package io.github.upowerman.spring;

import io.github.upowerman.annotation.RpcReference;
import io.github.upowerman.core.cluster.FailoverClusterInvoker;
import io.github.upowerman.core.directory.PullServiceDirectory;
import io.github.upowerman.core.filter.Filter;
import io.github.upowerman.core.filter.TraceFilter;
import io.github.upowerman.core.invoker.RemoteInvoker;
import io.github.upowerman.core.loadbalance.LoadBalancer;
import io.github.upowerman.core.proxy.RpcProxyFactory;
import io.github.upowerman.core.spi.SpiLoader;
import io.github.upowerman.core.transport.Transport;
import io.github.upowerman.core.registry.BaseServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessorAdapter;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.util.Collections;

/**
 * 消费方装配：字段 @RpcReference → 2.0 链路代理。
 * 链路（P1 样例验证过的同一结构）：RpcProxyFactory(TraceFilter)
 *   → FailoverClusterInvoker(retries=1, timeout=注解值)
 *   → PullServiceDirectory(registry) → SPI 选 LoadBalancer
 *   → RemoteInvoker(transport, iface)。
 * 逻辑自 1.x RpcSpringInvokerFactory.postProcessAfterInstantiation 原样搬运，只换链路内核。
 */
public class ReferenceBeanPostProcessor extends InstantiationAwareBeanPostProcessorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(ReferenceBeanPostProcessor.class);

    private final Transport transport;
    private final BaseServiceRegistry registry;

    public ReferenceBeanPostProcessor(Transport transport, BaseServiceRegistry registry) {
        this.transport = transport;
        this.registry = registry;
    }

    @Override
    public boolean postProcessAfterInstantiation(final Object bean, final String beanName) throws BeansException {
        ReflectionUtils.doWithFields(bean.getClass(), new ReflectionUtils.FieldCallback() {
            @Override
            public void doWith(Field field) throws IllegalArgumentException, IllegalAccessException {
                RpcReference reference = field.getAnnotation(RpcReference.class);
                if (reference == null) {
                    return;
                }
                Class<?> iface = field.getType();
                if (!iface.isInterface()) {
                    throw new IllegalStateException("@RpcReference 字段必须是接口: " + field);
                }
                Object proxy = buildProxy(iface, reference);
                field.setAccessible(true);
                field.set(bean, proxy);
                logger.info("rpc2 reference injected: {} -> {}", field, iface.getName());
            }
        });
        return true;
    }

    /** 测试直达入口（不走 Spring 生命周期） */
    void injectReferences(Object bean) throws IllegalAccessException {
        postProcessAfterInstantiation(bean, bean.getClass().getName());
    }

    private Object buildProxy(Class<?> iface, RpcReference reference) {
        PullServiceDirectory directory = new PullServiceDirectory(registry, null);
        LoadBalancer loadBalancer = reference.loadBalance().isEmpty()
                ? SpiLoader.of(LoadBalancer.class).getDefaultExtension()
                : SpiLoader.of(LoadBalancer.class).getExtension(reference.loadBalance());
        RemoteInvoker remoteInvoker = new RemoteInvoker(transport, iface);
        FailoverClusterInvoker cluster = new FailoverClusterInvoker(
                directory, loadBalancer, remoteInvoker, 1, reference.timeout());
        return new RpcProxyFactory(
                iface, Collections.<Filter>singletonList(new TraceFilter()), cluster, reference.timeout()).getProxy();
    }
}
