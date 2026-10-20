package io.github.upowerman.core.governance;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.Assert.assertEquals;

/** 假时钟驱动的滑动窗口统计：桶滚动、窗口淘汰、回拨保护、同毫秒累加 */
public class SlidingWindowTest {

    private final AtomicLong now = new AtomicLong(0);

    private SlidingWindow window() {
        return new SlidingWindow(10_000L, 1000L, new LongSupplier() {
            @Override public long getAsLong() { return now.get(); }
        });
    }

    @Test
    public void aggregatesAcrossBuckets() {
        SlidingWindow w = window();
        w.record(true, false, 5);
        now.set(1500);
        w.record(false, true, 100);
        now.set(2500);
        SlidingWindow.Stats s = w.stats();
        assertEquals(2L, s.total);
        assertEquals(1L, s.error);
        assertEquals(1L, s.slow);
        assertEquals(105L, s.totalRtMillis);
    }

    @Test
    public void staleSamplesDropOutOfWindow() {
        SlidingWindow w = window();
        w.record(true, false, 5);          // t=0
        now.set(11_000);                    // 10s 窗口只保留 (11000-10000, 11000]
        SlidingWindow.Stats s = w.stats();
        assertEquals(0L, s.total);
        // 新样本进入新桶后统计恢复
        w.record(true, false, 7);
        assertEquals(1L, w.stats().total);
    }

    @Test
    public void sameMillisAccumulatesWithoutReset() {
        SlidingWindow w = window();
        w.record(true, false, 1);
        w.record(true, false, 1);
        w.record(false, false, 1);
        assertEquals(3L, w.stats().total);
        assertEquals(1L, w.stats().error);
    }

    @Test
    public void clockJumpBackDoesNotClearFutureBuckets() {
        SlidingWindow w = window();
        now.set(5000);
        w.record(true, false, 1);
        now.set(2000);                      // 回拨：桶起始时间变小，不清零已记数据
        w.record(true, false, 1);
        assertEquals(2L, w.stats().total);
    }
}
