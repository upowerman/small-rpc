package io.github.upowerman.invoker.impl;

import io.github.upowerman.annotation.RpcReference;
import io.github.upowerman.exception.RpcException;
import io.github.upowerman.invoker.RpcInvokerFactory;
import io.github.upowerman.invoker.reference.RpcReferenceBean;
import io.github.upowerman.provider.RpcProviderFactory;
import io.github.upowerman.registry.BaseServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessorAdapter;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * @author gaoyunfeng
 */
public class RpcSpringInvokerFactory extends InstantiationAwareBeanPostProcessorAdapter implements InitializingBean, DisposableBean, BeanFactoryAware {

    private Logger logger = LoggerFactory.getLogger(RpcSpringInvokerFactory.class);
    /**
     * 服务注册实现类 todo
     */
    private Class<? extends BaseServiceRegistry> serviceRegistryClass;
    private Map<String, String> serviceRegistryParam;

    private RpcInvokerFactory rpcInvokerFactory;

    /**
     * 设置工厂类
     */
    private BeanFactory beanFactory;

    @Override
    public void afterPropertiesSet() throws Exception {
        rpcInvokerFactory = new RpcInvokerFactory(serviceRegistryClass, serviceRegistryParam);
        rpcInvokerFactory.start();
    }

    @Override
    public boolean postProcessAfterInstantiation(final Object bean, final String beanName) throws BeansException {
        final Set<String> serviceKeyList = new HashSet<>();
        // 判断实例化的bean中是否有被RpcReference注解的bean字段
        ReflectionUtils.doWithFields(bean.getClass(), new ReflectionUtils.FieldCallback() {
            @Override
            public void doWith(Field field) throws IllegalArgumentException, IllegalAccessException {
                if (field.isAnnotationPresent(RpcReference.class)) {
                    Class iface = field.getType();
                    if (!iface.isInterface()) {
                        throw new RpcException("服务提供类必须是接口");
                    }
                    RpcReference rpcReference = field.getAnnotation(RpcReference.class);
                    RpcReferenceBean referenceBean = new RpcReferenceBean(
                            rpcReference.netType(),
                            rpcReference.serializer().getSerializer(),
                            rpcReference.loadBalance(),
                            iface,
                            rpcReference.version(),
                            rpcReference.timeout(),
                            rpcReference.address(),
                            rpcInvokerFactory
                    );
                    // jdk设置代理对象
                    Object serviceProxy = referenceBean.getObject();
                    field.setAccessible(true);
                    field.set(bean, serviceProxy);
                    String serviceKey = RpcProviderFactory.makeServiceKey(iface.getName(), rpcReference.version());
                    serviceKeyList.add(serviceKey);

                }
            }
        });

        if (rpcInvokerFactory.getServiceRegistry() != null) {
            try {
                rpcInvokerFactory.getServiceRegistry().discovery(serviceKeyList);
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        }
        return super.postProcessAfterInstantiation(bean, beanName);
    }


    @Override
    public void destroy() throws Exception {
        rpcInvokerFactory.stop();
    }

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        this.beanFactory = beanFactory;
    }

    public void setServiceRegistryClass(Class<? extends BaseServiceRegistry> serviceRegistryClass) {
        this.serviceRegistryClass = serviceRegistryClass;
    }

    public void setServiceRegistryParam(Map<String, String> serviceRegistryParam) {
        this.serviceRegistryParam = serviceRegistryParam;
    }
}
