package io.github.upowerman.sample;

import io.github.upowerman.core.server.RpcServer;
import io.github.upowerman.spring.boot.Rpc2Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class RpcServerApplicationTest {

    @Configuration
    @EnableConfigurationProperties(Rpc2Properties.class)
    public static class PropertiesTestConfig {}

    private static int freePort() throws IOException {
        ServerSocket socket = new ServerSocket(0);
        try {
            return socket.getLocalPort();
        } finally {
            socket.close();
        }
    }

    @Test
    public void serverStartsSuccessfullyWithDefaultConfig() throws Exception {
        int rpcPort = freePort();
        int webPort = freePort();
        ConfigurableApplicationContext context = new SpringApplicationBuilder(RpcServerApplication.class)
                .web(WebApplicationType.NONE)
                .run(
                        "--server.port=" + webPort,
                        "--small-rpc.provider.rpc2-port=" + rpcPort);
        try {
            assertNotNull(context.getBean(RpcServer.class));
            Rpc2Properties properties = context.getBean(Rpc2Properties.class);
            assertEquals(rpcPort, properties.getProvider().getRpc2Port());
        } finally {
            context.close();
        }
    }

    @Test
    public void zookeeperProfileLoadsStrongTypedProperties() {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(
                RpcServerApplication.class, PropertiesTestConfig.class)
                .web(WebApplicationType.NONE)
                .profiles("zookeeper")
                .run("--small-rpc.provider.enabled=false");
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
                RpcServerApplication.class, PropertiesTestConfig.class)
                .web(WebApplicationType.NONE)
                .profiles("redis")
                .run("--small-rpc.provider.enabled=false");
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
