package io.github.upowerman.core.governance;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** 假时钟驱动的三态状态机：跳闸/打开/半开探测/恢复/名额不超发 */
public class DefaultCircuitBreakerTest {

    private final AtomicLong now = new AtomicLong(0);

    private DefaultCircuitBreaker breaker() {
        return new DefaultCircuitBreaker(new LongSupplier() {
            @Override public long getAsLong() { return now.get(); }
        });
    }

    /** 错误率 50%、3 个样本起判：3 连败跳闸 */
    private static CircuitBreakerRule rule() {
        return new CircuitBreakerRule(0.5, 0, 0, 3, 10_000, 1000, 1);
    }

    @Test
    public void tripsOnErrorRateAfterMinCalls() {
        DefaultCircuitBreaker cb = breaker();
        cb.onComplete("r", rule(), false, 10, 1000);
        cb.onComplete("r", rule(), false, 10, 1000);
        assertTrue("min-calls 未达不应跳闸", cb.tryPass("r", rule()));
        cb.onComplete("r", rule(), false, 10, 1000);
        assertFalse("3/3 失败应打开", cb.tryPass("r", rule()));
    }

    @Test
    public void coldStartBelowMinCallsDoesNotTrip() {
        DefaultCircuitBreaker cb = breaker();
        cb.onComplete("r", rule(), false, 10, 1000);
        cb.onComplete("r", rule(), false, 10, 1000);
        assertTrue(cb.tryPass("r", rule()));
    }

    @Test
    public void slowCallTripsViaBudgetDerivation() {
        CircuitBreakerRule slowRule = new CircuitBreakerRule(0, 0.5, 0, 2, 10_000, 1000, 1);
        DefaultCircuitBreaker cb = breaker();
        cb.onComplete("r", slowRule, true, 900, 1000);  // 预算1000×0.8=800，900 慢
        cb.onComplete("r", slowRule, true, 900, 1000);
        assertFalse("慢调用率 100% 应跳闸", cb.tryPass("r", slowRule));
    }

    @Test
    public void slowConditionDisabledWhenNoBudgetAndNoExplicitRt() {
        CircuitBreakerRule slowRule = new CircuitBreakerRule(0, 0.5, 0, 2, 10_000, 1000, 1);
        DefaultCircuitBreaker cb = breaker();
        cb.onComplete("r", slowRule, true, 999_999, 0);  // 无预算：慢条件停用
        cb.onComplete("r", slowRule, true, 999_999, 0);
        assertTrue(cb.tryPass("r", slowRule));
    }

    @Test
    public void openThenHalfOpenProbeThenClose() {
        DefaultCircuitBreaker cb = breaker();
        for (int i = 0; i < 3; i++) {
            cb.onComplete("r", rule(), false, 10, 1000);
        }
        assertFalse(cb.tryPass("r", rule()));
        now.set(1000);                                  // open-ms 到期
        assertTrue("到期后放行探测", cb.tryPass("r", rule()));
        cb.onComplete("r", rule(), true, 10, 1000);     // 探测成功
        assertTrue("探测成功应恢复 CLOSED", cb.tryPass("r", rule()));
        cb.onComplete("r", rule(), false, 10, 1000);    // 窗口已重置，1 个样本不跳闸
        assertTrue(cb.tryPass("r", rule()));
    }

    @Test
    public void halfOpenProbeFailureReopens() {
        DefaultCircuitBreaker cb = breaker();
        for (int i = 0; i < 3; i++) {
            cb.onComplete("r", rule(), false, 10, 1000);
        }
        now.set(1000);
        assertTrue(cb.tryPass("r", rule()));
        cb.onComplete("r", rule(), false, 10, 1000);    // 探测失败
        assertFalse("探测失败应回 OPEN 重新计时", cb.tryPass("r", rule()));
        now.set(2000);
        assertTrue(cb.tryPass("r", rule()));
    }

    @Test
    public void halfOpenGrantsAtMostProbes() {
        CircuitBreakerRule twoProbes = new CircuitBreakerRule(0.5, 0, 0, 3, 10_000, 1000, 2);
        DefaultCircuitBreaker cb = breaker();
        for (int i = 0; i < 3; i++) {
            cb.onComplete("r", twoProbes, false, 10, 1000);
        }
        now.set(1000);
        assertTrue(cb.tryPass("r", twoProbes));
        assertTrue(cb.tryPass("r", twoProbes));
        assertFalse("名额占满其余按 OPEN 拒绝", cb.tryPass("r", twoProbes));
    }

    @Test
    public void concurrentHammerKeepsInvariants() throws Exception {
        DefaultCircuitBreaker cb = breaker();
        final CircuitBreakerRule r = rule();
        final int threads = 8, loops = 500;
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicInteger violations = new AtomicInteger();
        for (int t = 0; t < threads; t++) {
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        for (int i = 0; i < loops; i++) {
                            boolean pass = cb.tryPass("r", r);
                            cb.onComplete("r", r, pass && (i % 2 == 0), 10, 1000);
                            // 不变量：tryPass 与 onComplete 混跑后状态机不抛异常、不卡死
                        }
                    } catch (Throwable t) {
                        violations.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                }
            }).start();
        }
        done.await();
        assertEquals(0, violations.get());
    }
}
