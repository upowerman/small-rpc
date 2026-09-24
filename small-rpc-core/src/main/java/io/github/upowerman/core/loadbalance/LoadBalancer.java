package io.github.upowerman.core.loadbalance;

import io.github.upowerman.core.directory.ServiceInstance;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.spi.Spi;

import java.util.List;

/**
 * 负载均衡：实例列表 + 调用信息 → 选出一个实例
 */
@Spi("random")
public interface LoadBalancer {

    ServiceInstance select(List<ServiceInstance> instances, Invocation invocation);
}
