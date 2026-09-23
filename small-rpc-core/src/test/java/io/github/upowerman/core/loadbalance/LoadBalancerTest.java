package io.github.upowerman.core.loadbalance;

import io.github.upowerman.core.directory.ServiceInstance;
import io.github.upowerman.core.invocation.GenericInvocation;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class LoadBalancerTest {

    private final List<ServiceInstance> instances = Arrays.asList(
            new ServiceInstance("127.0.0.1:1"),
            new ServiceInstance("127.0.0.1:2"),
            new ServiceInstance("127.0.0.1:3"));

    private GenericInvocation invocation() {
        return new GenericInvocation("com.test.EchoService", "echo",
                new Class<?>[0], new Object[0], new HashMap<String, Object>());
    }

    @Test
    public void randomSelectsExistingInstance() {
        RandomLoadBalancer lb = new RandomLoadBalancer();
        for (int i = 0; i < 20; i++) {
            ServiceInstance selected = lb.select(instances, invocation());
            assertTrue(instances.contains(selected));
        }
    }

    @Test
    public void roundRobinCyclesInOrder() {
        RoundRobinLoadBalancer lb = new RoundRobinLoadBalancer();
        assertSame(instances.get(0), lb.select(instances, invocation()));
        assertSame(instances.get(1), lb.select(instances, invocation()));
        assertSame(instances.get(2), lb.select(instances, invocation()));
        assertSame(instances.get(0), lb.select(instances, invocation()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptyInstances() {
        new RandomLoadBalancer().select(new ArrayList<ServiceInstance>(), invocation());
    }
}
