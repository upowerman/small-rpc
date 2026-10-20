package io.github.upowerman.core.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 消费端调用指标（C8）：按 {@code service#method} 维度累计 总数 / 成功 / 失败 /
 * 累计 RT 与最大 RT。零外部依赖的进程级单例，供监控端点或运维接口周期性
 * {@link #snapshot()} 读取；对接 Micrometer 等外部体系由使用方基于快照自行桥接。
 * <p>
 * 只做计数不做 QPS 估算：快照是单调累计值，两次快照差值 / 时间窗即得速率，
 * 比框架内私自计时更可验证。
 */
public final class RpcMetrics {

    private static final RpcMetrics INSTANCE = new RpcMetrics();

    private final ConcurrentHashMap<String, Counters> counters = new ConcurrentHashMap<String, Counters>();

    private RpcMetrics() {
    }

    public static RpcMetrics getInstance() {
        return INSTANCE;
    }

    /** 记录一次调用结果；rtNanos 为端到端耗时（由 Filter 计时） */
    public void record(String key, boolean success, long rtNanos) {
        Counters c = counters.computeIfAbsent(key == null ? "unknown" : key,
                k -> new Counters());
        c.total.incrementAndGet();
        if (success) {
            c.success.incrementAndGet();
        } else {
            c.failure.incrementAndGet();
        }
        c.totalRtNanos.addAndGet(rtNanos);
        c.maxRtNanos.accumulateAndGet(rtNanos, Math::max);
    }

    /** 当前全部指标的不可变快照（调用方安全遍历） */
    public List<Stat> snapshot() {
        List<Stat> stats = new ArrayList<Stat>(counters.size());
        for (Map.Entry<String, Counters> entry : counters.entrySet()) {
            Counters c = entry.getValue();
            stats.add(new Stat(entry.getKey(), c.total.get(), c.success.get(), c.failure.get(),
                    c.totalRtNanos.get(), c.maxRtNanos.get()));
        }
        Collections.sort(stats, (a, b) -> a.key().compareTo(b.key()));
        return Collections.unmodifiableList(stats);
    }

    /** 清空全部计数（测试与运维重置用） */
    public void reset() {
        counters.clear();
    }

    /** 一个 {@code service#method} 的累计指标快照（不可变） */
    public static final class Stat {
        private final String key;
        private final long total;
        private final long success;
        private final long failure;
        private final long totalRtNanos;
        private final long maxRtNanos;

        Stat(String key, long total, long success, long failure, long totalRtNanos, long maxRtNanos) {
            this.key = key;
            this.total = total;
            this.success = success;
            this.failure = failure;
            this.totalRtNanos = totalRtNanos;
            this.maxRtNanos = maxRtNanos;
        }

        public String key() {
            return key;
        }

        public long total() {
            return total;
        }

        public long success() {
            return success;
        }

        public long failure() {
            return failure;
        }

        public long totalRtNanos() {
            return totalRtNanos;
        }

        public long maxRtNanos() {
            return maxRtNanos;
        }

        public long avgRtNanos() {
            return total == 0 ? 0L : totalRtNanos / total;
        }

        @Override
        public String toString() {
            return "Stat{" + key + ", total=" + total + ", success=" + success
                    + ", failure=" + failure + ", avgRt=" + avgRtNanos() + "ns, maxRt=" + maxRtNanos + "ns}";
        }
    }

    private static final class Counters {
        final AtomicLong total = new AtomicLong();
        final AtomicLong success = new AtomicLong();
        final AtomicLong failure = new AtomicLong();
        final AtomicLong totalRtNanos = new AtomicLong();
        final AtomicLong maxRtNanos = new AtomicLong();
    }
}
