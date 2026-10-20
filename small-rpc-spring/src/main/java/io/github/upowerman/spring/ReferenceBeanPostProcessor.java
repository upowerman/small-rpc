package io.github.upowerman.spring;

import io.github.upowerman.annotation.RpcReference;
import io.github.upowerman.core.cluster.FailoverClusterInvoker;
import io.github.upowerman.core.directory.CachingServiceDirectory;
import io.github.upowerman.core.directory.ServiceDirectory;
import io.github.upowerman.core.directory.StaticServiceDirectory;
import io.github.upowerman.core.filter.FallbackFilter;
import io.github.upowerman.core.filter.Filter;
import io.github.upowerman.core.filter.MetricsFilter;
import io.github.upowerman.core.filter.TraceFilter;
import io.github.upowerman.core.invoker.RemoteInvoker;
import io.github.upowerman.core.loadbalance.LoadBalancer;
import io.github.upowerman.core.proxy.RpcProxyFactory;
import io.github.upowerman.core.registry.Registry;
import io.github.upowerman.core.spi.Spi;
import io.github.upowerman.core.spi.SpiLoader;
import io.github.upowerman.core.transport.Transport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessorAdapter;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * 消费方装配：字段 @RpcReference → 2.0 链路代理。
 * 链路（P1 样例验证过的同一结构）：RpcProxyFactory(TraceFilter)
 *   → FailoverClusterInvoker(retries=解析值, timeout=解析值)
 *   → CachingServiceDirectory(registry) → SPI 选 LoadBalancer
 *   → RemoteInvoker(transport, iface)。
 * 超时/重试解析（A1）：注解显式值 &gt; 全局默认值（small-rpc.consumer.timeout-ms / retries）。
 * 逻辑自 1.x RpcSpringInvokerFactory.postProcessAfterInstantiation 原样搬运，只换链路内核。
 */
