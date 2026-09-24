package io.github.upowerman.core.loadbalance;

import io.github.upowerman.core.directory.ServiceInstance;
import io.github.upowerman.core.invocation.Invocation;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 随机负载均衡
 */
public class RandomLoadBalancer implements LoadBalancer {

    @Override
    public ServiceInstance select(List<ServiceInstance> instances, Invocation invocation) {
        if (instances == null || instances.isEmpty()) {
            throw new IllegalArgumentException("instances is empty");
        }
        return instances.get(ThreadLocalRandom.current().nextInt(instances.size()));
    }
}
