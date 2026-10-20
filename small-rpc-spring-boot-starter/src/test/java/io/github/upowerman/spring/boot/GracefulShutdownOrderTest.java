package io.github.upowerman.spring.boot;

import io.github.upowerman.annotation.RpcService;
import io.github.upowerman.core.directory.ServiceInstance;
import io.github.upowerman.core.registry.Registry;
import io.github.upowerman.core.registry.ServiceListener;
import io.github.upowerman.core.server.RpcServer;
import org.junit.Test;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertTrue;

/**
 * B5 停机顺序：SmartLifecycle 的 unregister 必须先于任何单例 destroy（含 RpcServer 关闭）——
 * "先摘流量，再关服务"。观测手段：自定义 Registry 记录注销时间戳；晚于 RpcServer 创建的
 * 探针 bean（销毁先于 RpcServer）记录销毁时间戳；断言 注销时间 < 探针销毁时间。
 */
public class GracefulShutdownOrderTest {

    public interface OrderService {
        String ping();
    }

    @RpcService
    public static class OrderServiceImpl implements OrderService {
        @Override
        public String ping() {
            return "pong";
        }
    }

    static final AtomicLong UNREGISTER_AT = new AtomicLong(-1);
    static final AtomicLong PROBE_DESTROYED_AT = new AtomicLong(-1);

    static class RecordingRegistry implements Registry {
        @Override
        public void init(Map<String, String> param) {
        }

        @Override
        public void destroy() {
        }

        @Override
        public void register(String service, ServiceInstance instance) {
        }

        @Override
        public void unregister(String service, ServiceInstance instance) {
            UNREGISTER_AT.compareAndSet(-1, System.nanoTime());
        }

        @Override
        public void subscribe(String service, ServiceListener listener) {
        }

        @Override
        public void unsubscribe(String service, ServiceListener listener) {
        }
    }

    /** 依赖 RpcServer（创建晚于 server → 销毁早于 server）：探针销毁时 server 尚未关 */
    static class DestroyOrderProbe implements DisposableBean {
        @Override
        public void destroy() {
            PROBE_DESTROYED_AT.set(System.nanoTime());
        }
    }

    @Configuration
    @EnableAutoConfiguration
    static class TestApp {

        @Bean
        public Registry registry() {
            return new RecordingRegistry();
        }

        @Bean
        public OrderServiceImpl orderService() {
            return new OrderServiceImpl();
        }

        @Bean
        public DestroyOrderProbe destroyOrderProbe(RpcServer rpcServer) {
            return new DestroyOrderProbe();
        }
    }

    @Test
    public void unregisterHappensBeforeServerDestruction() throws Exception {
        int port = freePort();
        ConfigurableApplicationContext context = new SpringApplicationBuilder(TestApp.class)
                .web(WebApplicationType.NONE)
                .properties(
                        "small-rpc.provider.rpc2-port=" + port,
                        "small-rpc.consumer.enabled=false",
                        "small-rpc.registry.type=local")
                .run();
        try {
            assertTrue("上下文应成功启动并注册探针", context.containsBean("destroyOrderProbe"));
        } finally {
            context.close();
        }
        assertTrue("注销未发生：SmartLifecycle.stop 应在单例销毁前 unregister", UNREGISTER_AT.get() > 0);
        assertTrue("探针未销毁", PROBE_DESTROYED_AT.get() > 0);
        assertTrue("注销必须先于 RpcServer 关闭（先摘流量再关服务），实际注销="
                        + UNREGISTER_AT.get() + " 探针销毁=" + PROBE_DESTROYED_AT.get(),
                UNREGISTER_AT.get() < PROBE_DESTROYED_AT.get());
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
