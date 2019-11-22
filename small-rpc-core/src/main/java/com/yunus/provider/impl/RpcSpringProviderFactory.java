package com.yunus.provider.impl;

import com.yunus.annotation.RpcService;
import com.yunus.exception.RpcException;
import com.yunus.net.base.NetEnum;
import com.yunus.provider.RpcProviderFactory;
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

    /**
     * 默认采用NETTY 通信
     */
    private String netType = NetEnum.NETTY.name();
    /**
     * 默认序列化方式为HESSIAN
     */
    private String serialize = SerializeEnum.HESSIAN.name();


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
        if (serviceBeanMap != null && serviceBeanMap.size() > 0) {
            for (Object serviceBean : serviceBeanMap.values()) {
                if (serviceBean.getClass().getInterfaces().length == 0) {
                    throw new RpcException("服务提供类必须是接口");
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
