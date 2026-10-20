package io.github.upowerman.spring;

import io.github.upowerman.annotation.RpcReference;
import io.github.upowerman.core.directory.CachingServiceDirectory;
import io.github.upowerman.core.directory.ServiceDirectory;
import io.github.upowerman.core.directory.StaticServiceDirectory;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.registry.Registry;
import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import io.github.upowerman.core.transport.Connection;
import io.github.upowerman.core.transport.Endpoint;
import io.github.upowerman.core.transport.Transport;
import io.github.upowerman.exception.RpcException;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 处理器装配 2.0 链路：@RpcReference 字段被注入非 null 代理，且代理可直连本地桩 Transport。
 * 不起真 Netty：注入 InMemoryTransport（P0 测试夹具同款语义，本模块内新建桩实现）。
 */
public class ReferenceBeanPostProcessorTest {

    public static class Consumer {
        @RpcReference(timeout = 100)
        private EchoService echoService;
    }

    /** 直连：注解地址非空 → 不查注册中心 */
    public static class DirectConsumer {
        @RpcReference(address = "127.0.0.1:7099", timeout = 100)
        private EchoService echoService;
    }

    /** 注解显式重试 2 次：可容忍桩 Transport 前两次失败 */
    public static class RetryConsumer {
        @RpcReference(retries = 2, timeout = 2000)
        private EchoService echoService;
    }

    /** 注解显式 0 次重试：首次失败直接抛出 */
    public static class NoRetryConsumer {
        @RpcReference(retries = 0, timeout = 2000)
        private EchoService echoService;
    }

    /** 前 failCount 次 request 回 NETWORK_ERROR，其后回 SUCCESS——观测 Failover 重试次数 */
    private static Transport flakyTransport(final int failCount, final List<Integer> attemptLog) {
        return new Transport() {
            @Override
            public Connection connect(Endpoint endpoint) {
                return new Connection() {
                    @Override
                    public CompletableFuture<Result> request(Invocation invocation) {
                        attemptLog.add(attemptLog.size() + 1);
                        if (attemptLog.size() <= failCount) {
                            return CompletableFuture.completedFuture(DefaultResult.failure(
                                    Status.NETWORK_ERROR,
                                    new RpcException("flaky failure #" + attemptLog.size())));
                        }
                        return CompletableFuture.completedFuture(DefaultResult.success("recovered"));
                    }

                    @Override
                    public void close() {
                    }
                };
            }
        };
    }

    /** 记录实际连接地址的桩 Transport：用于观测目录解析与负载均衡选择结果 */
    private static Transport recordingTransport(final List<String> addresses) {
        return new Transport() {
            @Override
            public Connection connect(Endpoint endpoint) {
                addresses.add(endpoint.address());
                return new Connection() {
                    @Override
                    public CompletableFuture<Result> request(Invocation invocation) {
                        return CompletableFuture.completedFuture(DefaultResult.success("echo"));
                    }

                    @Override
                    public void close() {
                    }
                };
            }
        };
    }

    private static EchoService injectedEchoService(Object bean, String fieldName) throws Exception {
        Field field = bean.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (EchoService) field.get(bean);
    }

    @Test
    public void rpcReferenceFieldIsInjectedWithWorkingProxy() throws Exception {
        // 桩 Transport：request 恒回 SUCCESS/echo（结果对象按 P0 Result 构造）
        Transport stub = new EchoStubTransport();
        ReferenceBeanPostProcessor processor = new ReferenceBeanPostProcessor(
                stub, new LocalServiceRegistryForTest(), "");
        Consumer consumer = new Consumer();
        processor.injectReferences(consumer);
        Field field = Consumer.class.getDeclaredField("echoService");
        field.setAccessible(true);
        Object proxy = field.get(consumer);
        assertNotNull(proxy);
        assertTrue(field.getType().isInstance(proxy));

        EchoService echoService = (EchoService) proxy;
        assertEquals("echo", echoService.echo("hello"));
    }

    @Test
    public void loadBalanceNameResolvesAnnotationThenConfiguredDefaultThenSpiDefault() {
        // 三态：注解值 > small-rpc.loadbalance > 接口 @Spi 默认名（LoadBalancer = random）
        assertEquals("roundrobin", ReferenceBeanPostProcessor.resolveLoadBalanceName("roundrobin", "random"));
        assertEquals("roundrobin", ReferenceBeanPostProcessor.resolveLoadBalanceName("  roundrobin ", "random"));
        assertEquals("roundrobin", ReferenceBeanPostProcessor.resolveLoadBalanceName("", "roundrobin"));
        assertEquals("roundrobin", ReferenceBeanPostProcessor.resolveLoadBalanceName(null, "roundrobin"));
        assertEquals("random", ReferenceBeanPostProcessor.resolveLoadBalanceName("", ""));
        assertEquals("random", ReferenceBeanPostProcessor.resolveLoadBalanceName(null, null));
    }

