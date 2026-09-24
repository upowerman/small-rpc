package io.github.upowerman.spring.boot;

import io.github.upowerman.annotation.RpcService;
import io.github.upowerman.core.provider.ReflectiveInvoker;
import io.github.upowerman.core.serialize.SerializerRegistry;
import io.github.upowerman.core.server.RpcServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 提供方自动装配：收集 @RpcService bean → ReflectiveInvoker 注册 → 起 RpcServer。
 * 接口解析沿用 1.x RpcSpringProviderFactory 口径：getInterfaces()[0]。
 * 开关：small-rpc.provider.enabled（默认 true）——consumer 侧应用必须显式关掉，
 * 否则会跟着起一个空 provider 去抢端口（Ruling 6）。
 */
@Configuration
@ConditionalOnClass(RpcServer.class)
@ConditionalOnProperty(prefix = "small-rpc.provider", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnMissingBean(RpcServer.class)
@EnableConfigurationProperties(Rpc2Properties.class)
public class Rpc2ProviderAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(Rpc2ProviderAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean(SerializerRegistry.class)
    public SerializerRegistry serializerRegistry() {
        return SerializerRegistry.fromSpi();
    }

    @Bean(destroyMethod = "shutdown")
    public RpcServer rpc2Server(ApplicationContext applicationContext, Rpc2Properties properties,
                                SerializerRegistry serializerRegistry) throws InterruptedException {
        RpcServer server = new RpcServer(properties.getProvider().getRpc2Port(), serializerRegistry);
        int registered = 0;
        for (Object serviceBean : applicationContext.getBeansWithAnnotation(RpcService.class).values()) {
            Class<?>[] interfaces = serviceBean.getClass().getInterfaces();
            if (interfaces.length == 0) {
                throw new IllegalStateException("@RpcService 服务必须实现接口: "
                        + serviceBean.getClass().getName());
            }
            server.register(interfaces[0].getName(), new ReflectiveInvoker(interfaces[0], serviceBean));
            registered++;
        }
        server.start();
        logger.info("rpc2 provider started on port {} with {} services",
                properties.getProvider().getRpc2Port(), registered);
        return server;
    }
}
