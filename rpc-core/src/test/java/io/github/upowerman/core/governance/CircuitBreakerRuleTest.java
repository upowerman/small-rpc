package io.github.upowerman.core.governance;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/** Rule 参数校验：非法大声失败（2.x 装配原则） */
public class CircuitBreakerRuleTest {

    @Test
    public void defaultsReadable() {
        CircuitBreakerRule r = new CircuitBreakerRule(0.5, 0.5, 0, 20, 10_000, 10_000, 1);
        assertEquals(0.5, r.errorRateThreshold(), 0.0);
        assertEquals(0.5, r.slowCallRateThreshold(), 0.0);
        assertEquals(0, r.slowRtMillis());
        assertEquals(20, r.minCalls());
        assertEquals(10_000, r.windowMillis());
        assertEquals(10_000, r.openMillis());
        assertEquals(1, r.halfOpenProbes());
    }

    @Test
    public void zeroThresholdMeansDisabledCondition() {
        new CircuitBreakerRule(0, 0, 0, 0, 10_000, 10_000, 1); // 全部停用合法
    }

    @Test
    public void illegalValuesFailLoudly() {
        assertThrows("threshold > 1", () -> new CircuitBreakerRule(1.5, 0, 0, 0, 1000, 1000, 1));
        assertThrows("negative threshold", () -> new CircuitBreakerRule(0, -0.1, 0, 0, 1000, 1000, 1));
        assertThrows("negative slow-rt", () -> new CircuitBreakerRule(0, 0, -1, 0, 1000, 1000, 1));
        assertThrows("negative min-calls", () -> new CircuitBreakerRule(0, 0, 0, -1, 1000, 1000, 1));
        assertThrows("zero window", () -> new CircuitBreakerRule(0, 0, 0, 0, 0, 1000, 1));
        assertThrows("zero open-ms", () -> new CircuitBreakerRule(0, 0, 0, 0, 1000, 0, 1));
        assertThrows("zero probes", () -> new CircuitBreakerRule(0, 0, 0, 0, 1000, 1000, 0));
    }

    private static void assertThrows(String msg, Runnable run) {
        try {
            run.run();
            fail(msg + " should have thrown");
        } catch (IllegalArgumentException expected) {
            // 大声失败即预期
        }
    }
}
