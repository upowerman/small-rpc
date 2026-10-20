package io.github.upowerman.core.loadbalance;

import io.github.upowerman.core.directory.ServiceInstance;
import io.github.upowerman.core.invocation.GenericInvocation;
import io.github.upowerman.core.spi.SpiLoader;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class WeightedRandomLoadBalancerTest {

    private GenericInvocation invocation() {
        return new GenericInvocation("com.test.EchoService", "echo",
                new Class<?>[0], new Object[0], new HashMap<String, Object>());
    }

    @Test
    public void weightRatioDrivesDistribution() {
        // 9:1 两实例，大样本下选择比例应接近 9:1
        List<ServiceInstance> instances = Arrays.asList(
                new ServiceInstance("127.0.0.1:1", 9, 0L, 0L),
                new ServiceInstance("127.0.0.1:2", 1, 0L, 0L));
        WeightedRandomLoadBalancer lb = new WeightedRandomLoadBalancer();
        AtomicInteger heavy = new AtomicInteger();
        int samples = 10000;
        for (int i = 0; i < samples; i++) {
            if (lb.select(instances, invocation()).getAddress().endsWith(":1")) {
                heavy.incrementAndGet();
            }
        }
        double ratio = heavy.get() / (double) samples;
        assertTrue("权重 9:1 时选择比例应接近 0.9，实际 " + ratio, ratio > 0.85 && ratio < 0.95);
    }

    @Test
    public void warmupFactorRampsLinearlyAndSettlesAtOne() {
        long now = System.currentTimeMillis();
        assertEquals(0D, WeightedRandomLoadBalancer.warmupFactor(
                new ServiceInstance("a:1", 1, now, 60000L)), 0.0001D);
        double half = WeightedRandomLoadBalancer.warmupFactor(
                new ServiceInstance("a:1", 1, now - 30000L, 60000L));
        assertTrue("半程预热因子应≈0.5，实际 " + half, Math.abs(half - 0.5D) < 0.01D);
        assertEquals(1D, WeightedRandomLoadBalancer.warmupFactor(
                new ServiceInstance("a:1", 1, now - 60000L, 60000L)), 0.0001D);
        assertEquals(1D, WeightedRandomLoadBalancer.warmupFactor(
                new ServiceInstance("a:1", 1, now - 999999L, 60000L)), 0.0001D);
    }

    @Test
    public void unknownStartTimeOrDisabledWarmupMeansNoPenalty() {
        // startTime=0（旧格式）或 warmup=0：因子恒为 1，不衰减
        assertEquals(1D, WeightedRandomLoadBalancer.warmupFactor(new ServiceInstance("a:1")), 0D);
        assertEquals(1D, WeightedRandomLoadBalancer.warmupFactor(
                new ServiceInstance("a:1", 1, 0L, 60000L)), 0D);
        assertEquals(1D, WeightedRandomLoadBalancer.warmupFactor(
                new ServiceInstance("a:1", 1, System.currentTimeMillis(), 0L)), 0D);
    }

    @Test
    public void allInstancesWarmingUpStillRouteUniformly() {
        // 全部实例有效权重为 0（都在预热起点）→ 均匀随机兜底，路由永远有出路
        long now = System.currentTimeMillis();
        List<ServiceInstance> instances = Arrays.asList(
                new ServiceInstance("127.0.0.1:1", 1, now, 60000L),
                new ServiceInstance("127.0.0.1:2", 1, now, 60000L));
        WeightedRandomLoadBalancer lb = new WeightedRandomLoadBalancer();
        for (int i = 0; i < 100; i++) {
            assertNotNull(lb.select(instances, invocation()));
        }
    }

    @Test
    public void warmingInstanceGetsLessTrafficThanWarmedOne() {
        // 同权两实例，一个已预热、一个刚开始预热：预热实例应显著少接流量
        long now = System.currentTimeMillis();
        List<ServiceInstance> instances = Arrays.asList(
                new ServiceInstance("127.0.0.1:1", 1, now - 60000L, 60000L),
                new ServiceInstance("127.0.0.1:2", 1, now - 6000L, 60000L)); // 因子≈0.1
        WeightedRandomLoadBalancer lb = new WeightedRandomLoadBalancer();
        int warmed = 0;
        int samples = 5000;
        for (int i = 0; i < samples; i++) {
            if (lb.select(instances, invocation()).getAddress().endsWith(":1")) {
                warmed++;
            }
        }
        double ratio = warmed / (double) samples;
        // 有效权重 1 : 0.1 → 期望 ≈0.909
        assertTrue("预热中实例应显著少接流量，实际已预热实例占比 " + ratio, ratio > 0.85 && ratio < 0.96);
    }

    @Test
    public void spiRegistersWeightedRandomExtension() {
        LoadBalancer lb = SpiLoader.of(LoadBalancer.class).getExtension("weightedrandom");
        assertNotNull(lb);
        assertSame(WeightedRandomLoadBalancer.class, lb.getClass());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptyInstances() {
        new WeightedRandomLoadBalancer().select(
                Arrays.<ServiceInstance>asList(), invocation());
    }
}
