package io.github.upowerman.core.governance;

/**
 * 限流规则（不可变）：qps 每秒补充速率，桶容量 = qps（允许 1 秒突发）。
 * 0（不限）由装配层表达，不进 Rule。
 */
public final class RateLimitRule {

    private final int qps;

    public RateLimitRule(int qps) {
        if (qps < 1) {
            throw new IllegalArgumentException("qps must be >= 1: " + qps);
        }
        this.qps = qps;
    }

    public int qps() {
        return qps;
    }
}
