package io.github.upowerman.spring.boot;

import io.github.upowerman.core.serialize.Serializer;
import io.github.upowerman.core.serialize.SerializerRegistry;
import io.github.upowerman.core.spi.SpiLoader;
import io.github.upowerman.core.transport.NettyTransport;
import io.github.upowerman.core.registry.BaseServiceRegistry;
import io.github.upowerman.spring.ReferenceBeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 消费方自动装配：SPI 取注册中心与序列化 → NettyTransport → ReferenceBeanPostProcessor。
 * 开关：small-rpc.consumer.enabled（默认 true）——provider 侧应用必须显式关掉（Ruling 6）。
 */
@Configuration
@ConditionalOnClass(NettyTransport.class)
@ConditionalOnProperty(prefix = "small-rpc.consumer", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(Rpc2Properties.class)
public class Rpc2ConsumerAutoConfiguration {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(NettyTransport.class)
    public NettyTransport nettyTransport() {
        Serializer serializer = SpiLoader.of(Serializer.class).getDefaultExtension();
        return new NettyTransport(serializer);
    }

    @Bean(destroyMethod = "stop")
    public BaseServiceRegistry rpc2Registry(Rpc2Properties properties) {
        BaseServiceRegistry registry = SpiLoader.of(BaseServiceRegistry.class)
                .getExtension(properties.getRegistry().getType());
        registry.start(properties.getRegistry().getParam());
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean(SerializerRegistry.class)
    public SerializerRegistry serializerRegistry() {
        return SerializerRegistry.fromSpi();
    }

    @Bean
    public ReferenceBeanPostProcessor referenceBeanPostProcessor(NettyTransport nettyTransport,
                                                                 BaseServiceRegistry rpc2Registry) {
        return new ReferenceBeanPostProcessor(nettyTransport, rpc2Registry);
    }
}
