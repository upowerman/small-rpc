package com.yunus.invoker.impl;

import com.yunus.annotation.RpcReference;
import com.yunus.exception.RpcException;
import com.yunus.invoker.RpcInvokerFactory;
import com.yunus.invoker.reference.RpcReferenceBean;
import com.yunus.provider.RpcProviderFactory;
import com.yunus.registry.BaseServiceRegistry;
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

    private Class<? extends BaseServiceRegistry> serviceRegistryClass;
    private Map<String, String> serviceRegistryParam;

    private BeanFactory beanFactory;


    public void setServiceRegistryClass(Class<? extends BaseServiceRegistry> serviceRegistryClass) {
        this.serviceRegistryClass = serviceRegistryClass;
    }

    public void setServiceRegistryParam(Map<String, String> serviceRegistryParam) {
        this.serviceRegistryParam = serviceRegistryParam;
    }


    private RpcInvokerFactory rpcInvokerFactory;

    @Override
    public void afterPropertiesSet() throws Exception {
        rpcInvokerFactory = new RpcInvokerFactory(serviceRegistryClass, serviceRegistryParam);
        rpcInvokerFactory.start();
    }

    @Override
    public boolean postProcessAfterInstantiation(final Object bean, final String beanName) throws BeansException {

        final Set<String> serviceKeyList = new HashSet<>();
        ReflectionUtils.doWithFields(bean.getClass(), new ReflectionUtils.FieldCallback() {
            @Override
            public void doWith(Field field) throws IllegalArgumentException, IllegalAccessException {
                if (field.isAnnotationPresent(RpcReference.class)) {
                    Class iface = field.getType();
                    if (!iface.isInterface()) {
                        throw new RpcException("rpc, reference(RpcReference) must be interface.");
                    }
                    RpcReference rpcReference = field.getAnnotation(RpcReference.class);
                    RpcReferenceBean referenceBean = new RpcReferenceBean(
                            rpcReference.netType(),
                            rpcReference.serializer().getSerializer(),
                            rpcReference.callType(),
                            rpcReference.loadBalance(),
                            iface,
                            rpcReference.version(),
                            rpcReference.timeout(),
                            rpcReference.address(),
                            null,
                            rpcInvokerFactory
                    );

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
}
