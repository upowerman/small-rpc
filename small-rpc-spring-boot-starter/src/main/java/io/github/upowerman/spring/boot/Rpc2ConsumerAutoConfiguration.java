package io.github.upowerman.spring.boot;

import io.github.upowerman.core.generic.GenericService;
import io.github.upowerman.core.generic.GenericServiceFactory;
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
    public NettyTransport nettyTransport(Rpc2Properties properties) {
        // small-rpc.serializer 选择消费端序列化扩展；空 = 接口 @Spi 默认（hessian）。
        // 服务端按帧 codec typeId 自动识别，无需对称配置
        String serializerName = properties.getSerializer();
        Serializer serializer = (serializerName == null || serializerName.trim().isEmpty())
                ? SpiLoader.of(Serializer.class).getDefaultExtension()
                : SpiLoader.of(Serializer.class).getExtension(serializerName.trim());
        // small-rpc.consumer.max-in-flight：客户端在途背压上限（0 = 不限）
        return new NettyTransport(serializer, NettyTransport.DEFAULT_REQUEST_TIMEOUT_MILLIS,
                properties.getConsumer().getMaxInFlight());
    }

    /**
     * SPI 扩展是进程级单例，跨 Spring 上下文共享，因此这里<b>不挂 destroyMethod</b>：
     * 上下文关闭若把注册中心 destroy 掉，同一 JVM 里第二个上下文的地址表会被清空。
     * 带连接的注册中心（P3 ZK/Redis）需要独立的上下文级生命周期设计，届时再定。
     */
    @Bean
    @ConditionalOnMissingBean(Registry.class)
    public Registry rpc2Registry(Rpc2Properties properties) {
        return RegistrySupport.obtainRegistry(properties);
    }

    @Bean
    public ReferenceBeanPostProcessor referenceBeanPostProcessor(NettyTransport nettyTransport,
                                                                 Registry rpc2Registry,
                                                                 Rpc2Properties properties,
                                                                 org.springframework.beans.factory.BeanFactory beanFactory) {
        // small-rpc.loadbalance 与 small-rpc.consumer.timeout-ms/retries 在此进入链路：
        // 注解未指定时作为缺省负载均衡扩展名 / 缺省超时与重试；
        // BeanFactory 供 @RpcReference.fallback 优先解析容器 bean；
        // 治理规则在此构建——非法配置启动即失败（大声失败原则）
        Rpc2Properties.Consumer consumer = properties.getConsumer();
        Rpc2Properties.Governance governance = consumer.getGovernance();
        io.github.upowerman.core.governance.CircuitBreakerRule circuitBreakerRule = null;
        Rpc2Properties.Governance.CircuitBreaker cb = governance.getCircuitBreaker();
        if (cb.isEnabled()) {
            circuitBreakerRule = new io.github.upowerman.core.governance.CircuitBreakerRule(
                    cb.getErrorRateThreshold(), cb.getSlowCallRateThreshold(), cb.getSlowRtMs(),
                    cb.getMinCalls(), cb.getWindowMs(), cb.getOpenMs(), cb.getHalfOpenProbes());
        }
        io.github.upowerman.core.governance.RateLimitRule rateLimitRule = null;
        Rpc2Properties.Governance.RateLimit rl = governance.getRateLimit();
        if (rl.isEnabled() && rl.getQps() > 0) {
            rateLimitRule = new io.github.upowerman.core.governance.RateLimitRule(rl.getQps());
        }
        return new ReferenceBeanPostProcessor(nettyTransport, rpc2Registry, properties.getLoadBalance(),
                consumer.getTimeoutMs(), consumer.getRetries(),
                circuitBreakerRule, rateLimitRule, rl.getMaxConcurrent(), beanFactory);
    }

    /** C7：泛化调用门面——网关/运维场景不引入业务接口 jar 直接按服务名+方法名调用 */
    @Bean
    @ConditionalOnMissingBean(GenericService.class)
    public GenericService smallRpcGenericService(NettyTransport nettyTransport,
                                                 Registry rpc2Registry,
                                                 Rpc2Properties properties) {
        return GenericServiceFactory.create(nettyTransport, rpc2Registry, properties.getLoadBalance(),
                properties.getConsumer().getRetries(), properties.getConsumer().getTimeoutMs());
    }
}
