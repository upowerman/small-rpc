package io.github.upowerman.core.governance;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/**
 * 令牌桶限流器：每资源一个桶（容量 = qps，按 qps/秒 补充），
 * synchronized 桶级互斥——教科书式实现，优先正确与简单。
 */
public class TokenBucketRateLimiter implements RateLimiter {

    private final LongSupplier clockMillis;
    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<String, Bucket>();

    public TokenBucketRateLimiter() {
        this(new LongSupplier() {
            @Override public long getAsLong() { return System.currentTimeMillis(); }
        });
    }

    public TokenBucketRateLimiter(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    @Override
    public boolean tryAcquire(String resource, RateLimitRule rule) {
        Bucket b = buckets.computeIfAbsent(resource, k -> new Bucket(rule.qps()));
        synchronized (b) {
            long now = clockMillis.getAsLong();
            double elapsed = (now - b.lastRefillMillis) / 1000.0;
            if (elapsed > 0) {
                b.tokens = Math.min(b.capacity, b.tokens + elapsed * b.capacity);
                b.lastRefillMillis = now;
            }
            if (b.tokens >= 1.0) {
                b.tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    private static final class Bucket {
        final double capacity;
        double tokens;
        long lastRefillMillis;

        Bucket(int qps) {
            this.capacity = qps;
            this.tokens = qps;                          // 满桶起步，允许 1 秒突发
        }
    }
}
