package io.github.upowerman.spring.boot;

import io.github.upowerman.annotation.RpcService;
import io.github.upowerman.core.directory.ServiceInstance;
import io.github.upowerman.core.registry.Registry;
import io.github.upowerman.core.registry.ServiceListener;
import io.github.upowerman.core.server.RpcServer;
import org.junit.Assert;
import org.junit.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;

public class ProviderRegistrationTest {

    public interface DemoService {
        String test(String msg);
    }

    @RpcService
    public static class DemoServiceImpl implements DemoService {
        @Override
        public String test(String msg) {
            return "demo:" + msg;
        }
    }

    @Configuration
    @EnableAutoConfiguration
    static class TestApp {
        @Bean
        public DemoServiceImpl demoService() {
            return new DemoServiceImpl();
        }
    }

    @Test
    public void testProviderRegistersWithRegistryOnStartup() throws Exception {
        int port = freePort();
        ConfigurableApplicationContext context = new SpringApplicationBuilder(TestApp.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "small-rpc.provider.enabled=true",
                        "small-rpc.provider.rpc2-port=" + port,
                        "small-rpc.provider.address=127.0.0.1:" + port,
                        "small-rpc.consumer.enabled=false",
                        "small-rpc.registry.type=local")
                .run();
        try {
            Assert.assertNotNull(context.getBean(RpcServer.class));
            Registry registry = context.getBean(Registry.class);
            Assert.assertNotNull(registry);

            final List<List<ServiceInstance>> received = new ArrayList<List<ServiceInstance>>();
            registry.subscribe(DemoService.class.getName(), new ServiceListener() {
                @Override
                public void onChange(List<ServiceInstance> instances) {
                    received.add(instances);
                }
            });

            Assert.assertFalse("未在 Registry 中找到订阅快照", received.isEmpty());
            List<ServiceInstance> snapshot = received.get(0);
            Assert.assertFalse("Registry 中服务未注册任何实例", snapshot.isEmpty());
            boolean found = false;
            for (ServiceInstance inst : snapshot) {
                if (("127.0.0.1:" + port).equals(inst.getAddress())) {
                    found = true;
                    break;
                }
            }
            Assert.assertTrue("Registry 中未注册当前 provider 地址: 127.0.0.1:" + port, found);
        } finally {
            context.close();
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
}
