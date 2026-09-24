package io.github.upowerman.spring.boot;

import io.github.upowerman.annotation.RpcReference;
import io.github.upowerman.annotation.RpcService;
import io.github.upowerman.core.registry.BaseServiceRegistry;
import io.github.upowerman.core.serialize.SerializerRegistry;
import io.github.upowerman.core.server.RpcServer;
import io.github.upowerman.core.spi.SpiLoader;
import org.junit.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * starter 自动装配的上下文级测试：真实起 Spring Boot 上下文（非 web、临时端口），
 * 同一上下文内开 provider 与 consumer，端到端自调走通。
 * 把 small-rpc.* 配置驱动的装配行为钉成回归（此前该层零自动化测试）：
 * D-1（small-rpc.loadbalance 生效）、D-3（@RpcService 接口解析）、装配生命周期。
 */
public class Rpc2AutoConfigurationTest {

    public interface EchoService {
        String echo(String msg);
    }

    @RpcService
    public static class EchoServiceImpl implements EchoService {
        @Override
        public String echo(String msg) {
            return "echo:" + msg;
        }
    }

    /** 手写子类：getInterfaces() 不含父类实现的接口（D-3 评审实测会炸启动的形态） */
    public static class EchoServiceImplChild extends EchoServiceImpl {
    }

    /** @RpcReference 字段载体 bean：验证 BPP 注入 + 经注册中心直连地址调用 */
    public static class EchoServiceHolder {
        @RpcReference(timeout = 5000)
        private EchoService echoService;

        public EchoService get() {
            return echoService;
        }
    }

    @Configuration
    @EnableAutoConfiguration
    static class TestApp {

        @Bean
        public EchoServiceImpl echoService() {
            return new EchoServiceImpl();
        }

        @Bean
        public EchoServiceImplChild echoServiceChild() {
            return new EchoServiceImplChild();
        }

        @Bean
        public EchoServiceHolder echoHolder() {
            return new EchoServiceHolder();
        }
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

    @Test
    public void providerAndConsumerAssembleAndCallThroughInOneContext() throws Exception {
        int port = freePort();
        ConfigurableApplicationContext context = new SpringApplicationBuilder(TestApp.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "small-rpc.provider.rpc2-port=" + port,
                        "small-rpc.consumer.enabled=true",
                        "small-rpc.registry.type=local",
                        "small-rpc.registry.param[DIRECT_ADDRESS]=localhost:" + port,
                        "small-rpc.loadbalance=random")
                .run();
        try {
            // context 启动成功 + RpcServer bean 存在 + 端口可连
            assertNotNull(context.getBean(RpcServer.class));
            assertPortConnectable(port);

            // @RpcReference 注入字段非 null；子类 bean 解析出父类业务接口（D-3）；
            // 经 local 注册中心 + 配置的负载均衡扩展自调（D-1 配置路径）
            EchoServiceHolder holder = context.getBean(EchoServiceHolder.class);
            assertNotNull("@RpcReference 字段应被注入代理", holder.get());
            assertEquals("echo:world", holder.get().echo("world"));
        } finally {
            context.close();
        }
        // close 后端口释放
        assertPortReleased(port);
    }

    @Test
    public void configuredLoadBalanceFlowsIntoSpiLookup() throws Exception {
        // 非法负载均衡名必须在装配期大声失败——证明 small-rpc.loadbalance 真实进入 SPI 查找
        int port = freePort();
        try {
            new SpringApplicationBuilder(TestApp.class)
                    .web(WebApplicationType.NONE)
                    .properties(
                            "small-rpc.provider.rpc2-port=" + port,
                            "small-rpc.registry.type=local",
                            "small-rpc.loadbalance=nope")
                    .run();
            fail("expected context startup failure");
        } catch (RuntimeException e) {
            assertTrue("cause chain 应包含非法扩展名 nope，实际: " + allCauses(e),
                    allCauses(e).contains("nope"));
        }
    }

    // ---- 帮助方法 ----

    private static int freePort() throws IOException {
        ServerSocket socket = new ServerSocket(0);
        try {
            return socket.getLocalPort();
        } finally {
            socket.close();
        }
    }

    private static void assertPortConnectable(int port) throws IOException {
        Socket socket = new Socket("localhost", port);
        try {
            assertTrue(socket.isConnected());
        } finally {
            socket.close();
        }
    }

    private static void assertPortReleased(int port) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        IOException last = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                new ServerSocket(port).close();
                return;
            } catch (IOException e) {
                last = e;
                Thread.sleep(100);
            }
        }
        throw last;
    }

    private static String allCauses(Throwable error) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = error; t != null && t != t.getCause(); t = t.getCause()) {
            sb.append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
        }
        return sb.toString();
    }
}
