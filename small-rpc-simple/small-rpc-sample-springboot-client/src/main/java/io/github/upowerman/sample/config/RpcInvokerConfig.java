package io.github.upowerman.sample.config;

import io.github.upowerman.core.adapter.LegacyNettyTransport;
import io.github.upowerman.core.cluster.FailoverClusterInvoker;
import io.github.upowerman.core.directory.PullServiceDirectory;
import io.github.upowerman.core.filter.Filter;
import io.github.upowerman.core.filter.TraceFilter;
import io.github.upowerman.core.invoker.RemoteInvoker;
import io.github.upowerman.core.loadbalance.RandomLoadBalancer;
import io.github.upowerman.core.proxy.RpcProxyFactory;
import io.github.upowerman.exception.RpcException;
import io.github.upowerman.invoker.RpcInvokerFactory;
import io.github.upowerman.invoker.impl.RpcSpringInvokerFactory;
import io.github.upowerman.registry.BaseServiceRegistry;
import io.github.upowerman.registry.impl.LocalServiceRegistry;
import io.github.upowerman.registry.impl.RedisServiceRegistry;
import io.github.upowerman.registry.impl.ZookeeperServiceRegistry;
import io.github.upowerman.serialize.HessianSerializer;
import io.github.upowerman.service.HelloService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * @author gaoyunfeng
 */
@Configuration
public class RpcInvokerConfig {


    @Value("${small-rpc.registry.type:local}")
    private String registryType;

    @Value("${small-rpc.registry.address:}")
    private String address;

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
    public RpcSpringInvokerFactory jobExecutor() {
        RpcSpringInvokerFactory invokerFactory = new RpcSpringInvokerFactory();
        invokerFactory.setServiceRegistryClass(resolveRegistryClass());
        invokerFactory.setServiceRegistryParam(resolveRegistryParams());
        return invokerFactory;
    }

    /**
     * 2.0 新链路代理：Proxy → TraceFilter → FailoverClusterInvoker →
     * PullServiceDirectory → LoadBalancer → RemoteInvoker → LegacyNettyTransport
     */
    @Bean
    public HelloService rpc2HelloService() {
        LegacyNettyTransport transport = new LegacyNettyTransport(
                new HessianSerializer(), new RpcInvokerFactory(), null);

        LocalServiceRegistry registry = new LocalServiceRegistry();
        Map<String, String> param = new HashMap<>();
        param.put(LocalServiceRegistry.DIRECT_ADDRESS, address);
        registry.start(param);

        PullServiceDirectory directory = new PullServiceDirectory(registry, null);
        FailoverClusterInvoker cluster = new FailoverClusterInvoker(
                directory, new RandomLoadBalancer(),
                new RemoteInvoker(transport, HelloService.class), 1, 3000);

        return new RpcProxyFactory<>(HelloService.class,
                Collections.<Filter>singletonList(new TraceFilter()), cluster).getProxy();
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
                if (address == null || address.trim().isEmpty()) {
                    throw new RpcException("local 注册中心必须配置 small-rpc.registry.address");
                }
                params.put(LocalServiceRegistry.DIRECT_ADDRESS, address);
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
