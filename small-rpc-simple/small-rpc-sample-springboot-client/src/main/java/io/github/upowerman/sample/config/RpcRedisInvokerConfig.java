package io.github.upowerman.sample.config;

import io.github.upowerman.invoker.impl.RpcSpringInvokerFactory;
import io.github.upowerman.registry.impl.RedisServiceRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.HashMap;
import java.util.Map;

/**
 * Redis 注册中心客户端配置类示例
 * 使用 Redis 进行服务发现
 *
 * @author gaoyunfeng
 */
@Configuration
@Profile("redis")
public class RpcRedisInvokerConfig {

    @Value("${small-rpc.redis.host:localhost}")
    private String redisHost;

    @Value("${small-rpc.redis.port:6379}")
    private int redisPort;

    @Value("${small-rpc.redis.password:}")
    private String redisPassword;

    @Value("${small-rpc.redis.database:0}")
    private int redisDatabase;

    @Value("${small-rpc.redis.timeout:2000}")
    private int redisTimeout;

    @Bean
    public RpcSpringInvokerFactory rpcInvokerFactory() {
        RpcSpringInvokerFactory invokerFactory = new RpcSpringInvokerFactory();
        invokerFactory.setServiceRegistryClass(RedisServiceRegistry.class);

        // 配置 Redis 注册中心参数
        Map<String, String> registryParams = new HashMap<>();
        registryParams.put(RedisServiceRegistry.REDIS_HOST, redisHost);
        registryParams.put(RedisServiceRegistry.REDIS_PORT, String.valueOf(redisPort));
        registryParams.put(RedisServiceRegistry.REDIS_DATABASE, String.valueOf(redisDatabase));
        registryParams.put(RedisServiceRegistry.REDIS_TIMEOUT, String.valueOf(redisTimeout));
        
        if (redisPassword != null && !redisPassword.trim().isEmpty()) {
            registryParams.put(RedisServiceRegistry.REDIS_PASSWORD, redisPassword);
        }

        invokerFactory.setServiceRegistryParam(registryParams);
        return invokerFactory;
    }
}