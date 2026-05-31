package io.github.upowerman.sample.config;

import io.github.upowerman.exception.RpcException;
import io.github.upowerman.provider.impl.RpcSpringProviderFactory;
import io.github.upowerman.registry.BaseServiceRegistry;
import io.github.upowerman.registry.impl.LocalServiceRegistry;
import io.github.upowerman.registry.impl.RedisServiceRegistry;
import io.github.upowerman.registry.impl.ZookeeperServiceRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 配置类
 *
 * @author gaoyunfeng
 */
@Configuration
public class RpcProviderConfig {

    @Value("${small-rpc.provider.port}")
    private int port;

    @Value("${small-rpc.registry.type:local}")
    private String registryType;

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

    @Value("${small-rpc.zookeeper.connect-string:localhost:2181}")
    private String zkConnectString;

    @Value("${small-rpc.zookeeper.namespace:small-rpc}")
    private String zkNamespace;

    @Value("${small-rpc.zookeeper.base-path:/services}")
    private String zkBasePath;

    @Value("${small-rpc.zookeeper.session-timeout:60000}")
    private int zkSessionTimeout;

    @Value("${small-rpc.zookeeper.connection-timeout:15000}")
    private int zkConnectionTimeout;

    @Bean
    public RpcSpringProviderFactory rpcSpringProviderFactory() {
        RpcSpringProviderFactory providerFactory = new RpcSpringProviderFactory();
        providerFactory.setPort(port);
        providerFactory.setCorePoolSize(10);
        providerFactory.setMaxPoolSize(20);
        providerFactory.setServiceRegistryClass(resolveRegistryClass());
        providerFactory.setServiceRegistryParam(resolveRegistryParams());
        return providerFactory;
    }

    private Class<? extends BaseServiceRegistry> resolveRegistryClass() {
        String type = registryType == null ? "local" : registryType.trim().toLowerCase(Locale.ROOT);
        switch (type) {
            case "local":
                return LocalServiceRegistry.class;
            case "redis":
                return RedisServiceRegistry.class;
            case "zookeeper":
            case "zk":
                return ZookeeperServiceRegistry.class;
            default:
                throw new RpcException("不支持的注册中心类型: " + registryType + "，可选值: local|redis|zookeeper");
        }
    }

    private Map<String, String> resolveRegistryParams() {
        String type = registryType == null ? "local" : registryType.trim().toLowerCase(Locale.ROOT);
        Map<String, String> params = new HashMap<>();
        switch (type) {
            case "local":
                return params;
            case "redis":
                params.put(RedisServiceRegistry.REDIS_HOST, redisHost);
                params.put(RedisServiceRegistry.REDIS_PORT, String.valueOf(redisPort));
                params.put(RedisServiceRegistry.REDIS_DATABASE, String.valueOf(redisDatabase));
                params.put(RedisServiceRegistry.REDIS_TIMEOUT, String.valueOf(redisTimeout));
                if (redisPassword != null && !redisPassword.trim().isEmpty()) {
                    params.put(RedisServiceRegistry.REDIS_PASSWORD, redisPassword);
                }
                return params;
            case "zookeeper":
            case "zk":
                params.put(ZookeeperServiceRegistry.ZK_CONNECT_STRING, zkConnectString);
                params.put(ZookeeperServiceRegistry.ZK_NAMESPACE, zkNamespace);
                params.put(ZookeeperServiceRegistry.ZK_BASE_PATH, zkBasePath);
                params.put(ZookeeperServiceRegistry.ZK_SESSION_TIMEOUT, String.valueOf(zkSessionTimeout));
                params.put(ZookeeperServiceRegistry.ZK_CONNECTION_TIMEOUT, String.valueOf(zkConnectionTimeout));
                return params;
            default:
                throw new RpcException("不支持的注册中心类型: " + registryType + "，可选值: local|redis|zookeeper");
        }
    }
}
