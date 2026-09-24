package io.github.upowerman.spring.boot;

import io.github.upowerman.core.serialize.SerializerRegistry;
import org.junit.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import static org.junit.Assert.assertEquals;

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
}
