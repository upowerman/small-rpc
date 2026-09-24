package io.github.upowerman.spring.boot;

import io.github.upowerman.annotation.RpcService;
import io.github.upowerman.core.provider.ReflectiveInvoker;
import io.github.upowerman.core.serialize.SerializerRegistry;
import io.github.upowerman.core.server.RpcServer;
import io.github.upowerman.core.directory.ServiceInstance;
import io.github.upowerman.core.registry.Registry;
import io.github.upowerman.core.spi.SpiLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ClassUtils;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 提供方自动装配：收集 @RpcService bean → ReflectiveInvoker 注册 → 起 RpcServer。
 * 接口解析：CGLIB 子类解包 → 全部接口（含继承）→ 去掉 Spring 代理基础设施接口 →
 * 必须恰好一个业务接口，否则大声失败（原 getInterfaces()[0] 口径在 JDK 代理/子类/多接口
 * 场景会静默注册错接口或炸启动）。
 * 开关：small-rpc.provider.enabled（默认 true）——consumer 侧应用必须显式关掉，
 * 否则会跟着起一个空 provider 去抢端口（Ruling 6）。
 */
@Configuration
@ConditionalOnClass(RpcServer.class)
@ConditionalOnProperty(prefix = "small-rpc.provider", name = "enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnMissingBean(RpcServer.class)
@EnableConfigurationProperties(Rpc2Properties.class)
public class Rpc2ProviderAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(Rpc2ProviderAutoConfiguration.class);

    /**
     * 非业务接口，按 FQCN 精确匹配（不依赖编译期 spring-aop 类型）。
     * <ul>
     *   <li>Spring AOP 代理固定附加的三个：{@code SpringProxy} / {@code Advised} / {@code DecoratingProxy}；
     *       {@link ClassUtils#getAllInterfacesForClass(Class)} 会带出继承来的接口，
     *       {@code Advised} 继承 {@code TargetClassAware}，故它也要列；</li>
     *   <li>{@code java.io.Serializable}：JDK 代理类继承 {@code java.lang.reflect.Proxy}，
     *       而 {@code Proxy} 实现 {@code Serializable}，故它必然出现在接口集合里；
     *       业务接口继承 {@code Serializable} 时它也只是标记接口，不应参与「业务接口唯一性」判断。</li>
     * </ul>
     */
    private static final Set<String> NON_BUSINESS_INTERFACES = new HashSet<String>(Arrays.asList(
            "org.springframework.aop.SpringProxy",
            "org.springframework.aop.framework.Advised",
            "org.springframework.aop.TargetClassAware",
            "org.springframework.core.DecoratingProxy",
            "java.io.Serializable"));

    @Bean
    @ConditionalOnMissingBean(SerializerRegistry.class)
    public SerializerRegistry serializerRegistry() {
        return SerializerRegistry.fromSpi();
    }

    @Bean
    @ConditionalOnMissingBean(Registry.class)
    public Registry rpc2Registry(Rpc2Properties properties) {
        Registry registry = SpiLoader.of(Registry.class)
                .getExtension(properties.getRegistry().getType());
        registry.init(properties.getRegistry().getParam());
        return registry;
    }

    @Bean
    public ProviderRegistrationLifecycle providerRegistrationLifecycle(Registry registry) {
        return new ProviderRegistrationLifecycle(registry);
    }

    @Bean(destroyMethod = "shutdown")
    public RpcServer rpc2Server(ApplicationContext applicationContext, Rpc2Properties properties,
                                SerializerRegistry serializerRegistry, Registry registry,
                                ProviderRegistrationLifecycle lifecycle) throws Exception {
        int rpc2Port = properties.getProvider().getRpc2Port();
        RpcServer server = new RpcServer(rpc2Port, serializerRegistry);
        List<Class<?>> registeredInterfaces = new ArrayList<Class<?>>();
        for (Object serviceBean : applicationContext.getBeansWithAnnotation(RpcService.class).values()) {
            Class<?> serviceInterface = resolveServiceInterface(serviceBean);
            server.register(serviceInterface.getName(), new ReflectiveInvoker(serviceInterface, serviceBean));
            registeredInterfaces.add(serviceInterface);
        }
        if (registeredInterfaces.isEmpty()) {
            logger.warn("rpc2 provider 未注册任何服务：没有扫描到 @RpcService bean；"
                    + "如非本意请设 small-rpc.provider.enabled=false 关闭 provider 侧装配");
        }
        server.start();

        String selfAddress = resolveProviderAddress(properties.getProvider().getAddress(), rpc2Port);
        ServiceInstance instance = new ServiceInstance(selfAddress);
        for (Class<?> iface : registeredInterfaces) {
            registry.register(iface.getName(), instance);
            lifecycle.record(iface.getName(), instance);
        }

        logger.info("rpc2 provider started on port {} (address='{}') with {} services registered to Registry",
                rpc2Port, selfAddress, registeredInterfaces.size());
        return server;
    }

    private static String resolveProviderAddress(String configuredAddress, int port) {
        if (configuredAddress != null && !configuredAddress.trim().isEmpty()) {
            return configuredAddress.trim();
        }
        try {
            return InetAddress.getLocalHost().getHostAddress() + ":" + port;
        } catch (UnknownHostException e) {
            logger.warn("Failed to get local host address, defaulting to 127.0.0.1: {}", e.getMessage());
            return "127.0.0.1:" + port;
        }
    }

    /**
     * 解析 @RpcService bean 要暴露的业务接口。
     * 无业务接口 / 多业务接口都大声失败——静默注册错接口（或静默零注册）比启动失败更难排查。
     */
    static Class<?> resolveServiceInterface(Object bean) {
        Class<?> userClass = ClassUtils.getUserClass(bean);
        List<Class<?>> candidates = new ArrayList<Class<?>>();
        for (Class<?> iface : ClassUtils.getAllInterfacesForClass(userClass)) {
            if (!NON_BUSINESS_INTERFACES.contains(iface.getName())) {
                candidates.add(iface);
            }
        }
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        if (candidates.isEmpty()) {
            throw new IllegalStateException("@RpcService 未找到业务接口: " + userClass.getName()
                    + "（实现类必须实现一个业务接口）");
        }
        throw new IllegalStateException("@RpcService 实现类只能实现一个业务接口: " + userClass.getName()
                + "，候选接口: " + candidateNames(candidates));
    }

    private static String candidateNames(List<Class<?>> candidates) {
        StringBuilder sb = new StringBuilder();
        for (Class<?> candidate : candidates) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(candidate.getName());
        }
        return sb.toString();
    }

    static class ProviderRegistrationLifecycle implements org.springframework.beans.factory.DisposableBean {
        private static final Logger lifecycleLogger = LoggerFactory.getLogger(ProviderRegistrationLifecycle.class);

        private static class RegistrationEntry {
            final String service;
            final ServiceInstance instance;

            RegistrationEntry(String service, ServiceInstance instance) {
                this.service = service;
                this.instance = instance;
            }
        }

        private final Registry registry;
        private final List<RegistrationEntry> entries = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final Thread shutdownHook;

        public ProviderRegistrationLifecycle(final Registry registry) {
            this.registry = registry;
            this.shutdownHook = new Thread(new Runnable() {
                @Override
                public void run() {
                    ProviderRegistrationLifecycle.this.unregisterAll();
                }
            }, "small-rpc-provider-unregister-hook");
            try {
                Runtime.getRuntime().addShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM already shutting down
            }
        }

        public void record(String service, ServiceInstance instance) {
            entries.add(new RegistrationEntry(service, instance));
        }

        private void unregisterAll() {
            for (RegistrationEntry entry : entries) {
                try {
                    registry.unregister(entry.service, entry.instance);
                    lifecycleLogger.info("Unregistered provider instance '{}' for service '{}'",
                            entry.instance.getAddress(), entry.service);
                } catch (Throwable t) {
                    lifecycleLogger.warn("Failed to unregister provider service '{}': {}", entry.service, t.getMessage());
                }
            }
        }

        @Override
        public void destroy() {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (Throwable ignored) {
            }
            unregisterAll();
        }
    }
}
