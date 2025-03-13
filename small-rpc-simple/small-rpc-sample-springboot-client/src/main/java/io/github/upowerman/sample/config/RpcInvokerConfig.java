package io.github.upowerman.sample.config;

import io.github.upowerman.invoker.impl.RpcSpringInvokerFactory;
import io.github.upowerman.registry.impl.LocalServiceRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;

/**
 * @author gaoyunfeng
 */
@Configuration
public class RpcInvokerConfig {


    @Value("${small-rpc.registry.address}")
    private String address;

    @Bean
    public RpcSpringInvokerFactory jobExecutor() {
        RpcSpringInvokerFactory invokerFactory = new RpcSpringInvokerFactory();
        invokerFactory.setServiceRegistryClass(LocalServiceRegistry.class);
        HashMap<String, String> params = new HashMap<>(2);
        params.put(LocalServiceRegistry.DIRECT_ADDRESS, address);
        invokerFactory.setServiceRegistryParam(params);
        return invokerFactory;
    }
}
