package io.github.upowerman.spring.boot;

import org.junit.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** 治理配置绑定：默认关闭、嵌套绑定、非法值经 Rule 构造器大声失败 */
public class GovernanceConfigurationTest {

    private Rpc2Properties bind(Map<String, Object> props) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", props));
        Iterable<ConfigurationPropertySource> sources = ConfigurationPropertySources.get(env);
        Rpc2Properties target = new Rpc2Properties();
        new Binder(sources).bind("small-rpc", Bindable.ofInstance(target));
        return target;
    }

    @Test
    public void defaultsAreAllOff() {
        Rpc2Properties p = new Rpc2Properties();
        assertFalse(p.getConsumer().getGovernance().getCircuitBreaker().isEnabled());
        assertFalse(p.getConsumer().getGovernance().getRateLimit().isEnabled());
        assertEquals(0, p.getConsumer().getGovernance().getRateLimit().getQps());
        assertEquals(0, p.getConsumer().getGovernance().getRateLimit().getMaxConcurrent());
        assertEquals(0, p.getConsumer().getMaxInFlight());
    }

    @Test
    public void nestedBindingWorks() {
        Map<String, Object> props = new HashMap<String, Object>();
        props.put("small-rpc.consumer.governance.circuit-breaker.enabled", "true");
        props.put("small-rpc.consumer.governance.circuit-breaker.min-calls", "5");
        props.put("small-rpc.consumer.governance.circuit-breaker.open-ms", "5000");
        props.put("small-rpc.consumer.governance.rate-limit.qps", "100");
        props.put("small-rpc.consumer.governance.rate-limit.max-concurrent", "8");
        props.put("small-rpc.consumer.max-in-flight", "500");
        Rpc2Properties p = bind(props);
        assertTrue(p.getConsumer().getGovernance().getCircuitBreaker().isEnabled());
        assertEquals(5, p.getConsumer().getGovernance().getCircuitBreaker().getMinCalls());
        assertEquals(5000, p.getConsumer().getGovernance().getCircuitBreaker().getOpenMs());
        assertEquals(100, p.getConsumer().getGovernance().getRateLimit().getQps());
        assertEquals(8, p.getConsumer().getGovernance().getRateLimit().getMaxConcurrent());
        assertEquals(500, p.getConsumer().getMaxInFlight());
    }

    @Test
    public void invalidRuleValuesFailLoudlyAtAssembly() {
        Rpc2Properties.Governance.CircuitBreaker cb =
                new Rpc2Properties.Governance.CircuitBreaker();
        cb.setEnabled(true);
        cb.setOpenMs(0);                                   // 非法
        try {
            new io.github.upowerman.core.governance.CircuitBreakerRule(
                    cb.getErrorRateThreshold(), cb.getSlowCallRateThreshold(), cb.getSlowRtMs(),
                    cb.getMinCalls(), cb.getWindowMs(), cb.getOpenMs(), cb.getHalfOpenProbes());
            fail("open-ms=0 应大声失败");
        } catch (IllegalArgumentException expected) {
            // 装配期失败即启动失败——预期
        }
    }
}
