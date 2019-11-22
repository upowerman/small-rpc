package com.yunus.sample.config;

import com.yunus.provider.impl.RpcSpringProviderFactory;
import com.yunus.registry.impl.LocalServiceRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;

/**
 * 配置类
 *
 * @author gaoyunfeng
 */
@Configuration
public class RpcProviderConfig {

    @Value("${small-rpc.provider.port}")
    private int port;

    @Bean
    public RpcSpringProviderFactory rpcSpringProviderFactory() {
        RpcSpringProviderFactory providerFactory = new RpcSpringProviderFactory();
        providerFactory.setPort(port);
        providerFactory.setCorePoolSize(10);
        providerFactory.setMaxPoolSize(20);
        providerFactory.setServiceRegistryClass(LocalServiceRegistry.class);
        providerFactory.setServiceRegistryParam(Collections.EMPTY_MAP);
        return providerFactory;
    }
}
