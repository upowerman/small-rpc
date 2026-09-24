package io.github.upowerman.core.e2e;

import io.github.upowerman.core.RpcConstants;
import io.github.upowerman.core.cluster.FailoverClusterInvoker;
import io.github.upowerman.core.directory.ServiceDirectory;
import io.github.upowerman.core.directory.ServiceInstance;
import io.github.upowerman.core.filter.Filter;
import io.github.upowerman.core.filter.TraceFilter;
import io.github.upowerman.core.invocation.GenericInvocation;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.invoker.RemoteInvoker;
import io.github.upowerman.core.loadbalance.RoundRobinLoadBalancer;
import io.github.upowerman.core.provider.ReflectiveInvoker;
import io.github.upowerman.core.proxy.RpcProxyFactory;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import io.github.upowerman.core.serialize.HessianSerializer;
import io.github.upowerman.core.serialize.SerializerRegistry;
import io.github.upowerman.core.server.RpcServer;
import io.github.upowerman.core.testsupport.EchoDTO;
import io.github.upowerman.core.testsupport.EchoService;
import io.github.upowerman.core.testsupport.EchoServiceImpl;
import io.github.upowerman.core.transport.NettyTransport;
import io.github.upowerman.core.transport.PendingRequests;
import io.github.upowerman.exception.RpcException;
import io.netty.channel.Channel;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 2.0 协议栈全链路（真 Netty、真端口）：
 * Proxy → TraceFilter → FailoverClusterInvoker → 匿名 Directory → RoundRobin →
 * RemoteInvoker → NettyTransport → 协议帧 → RpcServer → ReflectiveInvoker → 回帧。
 * 全程不经过任何 1.x 组件。
 */
public class NettyProtocolEndToEndTest {

    private static final HessianSerializer HESSIAN = new HessianSerializer();

    private static int port;
    private static RpcServer server;
    private static NettyTransport transport;

    /** 端口随测试运行时取空闲端口；存在极小的 bind 竞态，本地串行测试可接受 */
    private static int freePort() throws IOException {
        ServerSocket socket = new ServerSocket(0);
        try {
            return socket.getLocalPort();
        } finally {
            socket.close();
        }
    }

    @BeforeClass
    public static void startServer() throws Exception {
        port = freePort();
        SerializerRegistry registry = new SerializerRegistry().register(HESSIAN);
        server = new RpcServer(port, registry);
        server.register(EchoService.class.getName(),
                new ReflectiveInvoker(EchoService.class, new EchoServiceImpl()));
        server.start();   // bind().sync() 返回即已监听

        transport = new NettyTransport(HESSIAN);
    }

    @AfterClass
    public static void stopAll() {
        if (transport != null) {
            transport.shutdown();
        }
        if (server != null) {
            server.shutdown();
        }
    }

    private static String address() {
        return "127.0.0.1:" + port;
    }

    private static EchoService newProxy(int retries, long timeoutMillis) {
        final List<ServiceInstance> instances =
                Arrays.asList(new ServiceInstance(address()));
        ServiceDirectory directory = new ServiceDirectory() {
            @Override
            public List<ServiceInstance> list(String service) {
                return instances;
            }

            @Override
            public void subscribe(String service) {
                // P0/P1 适配器为空实现
            }
        };
        Invoker cluster = new FailoverClusterInvoker(directory, new RoundRobinLoadBalancer(),
                new RemoteInvoker(transport, EchoService.class), retries, timeoutMillis);
        return new RpcProxyFactory<EchoService>(EchoService.class,
                Collections.<Filter>singletonList(new TraceFilter()), cluster).getProxy();
    }

    // ---- [Task 5 加固] 白盒直探辅助：brief 已知弱点（repeatedCallsReuseOneConnection 的
    // 收尾一致性检查证不了复用也探不到泄漏）。NettyTransport 无公开池/in-flight 视图，
    // 为不新增生产 API，测试侧反射读取既有私有字段 —— 生产代码零改动。
    // 泄漏断言的确定性依据：PendingRequests.complete() 先 remove 后 complete，
    // 调用方 get() 返回时表项必已移除，无需轮询等待。

    @SuppressWarnings("unchecked")
    private static ConcurrentMap<String, Channel> pooledChannels() throws Exception {
        Field field = NettyTransport.class.getDeclaredField("channels");
        field.setAccessible(true);
        return (ConcurrentMap<String, Channel>) field.get(transport);
    }

