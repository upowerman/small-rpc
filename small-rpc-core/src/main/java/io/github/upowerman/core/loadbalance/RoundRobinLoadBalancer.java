package io.github.upowerman.core.loadbalance;

import io.github.upowerman.core.directory.ServiceInstance;
import io.github.upowerman.core.invocation.Invocation;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 轮询负载均衡
 */
public class RoundRobinLoadBalancer implements LoadBalancer {

    private final AtomicInteger sequence = new AtomicInteger(0);

    @Override
    public ServiceInstance select(List<ServiceInstance> instances, Invocation invocation) {
        if (instances == null || instances.isEmpty()) {
            throw new IllegalArgumentException("instances is empty");
        }
        int index = Math.abs(sequence.getAndIncrement() % instances.size());
        return instances.get(index);
    }
}
