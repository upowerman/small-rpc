package io.github.upowerman.sample;

import io.github.upowerman.annotation.RpcService;
import io.github.upowerman.dto.HelloDTO;
import io.github.upowerman.sample.controller.HelloController;
import io.github.upowerman.service.HelloService;
import io.github.upowerman.spring.boot.Rpc2Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class RpcClientApplicationTest {

    @Configuration
    @EnableConfigurationProperties(Rpc2Properties.class)
    public static class PropertiesTestConfig {}

    @Service
    @RpcService
    public static class TestHelloService implements HelloService {
        @Override
        public HelloDTO hello(String name) {
            return new HelloDTO(name, "hello from example test");
        }
    }

    private static int freePort() throws IOException {
        ServerSocket socket = new ServerSocket(0);
        try {
            return socket.getLocalPort();
        } finally {
            socket.close();
        }
    }

    @Test
    public void endToEndCallWithStrongTypedLocalConfiguration() throws Exception {
        int rpcPort = freePort();
        int webPort = freePort();

        ConfigurableApplicationContext context = new SpringApplicationBuilder(
                RpcClientApplication.class, TestHelloService.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--server.port=" + webPort,
                        "--small-rpc.provider.enabled=true",
                        "--small-rpc.provider.rpc2-port=" + rpcPort,
                        "--small-rpc.consumer.enabled=true",
                        "--small-rpc.registry.type=local",
                        "--small-rpc.registry.local.direct-address=localhost:" + rpcPort);

        try {
            HelloController controller = context.getBean(HelloController.class);
            assertNotNull(controller);
            HelloDTO result = controller.hello("sample-test");
            assertNotNull(result);
            assertEquals("sample-test", result.getName());
            assertEquals("hello from example test", result.getWord());
        } finally {
            context.close();
        }
    }

    @Test
    public void zookeeperProfileLoadsStrongTypedProperties() {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(
                RpcClientApplication.class, PropertiesTestConfig.class)
                .web(WebApplicationType.NONE)
                .profiles("zookeeper")
                .run("--small-rpc.consumer.enabled=false");
        try {
            Rpc2Properties properties = context.getBean(Rpc2Properties.class);
            assertEquals("zookeeper", properties.getRegistry().getType());
            assertEquals("localhost:2181", properties.getRegistry().getZookeeper().getConnect());
            assertEquals("small-rpc", properties.getRegistry().getZookeeper().getNamespace());
        } finally {
            context.close();
        }
    }

    @Test
    public void redisProfileLoadsStrongTypedProperties() {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(
                RpcClientApplication.class, PropertiesTestConfig.class)
                .web(WebApplicationType.NONE)
                .profiles("redis")
                .run("--small-rpc.consumer.enabled=false");
        try {
            Rpc2Properties properties = context.getBean(Rpc2Properties.class);
            assertEquals("redis", properties.getRegistry().getType());
            assertEquals("localhost", properties.getRegistry().getRedis().getHost());
            assertEquals(6379, properties.getRegistry().getRedis().getPort());
            assertEquals(3000L, properties.getRegistry().getRedis().getPollIntervalMs());
        } finally {
            context.close();
        }
    }
}