    private static PendingRequests pendingRequests() throws Exception {
        Field field = NettyTransport.class.getDeclaredField("pending");
        field.setAccessible(true);
        return (PendingRequests) field.get(transport);
    }

    @Test
    public void fullChainEchoOverRealNetty() {
        EchoService echo = newProxy(1, 10000L);
        EchoDTO out = echo.echo(new EchoDTO("world"));
        assertEquals("echo:world", out.getMsg());
    }

    @Test
    public void serverSideExceptionSurfacesAsRpcExceptionToCaller() {
        EchoService echo = newProxy(0, 10000L);
        try {
            echo.echo(new EchoDTO("boom-now"));
            fail("expected RpcException for SERVER_ERROR result");
        } catch (RpcException expected) {
            assertTrue(expected.getMessage().contains("boom happened"));
        }
    }

    /** 直连 RemoteInvoker 观察原始状态码，不经 Failover 的重试语义 */
    @Test
    public void unknownServiceSettlesAsServiceNotFound() throws Exception {
        Map<String, Object> attachments = new HashMap<String, Object>();
        attachments.put(RpcConstants.ATTACH_ADDRESS, address());
        Invocation invocation = new GenericInvocation("no.such.Service", "go",
                new Class<?>[0], new Object[0], attachments);

        Result result = new RemoteInvoker(transport, EchoService.class)
                .invoke(invocation).get(10, TimeUnit.SECONDS);

        assertEquals(Status.SERVICE_NOT_FOUND, result.status());
    }

    /** Review Focus #5：并发请求必须按 requestId 各自回包，不串 */
    @Test
    public void concurrentRequestsRouteByRequestId() throws Exception {
        final EchoService echo = newProxy(0, 10000L);
        final int threads = 8;
        final int callsPerThread = 10;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicInteger failures = new AtomicInteger();
        try {
            for (int t = 0; t < threads; t++) {
                final int base = t * 1000;
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            for (int i = 0; i < callsPerThread; i++) {
                                String name = "t" + base + "-" + i;
                                EchoDTO out = echo.echo(new EchoDTO(name));
                                if (!("echo:" + name).equals(out.getMsg())) {
                                    failures.incrementAndGet();
                                }
                            }
                        } catch (Exception e) {
                            failures.incrementAndGet();
                        } finally {
                            done.countDown();
                        }
                    }
                });
            }
            assertTrue("all concurrent calls must finish", done.await(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, failures.get());
    }

    /** 连接复用：同一地址的多次调用共用一条 Channel，且调用后无悬挂 in-flight 条目 */
    @Test
    public void repeatedCallsReuseOneConnection() throws Exception {
        EchoService echo = newProxy(0, 10000L);
        // [Task 5 加固] 先让池对该地址建立 Channel 再取快照：使"全部调用复用同一实例"
        // 成为无条件断言（不依赖 JUnit 方法执行顺序），对下方 20+1 次 brief 调用全程生效
        assertEquals("echo:warmup", echo.echo(new EchoDTO("warmup")).getMsg());
        Channel before = pooledChannels().get(address());
        assertNotNull(before);
        for (int i = 0; i < 20; i++) {
            assertEquals("echo:c" + i, echo.echo(new EchoDTO("c" + i)).getMsg());
        }
        // 若前序调用有 in-flight 泄漏，后续调用仍会即时成功——本断言只是收尾一致性检查
        assertEquals("echo:final", echo.echo(new EchoDTO("final")).getMsg());
        // [Task 5 加固] 直探断言（不改动生产代码）：
        // (a) 池内该地址有且仅有一条活跃 Channel，且与调用前是同一实例——
        //     任何隐性重连都会以实例变化暴露，直接证明"复用"而非仅"仍可用"；
        // (b) 全部调用已结算后 in-flight 表必须为空（complete 先 remove 后 complete，
        //     JUnit 串行执行下前序测试的调用也已结算），直接探测泄漏。
        ConcurrentMap<String, Channel> pool = pooledChannels();
        assertEquals("one address must map to exactly one pooled channel", 1, pool.size());
        Channel reused = pool.get(address());
        assertNotNull(reused);
        assertTrue("pooled channel must still be active", reused.isActive());
        assertSame("all calls must reuse the same channel (silent reconnect would change identity)",
                before, reused);
        assertEquals("no in-flight entries may leak after settled calls",
                0, pendingRequests().size());
    }
}
