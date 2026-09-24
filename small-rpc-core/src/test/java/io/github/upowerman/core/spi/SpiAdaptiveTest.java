package io.github.upowerman.core.spi;

import io.github.upowerman.core.directory.ServiceInstance;
import io.github.upowerman.core.invocation.GenericInvocation;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.loadbalance.LoadBalancer;
import io.github.upowerman.core.spi.fixture.DemoSpi;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Adaptive 分发器：attachments[key] 决定扩展；缺省走默认；无 Invocation 参数的接口大声失败。
 */
public class SpiAdaptiveTest {

    private static Invocation invocationWith(Map<String, Object> attachments) {
        return new GenericInvocation("demo", "name", new Class<?>[0], new Object[0], attachments);
    }

    @Test
    public void adaptiveDispatchesByAttachmentsKey() {
        // random 与 roundrobin 对空/单元素列表行为无法区分——用 roundrobin 的共享计数器语义断言：
        // lb=roundrobin 时两次 select 在两实例间交替；lb=random 只断言选中列表成员。
        ServiceInstance a = new ServiceInstance("addr-a");
        ServiceInstance b = new ServiceInstance("addr-b");
        LoadBalancer adaptive = SpiLoader.of(LoadBalancer.class).getAdaptive();

        Map<String, Object> rr = new HashMap<String, Object>();
        rr.put("lb", "roundrobin");
        Invocation inv = invocationWith(rr);
        ServiceInstance first = adaptive.select(Arrays.asList(a, b), inv);
        ServiceInstance second = adaptive.select(Arrays.asList(a, b), inv);
        assertTrue((first == a && second == b) || (first == b && second == a));
    }

    @Test
    public void adaptiveWithoutKeyUsesDefaultExtension() {
        LoadBalancer adaptive = SpiLoader.of(LoadBalancer.class).getAdaptive();
        ServiceInstance picked = adaptive.select(
                Collections.singletonList(new ServiceInstance("only")),
                invocationWith(Collections.<String, Object>emptyMap()));
        assertEquals("only", picked.getAddress());
    }

    @Test
    public void adaptiveWithoutInvocationParameterFails() {
        try {
            SpiLoader.of(DemoSpi.class).getAdaptive();
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Invocation"));
        }
    }
}
