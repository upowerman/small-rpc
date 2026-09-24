package io.github.upowerman.spring.boot;

import io.github.upowerman.core.serialize.Serializer;
import io.github.upowerman.core.spi.SpiLoader;
import io.github.upowerman.core.transport.NettyTransport;
import io.github.upowerman.core.registry.Registry;
import io.github.upowerman.spring.ReferenceBeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 消费方自动装配：SPI 取注册中心 → NettyTransport（内部经 SPI 取默认序列化器）
 * → ReferenceBeanPostProcessor。
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

    /**
     * SPI 扩展是进程级单例，跨 Spring 上下文共享，因此这里<b>不挂 destroyMethod</b>：
     * 上下文关闭若把注册中心 destroy 掉，同一 JVM 里第二个上下文的地址表会被清空。
     * 带连接的注册中心（P3 ZK/Redis）需要独立的上下文级生命周期设计，届时再定。
     */
    @Bean
    @ConditionalOnMissingBean(Registry.class)
    public Registry rpc2Registry(Rpc2Properties properties) {
        Registry registry = SpiLoader.of(Registry.class)
                .getExtension(properties.getRegistry().getType());
        registry.init(properties.getRegistry().getParam());
        return registry;
    }

    @Bean
    public ReferenceBeanPostProcessor referenceBeanPostProcessor(NettyTransport nettyTransport,
                                                                 Registry rpc2Registry,
                                                                 Rpc2Properties properties) {
        // small-rpc.loadbalance 在此进入链路：注解未指定时作为缺省负载均衡扩展名
        return new ReferenceBeanPostProcessor(nettyTransport, rpc2Registry, properties.getLoadBalance());
    }
}
