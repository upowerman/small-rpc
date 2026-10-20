package io.github.upowerman.spring;

import io.github.upowerman.annotation.RpcReference;
import io.github.upowerman.core.directory.ServiceInstance;
import io.github.upowerman.core.provider.ReflectiveInvoker;
import io.github.upowerman.core.registry.Registry;
import io.github.upowerman.core.registry.ServiceListener;
import io.github.upowerman.core.transport.InMemoryTransport;
import org.junit.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/** fallback 注解解析与链路装配：反射实例化 / Spring bean 优先 / 类型校验大声失败 */
public class ReferenceFallbackTest {

    public interface EchoService {
        String echo(String msg);
    }

    public static class FlakyEcho implements EchoService {
        @Override public String echo(String msg) {
            throw new IllegalStateException("always down");
        }
    }

    public static class EchoFallback implements EchoService {
        final String tag;

        public EchoFallback() {
            this.tag = "reflective";
        }

        public EchoFallback(String tag) {
            this.tag = tag;
        }

        @Override public String echo(String msg) {
            return tag + ":" + msg;
        }
    }

    public static class ConsumerBean {
        @RpcReference(fallback = EchoFallback.class)
        public EchoService echoService;
    }

    private ReferenceBeanPostProcessor processor(boolean withBean) {
        InMemoryTransport transport = new InMemoryTransport();
        transport.register("127.0.0.1:7080", new ReflectiveInvoker(EchoService.class, new FlakyEcho()));
        if (withBean) {
            DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
            beanFactory.registerSingleton("echoFallback", new EchoFallback("spring-bean"));
            return new ReferenceBeanPostProcessor(transport, stubRegistry(), "", 1000, 0, beanFactory);
        }
        return new ReferenceBeanPostProcessor(transport, stubRegistry(), "", 1000, 0, null);
    }

    @Test
    public void reflectiveFallbackRescuesFailure() throws Exception {
        ConsumerBean bean = new ConsumerBean();
        processor(false).injectReferences(bean);
        assertEquals("reflective:hi", bean.echoService.echo("hi"));
    }

    @Test
    public void springBeanFallbackTakesPriority() throws Exception {
        ConsumerBean bean = new ConsumerBean();
        processor(true).injectReferences(bean);
        assertEquals("spring-bean:hi", bean.echoService.echo("hi"));
    }

    @Test
    public void mismatchedFallbackTypeFailsLoudly() {
        try {
            // String 不是 EchoService 子类
            ReferenceBeanPostProcessor.resolveFallback(EchoService.class, String.class, null);
            fail("类型不匹配应大声失败");
        } catch (IllegalStateException expected) {
            // 预期
        }
    }

    private static Registry stubRegistry() {
        final List<ServiceInstance> instances = new ArrayList<ServiceInstance>();
        instances.add(new ServiceInstance("127.0.0.1:7080"));
        return new Registry() {
            @Override public void init(Map<String, String> param) { }
            @Override public void destroy() { }
            @Override public void register(String service, ServiceInstance instance) { }
            @Override public void unregister(String service, ServiceInstance instance) { }
            @Override public void subscribe(String service, ServiceListener listener) {
                listener.onChange(new ArrayList<ServiceInstance>(instances));
            }
            @Override public void unsubscribe(String service, ServiceListener listener) { }
        };
    }
}