public class ReferenceBeanPostProcessor extends InstantiationAwareBeanPostProcessorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(ReferenceBeanPostProcessor.class);

    /** 框架兜底默认超时（毫秒）：全局未配置或非法时的最终缺省 */
    static final long FALLBACK_TIMEOUT_MILLIS = 3000L;

    /** 框架兜底默认重试次数 */
    static final int FALLBACK_RETRIES = 1;

    /**
     * 外层 get 兜底相对 ClusterInvoker 预算的余量（毫秒）。预算含全部重试尝试，理论上内层
     * 必先结算；但内外计时起点存在毫秒级先后，同值会让外层抢跑几毫秒把成功误报成超时——
     * 外层只是防悬挂兜底，必须严格大于内层预算。
     */
    static final long OUTER_GET_MARGIN_MILLIS = 1000L;

    private final Transport transport;
    private final Registry registry;
    /** small-rpc.loadbalance 的值：注解未指定负载均衡时的缺省扩展名，空 = SPI 默认 */
    private final String defaultLoadBalance;
    /** small-rpc.consumer.timeout-ms：注解未指定超时时的缺省 */
    private final long defaultTimeoutMillis;
    /** small-rpc.consumer.retries：注解未指定重试时的缺省 */
    private final int defaultRetries;
    /** fallback 解析用的容器（可为 null：无容器环境/单测直连，fallback 走反射实例化） */
    private final BeanFactory beanFactory;

    public ReferenceBeanPostProcessor(Transport transport, Registry registry,
                                      String defaultLoadBalance) {
        this(transport, registry, defaultLoadBalance, FALLBACK_TIMEOUT_MILLIS, FALLBACK_RETRIES, null);
    }

    public ReferenceBeanPostProcessor(Transport transport, Registry registry,
                                      String defaultLoadBalance, long defaultTimeoutMillis,
                                      int defaultRetries) {
        this(transport, registry, defaultLoadBalance, defaultTimeoutMillis, defaultRetries, null);
    }

    public ReferenceBeanPostProcessor(Transport transport, Registry registry,
                                      String defaultLoadBalance, long defaultTimeoutMillis,
                                      int defaultRetries, BeanFactory beanFactory) {
        this.transport = transport;
        this.registry = registry;
        this.defaultLoadBalance = defaultLoadBalance;
        this.defaultTimeoutMillis = defaultTimeoutMillis;
        this.defaultRetries = defaultRetries;
        this.beanFactory = beanFactory;
    }

    @Override
    public boolean postProcessAfterInstantiation(final Object bean, final String beanName) throws BeansException {
        ReflectionUtils.doWithFields(bean.getClass(), new ReflectionUtils.FieldCallback() {
            @Override
            public void doWith(Field field) throws IllegalArgumentException, IllegalAccessException {
                RpcReference reference = field.getAnnotation(RpcReference.class);
                if (reference == null) {
                    return;
                }
                Class<?> iface = field.getType();
                if (!iface.isInterface()) {
                    throw new IllegalStateException("@RpcReference 字段必须是接口: " + field);
                }
                Object proxy = buildProxy(iface, reference);
                field.setAccessible(true);
                field.set(bean, proxy);
                logger.info("rpc2 reference injected: {} -> {}", field, iface.getName());
            }
        });
        return true;
    }

    /** 测试直达入口（不走 Spring 生命周期） */
    void injectReferences(Object bean) throws IllegalAccessException {
        postProcessAfterInstantiation(bean, bean.getClass().getName());
    }

    private Object buildProxy(Class<?> iface, RpcReference reference) {
        ServiceDirectory directory = resolveDirectory(registry, reference.address());
        directory.subscribe(iface.getName());
        LoadBalancer loadBalancer = SpiLoader.of(LoadBalancer.class)
                .getExtension(resolveLoadBalanceName(reference.loadBalance(), defaultLoadBalance));
        RemoteInvoker remoteInvoker = new RemoteInvoker(transport, iface);
        long timeoutMillis = resolveTimeout(reference.timeout(), defaultTimeoutMillis);
        int retries = resolveRetries(reference.retries(), defaultRetries);
        FailoverClusterInvoker cluster = new FailoverClusterInvoker(
                directory, loadBalancer, remoteInvoker, retries, timeoutMillis);
        // 链序：Trace（traceId 先落 attachments）→ Metrics（计时含 Cluster 重试全程）
        // → Fallback（包住全部下游失败，治理拒绝也由它兜底）
        List<Filter> filters = new ArrayList<Filter>();
        filters.add(new TraceFilter());
        filters.add(new MetricsFilter());
        Object fallbackInstance = resolveFallback(iface, reference.fallback(), beanFactory);
        if (fallbackInstance != null) {
            filters.add(new FallbackFilter(fallbackInstance));
        }
        return new RpcProxyFactory(
                iface, filters, cluster,
                timeoutMillis + OUTER_GET_MARGIN_MILLIS).getProxy();
    }

    /**
     * fallback 解析：容器 bean 优先，缺省反射实例化；类型不匹配/无法实例化大声失败。
     * 返回 null = 注解未设置（void.class 哨兵），不装配 FallbackFilter。
     */
    static Object resolveFallback(Class<?> iface, Class<?> fallbackClass, BeanFactory beanFactory) {
        if (fallbackClass == void.class) {
            return null;
        }
        if (!iface.isAssignableFrom(fallbackClass)) {
            throw new IllegalStateException("fallback 类 " + fallbackClass.getName()
                    + " 未实现业务接口 " + iface.getName());
        }
        if (beanFactory != null) {
            try {
                return beanFactory.getBean(fallbackClass);
            } catch (org.springframework.beans.factory.NoSuchBeanDefinitionException notInContainer) {
                // 容器无此类型 → 反射实例化
            }
        }
        try {
            return fallbackClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("fallback 类无法实例化（需 public 无参构造或注册为 Spring bean）: "
                    + fallbackClass.getName(), e);
        }
    }

    /**
     * 目录解析：{@code @RpcReference(address)} 非空 → 直连（不查注册中心）；空 → CachingServiceDirectory。
     */
    static ServiceDirectory resolveDirectory(Registry registry, String address) {
        if (isNotBlank(address)) {
            return new StaticServiceDirectory(address);
        }
        return new CachingServiceDirectory(registry);
    }

    /**
     * 负载均衡扩展名三态解析：注解值 &gt; {@code small-rpc.loadbalance} &gt; 接口 {@code @Spi} 默认名。
     * 非法名由 {@link SpiLoader#getExtension(String)} 大声失败（消息含该名与支持的扩展列表）。
     */
    static String resolveLoadBalanceName(String annotationValue, String defaultValue) {
        if (isNotBlank(annotationValue)) {
            return annotationValue.trim();
        }
        if (isNotBlank(defaultValue)) {
            return defaultValue.trim();
        }
        // 兜底：先让 SpiLoader 解析一次默认扩展（缺省配置缺失时在此大声失败），再取其登记名
        SpiLoader.of(LoadBalancer.class).getDefaultExtension();
        return LoadBalancer.class.getAnnotation(Spi.class).value();
    }

    /**
     * 超时解析：注解显式值（&gt;0）&gt; 全局配置；两者都未设置才回退框架默认。
     * 全局配置出现非正值（&lt;=0）视为配置错误——静默换成默认超时会掩盖 typo，
     * 与"非法大声失败"的装配原则一致。
     */
    static long resolveTimeout(long annotationValue, long globalValue) {
        if (annotationValue > 0) {
            return annotationValue;
        }
        if (globalValue > 0) {
            return globalValue;
        }
        throw new IllegalArgumentException(
                "small-rpc.consumer.timeout-ms 非法: " + globalValue + "（必须为正毫秒数）");
    }

    /** 重试解析：注解显式值（&gt;=0，0 = 不重试）&gt; 全局配置；全局出现负值大声失败 */
    static int resolveRetries(int annotationValue, int globalValue) {
        if (annotationValue >= 0) {
            return annotationValue;
        }
        if (globalValue >= 0) {
            return globalValue;
        }
        throw new IllegalArgumentException(
                "small-rpc.consumer.retries 非法: " + globalValue + "（不能为负）");
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
