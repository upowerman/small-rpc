package com.yunus.sample.config;

import com.yunus.invoker.impl.RpcSpringInvokerFactory;
import com.yunus.registry.impl.LocalServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;

/**
 * @author gaoyunfeng
 */
@Configuration
public class RpcInvokerConfig {
    private Logger logger = LoggerFactory.getLogger(RpcInvokerConfig.class);


    @Value("${small-rpc.registry.address}")
    private String address;

    @Bean
    public RpcSpringInvokerFactory JobExecutor() {
        RpcSpringInvokerFactory invokerFactory = new RpcSpringInvokerFactory();
        invokerFactory.setServiceRegistryClass(LocalServiceRegistry.class);
        HashMap<String, String> params = new HashMap<>();
        params.put(LocalServiceRegistry.DIRECT_ADDRESS, address);
        invokerFactory.setServiceRegistryParam(params);
        return invokerFactory;
    }
}
