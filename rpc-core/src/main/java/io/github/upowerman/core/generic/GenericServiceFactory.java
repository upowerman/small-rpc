package io.github.upowerman.core.generic;

import io.github.upowerman.core.cluster.FailoverClusterInvoker;
import io.github.upowerman.core.directory.CachingServiceDirectory;
import io.github.upowerman.core.directory.ServiceDirectory;
import io.github.upowerman.core.filter.Filter;
import io.github.upowerman.core.filter.FilterChain;
import io.github.upowerman.core.filter.MetricsFilter;
import io.github.upowerman.core.filter.TraceFilter;
import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.invoker.RemoteInvoker;
import io.github.upowerman.core.loadbalance.LoadBalancer;
import io.github.upowerman.core.registry.Registry;
import io.github.upowerman.core.spi.SpiLoader;
import io.github.upowerman.core.transport.Transport;

import java.util.Arrays;

/**
 * 泛化调用装配工厂（C7）：组装与 {@code @RpcReference} 完全一致的消费端链路——
 * TraceFilter → FailoverClusterInvoker → CachingServiceDirectory（懒订阅，按调用到的
 * 服务名建立）→ SPI LoadBalancer → RemoteInvoker（无接口 Class）。
 */
public final class GenericServiceFactory {

    private GenericServiceFactory() {
    }

    /** 缺省参数版：重试 1 次、超时 3000ms、SPI 默认负载均衡 */
    public static GenericService create(Transport transport, Registry registry) {
        return create(transport, registry, "", 1, 3000L);
    }

    /**
     * @param loadBalanceName 负载均衡 SPI 名；空 = 接口 {@code @Spi} 默认扩展
     * @param retries         失败重试次数
     * @param timeoutMillis   单次调用超时（deadline 预算）
     */
    public static GenericService create(Transport transport, Registry registry,
                                        String loadBalanceName, int retries, long timeoutMillis) {
        ServiceDirectory directory = new CachingServiceDirectory(registry);
        LoadBalancer loadBalancer = (loadBalanceName == null || loadBalanceName.trim().isEmpty())
                ? SpiLoader.of(LoadBalancer.class).getDefaultExtension()
                : SpiLoader.of(LoadBalancer.class).getExtension(loadBalanceName.trim());
        FailoverClusterInvoker cluster = new FailoverClusterInvoker(
                directory, loadBalancer, new RemoteInvoker(transport, null), retries, timeoutMillis);
        Invoker chainHead = FilterChain.build(
                Arrays.<Filter>asList(new TraceFilter(), new MetricsFilter()), cluster);
        return new DefaultGenericService(directory, chainHead, timeoutMillis);
    }
}
