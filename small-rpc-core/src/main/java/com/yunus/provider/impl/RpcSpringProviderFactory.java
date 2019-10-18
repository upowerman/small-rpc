package com.yunus.provider.impl;

import com.yunus.annotation.RpcService;
import com.yunus.exception.RpcException;
import com.yunus.net.base.NetEnum;
import com.yunus.provider.RpcProviderFactory;
import com.yunus.registry.BaseServiceRegistry;
import com.yunus.serialize.BaseSerializer;
import com.yunus.serialize.SerializeEnum;
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


    private String netType = NetEnum.NETTY.name();
    private String serialize = SerializeEnum.HESSIAN.name();

    private int corePoolSize;
    private int maxPoolSize;

    private String ip;
    private int port;
    private String accessToken;

    private Class<? extends BaseServiceRegistry> serviceRegistryClass;
    private Map<String, String> serviceRegistryParam;


    public void setNetType(String netType) {
        this.netType = netType;
    }

    public void setSerialize(String serialize) {
        this.serialize = serialize;
    }


    public void setCorePoolSize(int corePoolSize) {
        this.corePoolSize = corePoolSize;
    }

    public void setMaxPoolSize(int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public void setServiceRegistryClass(Class<? extends BaseServiceRegistry> serviceRegistryClass) {
        this.serviceRegistryClass = serviceRegistryClass;
    }

    public void setServiceRegistryParam(Map<String, String> serviceRegistryParam) {
        this.serviceRegistryParam = serviceRegistryParam;
    }


    private void prepareConfig() {
        NetEnum netTypeEnum = NetEnum.autoMatch(netType, null);
        SerializeEnum serializeEnum = SerializeEnum.match(serialize, null);
        BaseSerializer serializer = serializeEnum != null ? serializeEnum.getSerializer() : null;

        // init config
        super.initConfig(netTypeEnum, serializer, corePoolSize, maxPoolSize, ip, port, accessToken, serviceRegistryClass, serviceRegistryParam);
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
        if (serviceBeanMap != null && serviceBeanMap.size() > 0) {
            for (Object serviceBean : serviceBeanMap.values()) {
                if (serviceBean.getClass().getInterfaces().length == 0) {
                    throw new RpcException("rpc, service(RpcService) must inherit interface.");
                }
                RpcService RpcService = serviceBean.getClass().getAnnotation(RpcService.class);
                String iface = serviceBean.getClass().getInterfaces()[0].getName();
                String version = RpcService.version();
                super.addService(iface, version, serviceBean);
            }
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
