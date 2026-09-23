package io.github.upowerman.core.adapter;

import io.github.upowerman.core.cluster.FailoverClusterInvoker;
import io.github.upowerman.core.directory.PullServiceDirectory;
import io.github.upowerman.core.filter.Filter;
import io.github.upowerman.core.filter.TraceFilter;
import io.github.upowerman.core.invocation.GenericInvocation;
import io.github.upowerman.core.invoker.RemoteInvoker;
import io.github.upowerman.core.loadbalance.RandomLoadBalancer;
import io.github.upowerman.core.proxy.RpcProxyFactory;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import io.github.upowerman.core.transport.Transport;
import io.github.upowerman.invoker.RpcInvokerFactory;
import io.github.upowerman.invoker.reference.RpcReferenceBean;
import io.github.upowerman.invoker.route.LoadBalance;
import io.github.upowerman.net.base.NetEnum;
import io.github.upowerman.provider.RpcProviderFactory;
import io.github.upowerman.registry.impl.LocalServiceRegistry;
import io.github.upowerman.serialize.HessianSerializer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class LegacyNettyTransportIntegrationTest {

    public interface EchoService {
        EchoDTO echo(EchoDTO dto);
    }

    public static class EchoDTO implements Serializable {
        private static final long serialVersionUID = 1L;
        private String msg;

        public EchoDTO() {
        }

        public EchoDTO(String msg) {
            this.msg = msg;
        }

        public String getMsg() {
            return msg;
        }

        public void setMsg(String msg) {
            this.msg = msg;
        }
    }

    public static class EchoServiceImpl implements EchoService {
        @Override
        public EchoDTO echo(EchoDTO dto) {
            return new EchoDTO("echo:" + dto.getMsg());
        }
    }

    /** 服务端返回错误状态：方法执行抛异常 → 1.x 回 errorMsg → 桥接为 SERVER_ERROR（可重试） */
    public static class ThrowingEchoServiceImpl implements EchoService {
        @Override
        public EchoDTO echo(EchoDTO dto) {
            throw new IllegalStateException("boom");
        }
    }

    /** 端口随测试运行时取空闲端口（替代硬编码 18080）；存在极小的 bind 竞态，本地串行测试可接受 */
    private static int freePort() throws IOException {
        ServerSocket socket = new ServerSocket(0);
        try {
            return socket.getLocalPort();
        } finally {
            socket.close();
        }
    }

    /** NettyServer.start() 是线程内异步 bind，轮询直到端口真正可连接，避免客户端 connect 抢在 bind 之前 */
    private static void awaitListening(int port) throws Exception {
        long deadline = System.currentTimeMillis() + 10000L;
        while (System.currentTimeMillis() < deadline) {
            try (Socket probe = new Socket()) {
                probe.connect(new InetSocketAddress("127.0.0.1", port), 1000);
                return;
            } catch (IOException e) {
                Thread.sleep(50L);
            }
        }
        throw new IllegalStateException("server did not listen on 127.0.0.1:" + port);
    }

    private int port;
    private RpcProviderFactory providerFactory;

    @Before
    public void startProvider() throws Exception {
        port = freePort();
        providerFactory = new RpcProviderFactory();
        providerFactory.setNetType(NetEnum.NETTY);
        providerFactory.setSerializer(new HessianSerializer());
        providerFactory.setPort(port);
        // 校验配置并补齐线程池默认参数（core=60/max=300），不调用则池参数为 0
        providerFactory.initConfig();
        providerFactory.addService(EchoService.class.getName(), null, new EchoServiceImpl());
        providerFactory.start();
        awaitListening(port);
    }

    @After
    public void stopProvider() throws Exception {
        if (providerFactory != null) {
            providerFactory.stop();
        }
    }

    @Test
    public void newChainCallsRealNettyServer() throws Exception {
        LegacyNettyTransport transport = new LegacyNettyTransport(new HessianSerializer(), null, null);
        try {
            EchoService echoService = newChain(transport);
            assertEquals("echo:world", echoService.echo(new EchoDTO("world")).getMsg());
        } finally {
            transport.close();
        }
    }

    /** C2 回归：1.x 先跑，2.0 后跑；两者共址同一真实 Netty server，都必须成功（1.x 占静态池后 2.0 不得串话） */
    @Test
    public void oneXFirstThenTwoXShareSameAddress() throws Exception {
        RpcInvokerFactory oneFactory = new RpcInvokerFactory();
        LegacyNettyTransport transport = new LegacyNettyTransport(new HessianSerializer(), new RpcInvokerFactory(), null);
        try {
            EchoService one = oneXProxy(oneFactory);
            assertEquals("echo:one-x", one.echo(new EchoDTO("one-x")).getMsg());

            EchoService two = newChain(transport);
            assertEquals("echo:two-x", two.echo(new EchoDTO("two-x")).getMsg());
        } finally {
            transport.close();
            oneFactory.stop();
        }
    }

    /** C2 回归：2.0 先跑，1.x 后跑；两者共址同一真实 Netty server，都必须成功 */
    @Test
    public void twoXFirstThenOneXShareSameAddress() throws Exception {
        RpcInvokerFactory oneFactory = new RpcInvokerFactory();
        LegacyNettyTransport transport = new LegacyNettyTransport(new HessianSerializer(), new RpcInvokerFactory(), null);
        try {
            EchoService two = newChain(transport);
            assertEquals("echo:two-x", two.echo(new EchoDTO("two-x")).getMsg());

            EchoService one = oneXProxy(oneFactory);
            assertEquals("echo:one-x", one.echo(new EchoDTO("one-x")).getMsg());
        } finally {
            transport.close();
            oneFactory.stop();
        }
    }

    /** I1 回归：服务端返回错误状态，重试真实回流经传输层；chainHead 的有界 get 必须结算、不悬挂 */
    @Test
    public void serverErrorRetriesAndSettlesWithBoundedGet() throws Exception {
        // 覆盖为"会抛异常"的实现：服务端回 errorMsg → SERVER_ERROR → 可重试 → 耗尽 → 以 SERVER_ERROR 结算
        providerFactory.addService(EchoService.class.getName(), null, new ThrowingEchoServiceImpl());

        LegacyNettyTransport transport = new LegacyNettyTransport(new HessianSerializer(), new RpcInvokerFactory(), null);
        try {
            FailoverClusterInvoker cluster = newCluster(transport, 5000L);
            Result result = cluster.invoke(new GenericInvocation(
                    EchoService.class.getName(), "echo",
                    new Class<?>[]{EchoDTO.class}, new Object[]{new EchoDTO("x")},
                    new HashMap<String, Object>())).get(10, TimeUnit.SECONDS);
            assertSame(Status.SERVER_ERROR, result.status());
        } finally {
            transport.close();
        }
    }

    /** 构造 2.0 新链路代理（默认超时 5000ms） */
    private EchoService newChain(Transport transport) {
        return new RpcProxyFactory<EchoService>(EchoService.class,
                Collections.<Filter>singletonList(new TraceFilter()),
                newCluster(transport, 5000L)).getProxy();
    }

    private FailoverClusterInvoker newCluster(Transport transport, long timeoutMillis) {
        LocalServiceRegistry registry = new LocalServiceRegistry();
        Map<String, String> param = new HashMap<String, String>();
        param.put(LocalServiceRegistry.DIRECT_ADDRESS, "127.0.0.1:" + port);
        registry.start(param);
        return new FailoverClusterInvoker(
                new PullServiceDirectory(registry, null), new RandomLoadBalancer(),
                new RemoteInvoker(transport, EchoService.class), 1, timeoutMillis);
    }

    /** 构造 1.x 直连同址代理 */
    private EchoService oneXProxy(RpcInvokerFactory invokerFactory) {
        RpcReferenceBean referenceBean = new RpcReferenceBean(
                NetEnum.NETTY, new HessianSerializer(), LoadBalance.RANDOM,
                EchoService.class, null, 5000L, "127.0.0.1:" + port, invokerFactory);
        return (EchoService) referenceBean.getObject();
    }
}
