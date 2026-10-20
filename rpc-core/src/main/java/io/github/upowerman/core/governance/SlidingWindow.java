package io.github.upowerman.core.governance;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

/**
 * 单资源滑动窗口（Sentinel LeapArray 的最小可读版）：秒级桶环形数组，
 * 读写均以桶起始时间戳判新——时间推进到新桶即整桶重置，窗口外的旧桶不计入统计。
 * 同毫秒多次 record 只做累加；时钟回拨（新桶起始 &lt; 已记起始）不清零，按当前桶继续累加。
 * 线程模型：实例级 ReentrantLock——record 频率为调用频率，教学取舍优先正确与简单。
 */
public class SlidingWindow {

    private final long bucketMillis;
    private final Bucket[] buckets;
    private final long windowMillis;
    private final LongSupplier clockMillis;
    private final ReentrantLock lock = new ReentrantLock();

    public SlidingWindow(long windowMillis, long bucketMillis, LongSupplier clockMillis) {
        if (windowMillis <= 0 || bucketMillis <= 0 || windowMillis % bucketMillis != 0) {
            throw new IllegalArgumentException(
                    "illegal window/bucket: " + windowMillis + "/" + bucketMillis);
        }
        this.windowMillis = windowMillis;
        this.bucketMillis = bucketMillis;
        this.buckets = new Bucket[(int) (windowMillis / bucketMillis)];
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = new Bucket();
        }
        this.clockMillis = clockMillis;
    }

    public void record(boolean success, boolean slow, long rtMillis) {
        long now = clockMillis.getAsLong();
        long bucketStart = now - now % bucketMillis;
        lock.lock();
        try {
            Bucket b = buckets[index(bucketStart)];
            // 只有「更新的桶起始」才重置：回拨不清零，同桶累加
            if (bucketStart > b.startMillis) {
                b.reset(bucketStart);
            }
            b.total++;
            if (!success) {
                b.error++;
            }
            if (slow) {
                b.slow++;
            }
            b.totalRtMillis += rtMillis;
        } finally {
            lock.unlock();
        }
    }

    public Stats stats() {
        long now = clockMillis.getAsLong();
        long minStart = now - windowMillis;
        long total = 0, error = 0, slow = 0, totalRt = 0;
        lock.lock();
        try {
            for (Bucket b : buckets) {
                // 已使用桶判定用 >= 0：t=0（epoch 对齐）的合法桶不能被当未使用排除
                if (b.startMillis >= 0 && b.startMillis > minStart) {
                    total += b.total;
                    error += b.error;
                    slow += b.slow;
                    totalRt += b.totalRtMillis;
                }
            }
        } finally {
            lock.unlock();
        }
        return new Stats(total, error, slow, totalRt);
    }

    private int index(long bucketStart) {
        return (int) ((bucketStart / bucketMillis) % buckets.length);
    }

    /** 窗口聚合快照（不可变） */
    public static final class Stats {
        public final long total;
        public final long error;
        public final long slow;
        public final long totalRtMillis;

        Stats(long total, long error, long slow, long totalRtMillis) {
            this.total = total;
            this.error = error;
            this.slow = slow;
            this.totalRtMillis = totalRtMillis;
        }
    }

    private static final class Bucket {
        long startMillis = -1;
        long total;
        long error;
        long slow;
        long totalRtMillis;

        void reset(long start) {
            this.startMillis = start;
            this.total = 0;
            this.error = 0;
            this.slow = 0;
            this.totalRtMillis = 0;
        }
    }
}
