package io.github.upowerman.spring;

import io.github.upowerman.annotation.RpcReference;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.transport.Connection;
import io.github.upowerman.core.transport.Endpoint;
import io.github.upowerman.core.transport.Transport;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
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
        @RpcReference(address = "localhost:0", timeout = 100)
        private EchoService echoService;
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
}
