package io.github.upowerman.core.governance;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * 默认熔断器：三态状态机（CLOSED/OPEN/HALF_OPEN）+ 每资源独立窗口。
 * 状态迁移与 HALF_OPEN 记账集中在 per-resource 锁内；tryPass 的 CLOSED 快路径
 * 仅读 volatile state 不加锁。每资源以首见的 Rule 建窗口——全局配置场景下
 * 所有调用方传同一 Rule 实例（装配层保证）。
 */
public class DefaultCircuitBreaker implements CircuitBreaker {

    private static final int CLOSED = 0;
    private static final int OPEN = 1;
    private static final int HALF_OPEN = 2;

    private final LongSupplier clockMillis;
    private final ConcurrentMap<String, BreakerState> states = new ConcurrentHashMap<String, BreakerState>();

    public DefaultCircuitBreaker() {
        this(new LongSupplier() {
            @Override public long getAsLong() { return System.currentTimeMillis(); }
        });
    }

    public DefaultCircuitBreaker(LongSupplier clockMillis) {
        this.clockMillis = clockMillis;
    }

    @Override
    public boolean tryPass(String resource, CircuitBreakerRule rule) {
        return stateOf(resource, rule).tryPass();
    }

    @Override
    public void onComplete(String resource, CircuitBreakerRule rule, boolean success,
                           long rtMillis, long timeoutBudgetMillis) {
        stateOf(resource, rule).onComplete(success, rtMillis, timeoutBudgetMillis);
    }

    private BreakerState stateOf(String resource, CircuitBreakerRule rule) {
        BreakerState s = states.get(resource);
        if (s == null) {
            states.putIfAbsent(resource, new BreakerState(rule));
            s = states.get(resource);
        }
        return s;
    }

    /** 单资源状态机（锁保护迁移；窗口为非 final 字段，CLOSED 恢复时整体换新即"重置窗口"） */
    private final class BreakerState {

        private final ReentrantLock lock = new ReentrantLock();
        private final CircuitBreakerRule rule;
        private SlidingWindow window;
        private volatile int state = CLOSED;
        private volatile long openedAtMillis;
        private int halfOpenRemaining;                       // 以下三项均由 lock 保护
        private int halfOpenSuccesses;
        private boolean halfOpenFailed;

        BreakerState(CircuitBreakerRule rule) {
            this.rule = rule;
            this.window = new SlidingWindow(rule.windowMillis(), 1000L, clockMillis);
        }

        boolean tryPass() {
            if (state == CLOSED) {
                return true;
            }
            lock.lock();
            try {
                if (state == OPEN && clockMillis.getAsLong() - openedAtMillis >= rule.openMillis()) {
                    state = HALF_OPEN;
                    halfOpenRemaining = rule.halfOpenProbes();
                    halfOpenSuccesses = 0;
                    halfOpenFailed = false;
                }
                if (state == HALF_OPEN) {
                    if (halfOpenRemaining > 0) {
                        halfOpenRemaining--;
                        return true;
                    }
                    return false;                            // 名额被占：其余按 OPEN 拒绝
                }
                return false;
            } finally {
                lock.unlock();
            }
        }

        void onComplete(boolean success, long rtMillis, long timeoutBudgetMillis) {
            lock.lock();
            try {
                if (state == HALF_OPEN) {
                    completeProbe(success);
                    return;
                }
                if (state == OPEN) {
                    return;                                  // OPEN 期间迟到的终态忽略
                }
                window.record(success, isSlow(rtMillis, timeoutBudgetMillis), rtMillis);
                maybeTrip();
            } finally {
                lock.unlock();
            }
        }

        /**
         * 探测记账：任一失败 → 立即回 OPEN 重新计时。
         * 恢复条件 = 已放行探测全部成功（successes + 剩余名额凑满总名额；
         * 低流量下首个成功即恢复，与 Sentinel 行为一致，不等待未必到来的流量）。
         */
        private void completeProbe(boolean success) {
            if (halfOpenFailed) {
                return;
            }
            if (!success) {
                halfOpenFailed = true;
                open();
                return;
            }
            halfOpenSuccesses++;
            if (halfOpenSuccesses + halfOpenRemaining >= rule.halfOpenProbes()) {
                state = CLOSED;
                window = new SlidingWindow(rule.windowMillis(), 1000L, clockMillis);
            }
        }

        private void maybeTrip() {
            SlidingWindow.Stats s = window.stats();
            if (s.total < rule.minCalls()) {
                return;
            }
            if (rule.errorRateThreshold() > 0
                    && (double) s.error / s.total >= rule.errorRateThreshold()) {
                open();
                return;
            }
            if (rule.slowCallRateThreshold() > 0
                    && (double) s.slow / s.total >= rule.slowCallRateThreshold()) {
                open();
            }
        }

        private void open() {
            state = OPEN;
            openedAtMillis = clockMillis.getAsLong();
        }

        /** 慢阈值：显式 slow-rt-ms > 0 优先；否则预算×0.8 推导；两者皆无 → 慢条件停用 */
        private boolean isSlow(long rtMillis, long timeoutBudgetMillis) {
            long threshold = rule.slowRtMillis();
            if (threshold <= 0 && timeoutBudgetMillis > 0) {
                threshold = timeoutBudgetMillis * 8 / 10;
            }
            return threshold > 0 && rtMillis > threshold;
        }
    }
}