    @Test
    public void timeoutResolvesAnnotationThenGlobalAndFailsLoudOnBadGlobal() {
        assertEquals(200L, ReferenceBeanPostProcessor.resolveTimeout(200, 5000));
        assertEquals(5000L, ReferenceBeanPostProcessor.resolveTimeout(-1, 5000));
        assertEquals(5000L, ReferenceBeanPostProcessor.resolveTimeout(0, 5000)); // 0 = 未设置
        try {
            ReferenceBeanPostProcessor.resolveTimeout(-1, 0);
            fail("expected IllegalArgumentException on non-positive global timeout");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("timeout-ms"));
        }
    }

    @Test
    public void retriesResolveAnnotationThenGlobalAndFailsLoudOnNegativeGlobal() {
        assertEquals(3, ReferenceBeanPostProcessor.resolveRetries(3, 1));
        assertEquals(0, ReferenceBeanPostProcessor.resolveRetries(0, 1)); // 0 = 显式不重试
        assertEquals(1, ReferenceBeanPostProcessor.resolveRetries(-1, 1));
        try {
            ReferenceBeanPostProcessor.resolveRetries(-1, -2);
            fail("expected IllegalArgumentException on negative global retries");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("retries"));
        }
    }

    @Test
    public void annotationRetriesDriveFailoverAttempts() throws Exception {
        List<Integer> attempts = new ArrayList<Integer>();
        ReferenceBeanPostProcessor processor = new ReferenceBeanPostProcessor(
                flakyTransport(2, attempts), new LocalServiceRegistryForTest("127.0.0.1:1"), "");
        RetryConsumer consumer = new RetryConsumer();
        processor.injectReferences(consumer);

        assertEquals("recovered", injectedEchoService(consumer, "echoService").echo("hi"));
        assertEquals("retries=2 应恰好发起 3 次尝试", 3, attempts.size());
    }

    @Test
    public void zeroRetriesSurfacesFailureImmediately() throws Exception {
        List<Integer> attempts = new ArrayList<Integer>();
        ReferenceBeanPostProcessor processor = new ReferenceBeanPostProcessor(
                flakyTransport(2, attempts), new LocalServiceRegistryForTest("127.0.0.1:1"), "");
        NoRetryConsumer consumer = new NoRetryConsumer();
        processor.injectReferences(consumer);

        try {
            injectedEchoService(consumer, "echoService").echo("hi");
            fail("expected RpcException");
        } catch (RpcException expected) {
            // ok
        }
        assertEquals("retries=0 只允许 1 次尝试", 1, attempts.size());
    }

    @Test
    public void configuredLoadBalanceDrivesInstanceSelection() throws Exception {
        // small-rpc.loadbalance 生效的用户可见效果：注解未指定时按配置的 SPI 扩展选实例
        List<String> calls = new ArrayList<String>();
        ReferenceBeanPostProcessor processor = new ReferenceBeanPostProcessor(
                recordingTransport(calls), new LocalServiceRegistryForTest("127.0.0.1:1", "127.0.0.1:2"),
                "roundrobin");
        Consumer consumer = new Consumer();
        processor.injectReferences(consumer);
        EchoService echoService = injectedEchoService(consumer, "echoService");

        echoService.echo("first");
        echoService.echo("second");
        echoService.echo("third");

        assertEquals(3, calls.size());
        assertNotEquals("轮询：相邻两次应选到不同实例", calls.get(0), calls.get(1));
        assertEquals("轮询：第三次回到第一个实例", calls.get(0), calls.get(2));
    }

    @Test
    public void unknownConfiguredLoadBalanceFailsLoudly() throws Exception {
        ReferenceBeanPostProcessor processor = new ReferenceBeanPostProcessor(
                new EchoStubTransport(), new LocalServiceRegistryForTest(), "nope");
        try {
            processor.injectReferences(new Consumer());
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("nope"));
        }
    }

    @Test
    public void nonBlankAddressResolvesToStaticDirectory() {
        ServiceDirectory directory = ReferenceBeanPostProcessor.resolveDirectory(
                new LocalServiceRegistryForTest(), "127.0.0.1:7099");

        assertTrue(directory instanceof StaticServiceDirectory);
        assertEquals("127.0.0.1:7099", directory.list("a.B").get(0).getAddress());
    }

    @Test
    public void blankAddressResolvesToRegistryDirectory() {
        Registry registry = new LocalServiceRegistryForTest();

        assertTrue(ReferenceBeanPostProcessor.resolveDirectory(registry, "")
                instanceof CachingServiceDirectory);
        assertTrue(ReferenceBeanPostProcessor.resolveDirectory(registry, "   ")
                instanceof CachingServiceDirectory);
        assertTrue(ReferenceBeanPostProcessor.resolveDirectory(registry, null)
                instanceof CachingServiceDirectory);
    }

    @Test
    public void addressAnnotationDrivesDirectConnectInsteadOfRegistry() throws Exception {
        // 注册中心里只有 localhost:0；注解地址非空时必须直连注解地址
        List<String> calls = new ArrayList<String>();
        ReferenceBeanPostProcessor processor = new ReferenceBeanPostProcessor(
                recordingTransport(calls), new LocalServiceRegistryForTest(), "");
        DirectConsumer consumer = new DirectConsumer();
        processor.injectReferences(consumer);

        assertEquals("echo", injectedEchoService(consumer, "echoService").echo("hello"));
        assertEquals(Collections.singletonList("127.0.0.1:7099"), calls);
    }
}
