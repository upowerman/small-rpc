package io.github.upowerman.core.governance;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** 令牌桶：容量=qps 允许 1 秒突发；按速率恢复；资源间隔离；qps 校验大声失败 */
public class TokenBucketRateLimiterTest {

    private final AtomicLong now = new AtomicLong(0);
    private final LongSupplier clock = new LongSupplier() {
        @Override public long getAsLong() { return now.get(); }
    };

    @Test
    public void burstThenRefill() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(clock);
        RateLimitRule rule = new RateLimitRule(2);      // 容量 2，速率 2/s
        assertTrue(limiter.tryAcquire("r", rule));
        assertTrue(limiter.tryAcquire("r", rule));
        assertFalse("桶空拒绝", limiter.tryAcquire("r", rule));
        now.set(500);                                   // 0.5s 恢复 1 个令牌
        assertTrue(limiter.tryAcquire("r", rule));
        assertFalse(limiter.tryAcquire("r", rule));
    }

    @Test
    public void refillCappedAtCapacity() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(clock);
        RateLimitRule rule = new RateLimitRule(2);
        assertTrue(limiter.tryAcquire("r", rule));
        now.set(10_000);                                // 长时间恢复也不超过容量
        assertTrue(limiter.tryAcquire("r", rule));
        assertTrue(limiter.tryAcquire("r", rule));
        assertFalse(limiter.tryAcquire("r", rule));
    }

    @Test
    public void resourcesAreIsolated() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(clock);
        RateLimitRule rule = new RateLimitRule(1);
        assertTrue(limiter.tryAcquire("a", rule));
        assertFalse(limiter.tryAcquire("a", rule));
        assertTrue("另一资源不受影响", limiter.tryAcquire("b", rule));
    }

    @Test
    public void illegalQpsFailsLoudly() {
        try {
            new RateLimitRule(0);
            fail("qps=0 应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 大声失败即预期（0 = 不限速由装配层表达，不进 Rule）
        }
    }
}
