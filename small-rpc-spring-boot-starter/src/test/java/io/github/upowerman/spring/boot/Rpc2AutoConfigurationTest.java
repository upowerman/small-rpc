package io.github.upowerman.spring.boot;

import io.github.upowerman.core.registry.BaseServiceRegistry;
import io.github.upowerman.core.serialize.SerializerRegistry;
import io.github.upowerman.core.spi.SpiLoader;
import org.junit.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * starter 自动装配的上下文级测试：真实起 Spring Boot 上下文（非 web），
 * 把 small-rpc.* 配置驱动的装配行为钉成回归（此前该层零自动化测试）。
 */
public class Rpc2AutoConfigurationTest {

    @Configuration
    @EnableAutoConfiguration
    static class TestApp {
    }

    @Test
    public void consumerOnlyContextHasNoSerializerRegistryBean() {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(TestApp.class)
                .web(WebApplicationType.NONE)
                .properties("small-rpc.provider.enabled=false",
                        "small-rpc.registry.type=local")
                .run();
        try {
            // consumer 侧不消费 SerializerRegistry（nettyTransport 直接用 SPI 取 Serializer），
            // 该 bean 曾是死代码，且与 provider 侧同名重复定义
            assertEquals(0, context.getBeanNamesForType(SerializerRegistry.class).length);
        } finally {
            context.close();
        }
    }

    @Test
    public void contextCloseDoesNotStopProcessLevelRegistrySingleton() {
        // SPI 扩展是进程级单例，跨 Spring 上下文共享；上下文关闭若把它 stop 掉，
        // 同一 JVM 的第二个上下文的地址表会被清空（LocalServiceRegistry.stop 会 clear）
        ConfigurableApplicationContext context = new SpringApplicationBuilder(TestApp.class)
                .web(WebApplicationType.NONE)
                .properties("small-rpc.provider.enabled=false",
                        "small-rpc.registry.type=local",
                        "small-rpc.registry.param[DIRECT_ADDRESS]=localhost:7081")
                .run();
        context.close();

        BaseServiceRegistry singleton = SpiLoader.of(BaseServiceRegistry.class).getExtension("local");
        assertFalse("上下文关闭不应清空进程级 SPI 单例的地址表",
                singleton.discovery("io.github.upowerman.service.HelloService").isEmpty());
    }
}
