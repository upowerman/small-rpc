package io.github.upowerman.provider.impl;

import io.github.upowerman.annotation.RpcService;
import io.github.upowerman.exception.RpcException;
import io.github.upowerman.net.base.NetEnum;
import io.github.upowerman.provider.RpcProviderFactory;
import io.github.upowerman.serialize.BaseSerializer;
import io.github.upowerman.serialize.SerializeEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

import java.util.Map;

/**
 * spring 整合
 *
 * @author gaoyunfeng
 */
public class RpcSpringProviderFactory extends RpcProviderFactory implements ApplicationContextAware, InitializingBean, DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(RpcSpringProviderFactory.class);

    /**
     * 默认采用NETTY 通信
     */
    private final String netType = NetEnum.NETTY.name();
    /**
     * 默认序列化方式为HESSIAN
     */
    private final String serialize = SerializeEnum.HESSIAN.name();


    private void prepareConfig() {
        NetEnum netTypeEnum = NetEnum.autoMatch(netType, null);
        SerializeEnum serializeEnum = SerializeEnum.match(serialize, null);
        BaseSerializer serializer = serializeEnum != null ? serializeEnum.getSerializer() : null;
        super.initConfig(netTypeEnum, serializer);
    }

    /**
     * 设置服务提供者信息
     *
     * @param applicationContext 上下文
     * @throws BeansException
     */
    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {

        Map<String, Object> serviceBeanMap = applicationContext.getBeansWithAnnotation(RpcService.class);
        if (serviceBeanMap.isEmpty()) {
            logger.info("No RPC services found with @RpcService annotation");
            return;
        }

        logger.info("Found {} RPC services to register", serviceBeanMap.size());
        for (Map.Entry<String, Object> entry : serviceBeanMap.entrySet()) {
            Object serviceBean = entry.getValue();
            if (serviceBean == null) {
                logger.warn("Skipping null service bean for key: {}", entry.getKey());
                continue;
            }

            Class<?>[] interfaces = serviceBean.getClass().getInterfaces();
            if (interfaces.length == 0) {
                throw new RpcException("服务提供类必须实现接口: " + serviceBean.getClass().getName());
            }

            RpcService rpcService = serviceBean.getClass().getAnnotation(RpcService.class);
            if (rpcService == null) {
                logger.warn("No @RpcService annotation found on {}, skipping", serviceBean.getClass().getName());
                continue;
            }

            String iface = interfaces[0].getName();
            String version = rpcService.version();
            logger.info("Registering RPC service: {} version: {}", iface, version);
            super.addService(iface, version, serviceBean);
        }
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        this.prepareConfig();
        super.start();
    }

    @Override
    public void destroy() throws Exception {
        super.stop();
    }

}
