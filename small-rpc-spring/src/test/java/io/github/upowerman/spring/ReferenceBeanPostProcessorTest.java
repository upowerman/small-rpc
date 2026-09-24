package io.github.upowerman.spring;

import io.github.upowerman.annotation.RpcReference;
import io.github.upowerman.core.transport.Transport;
import org.junit.Test;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 处理器装配 2.0 链路：@RpcReference 字段被注入非 null 代理，且代理可直连本地桩 Transport。
 * 不起真 Netty：注入 InMemoryTransport（P0 测试夹具同款语义，本模块内新建桩实现）。
 */
public class ReferenceBeanPostProcessorTest {

    public static class Consumer {
        @RpcReference(address = "localhost:0", timeout = 100)
        private EchoService echoService;
    }

    @Test
    public void rpcReferenceFieldIsInjectedWithWorkingProxy() throws Exception {
        // 桩 Transport：request 恒回 SUCCESS/echo（结果对象按 P0 Result 构造）
        Transport stub = new EchoStubTransport();
        ReferenceBeanPostProcessor processor = new ReferenceBeanPostProcessor(
                stub, new LocalServiceRegistryForTest());
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
}
