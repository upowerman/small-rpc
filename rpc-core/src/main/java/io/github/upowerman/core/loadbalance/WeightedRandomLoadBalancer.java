package io.github.upowerman.core.loadbalance;

import io.github.upowerman.core.directory.ServiceInstance;
import io.github.upowerman.core.invocation.Invocation;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 加权随机负载均衡：按有效权重比例分流。
 * <p>
 * 有效权重 = {@code weight × 预热因子}。预热因子：实例携带预热窗口（warmupMillis &gt; 0）时，
 * 启动后窗口内流量从 0 线性爬坡到满权重（新实例少接流量，冷启动缓存/JIT 未热时不被压垮）；
 * 窗口外为 1。startTime 未知（0，旧格式注册数据）或预热关闭时恒为 1，不衰减。
 * <p>
 * 全部实例有效权重之和为 0（例如刚启动、全部处于预热起点）时回退均匀随机——
 * 预热是"少分流量"，不是"不分流量"，路由必须永远有出路。
 */
public class WeightedRandomLoadBalancer implements LoadBalancer {

    @Override
    public ServiceInstance select(List<ServiceInstance> instances, Invocation invocation) {
        if (instances == null || instances.isEmpty()) {
            throw new IllegalArgumentException("instances is empty");
        }
        double totalEffectiveWeight = 0D;
        double[] effectiveWeights = new double[instances.size()];
        for (int i = 0; i < instances.size(); i++) {
            effectiveWeights[i] = effectiveWeight(instances.get(i));
            totalEffectiveWeight += effectiveWeights[i];
        }
        if (totalEffectiveWeight <= 0D) {
            return instances.get(ThreadLocalRandom.current().nextInt(instances.size()));
        }
        double point = ThreadLocalRandom.current().nextDouble(totalEffectiveWeight);
        double cumulative = 0D;
        for (int i = 0; i < effectiveWeights.length; i++) {
            cumulative += effectiveWeights[i];
            if (point < cumulative) {
                return instances.get(i);
            }
        }
        // 浮点累加边界兜底：nextDouble 上界含不到，理论上不可达
        return instances.get(instances.size() - 1);
    }

    /** 有效权重 = 静态权重 × 预热因子；权重为负按 0 处理 */
    static double effectiveWeight(ServiceInstance instance) {
        int weight = Math.max(0, instance.getWeight());
        return weight * warmupFactor(instance);
    }

    /** 预热因子 ∈ [0,1]：窗口内随存活时长线性爬坡；无窗口/未知启动时间 = 1 */
    static double warmupFactor(ServiceInstance instance) {
        long warmupMillis = instance.getWarmupMillis();
        long startTime = instance.getStartTime();
        if (warmupMillis <= 0 || startTime <= 0) {
            return 1D;
        }
        long uptime = System.currentTimeMillis() - startTime;
        if (uptime <= 0) {
            return 0D;
        }
        if (uptime >= warmupMillis) {
            return 1D;
        }
        return (double) uptime / warmupMillis;
    }
}
