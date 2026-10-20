package io.github.upowerman.core.metrics;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RpcMetricsTest {

    @Before
    public void reset() {
        RpcMetrics.getInstance().reset();
    }

    @Test
    public void recordAggregatesByServiceMethodKey() {
        RpcMetrics m = RpcMetrics.getInstance();
        m.record("svc#m", true, 100L);
        m.record("svc#m", true, 300L);
        m.record("svc#m", false, 50L);

        List<RpcMetrics.Stat> stats = m.snapshot();
        assertEquals(1, stats.size());
        RpcMetrics.Stat stat = stats.get(0);
        assertEquals("svc#m", stat.key());
        assertEquals(3L, stat.total());
        assertEquals(2L, stat.success());
        assertEquals(1L, stat.failure());
        assertEquals(450L, stat.totalRtNanos());
        assertEquals(150L, stat.avgRtNanos());
        assertEquals(300L, stat.maxRtNanos());
    }

    @Test
    public void snapshotIsSortedAndImmutable() {
        RpcMetrics m = RpcMetrics.getInstance();
        m.record("b#m", true, 1L);
        m.record("a#m", true, 1L);

        List<RpcMetrics.Stat> stats = m.snapshot();
        assertEquals("a#m", stats.get(0).key());
        assertEquals("b#m", stats.get(1).key());
        try {
            stats.add(new RpcMetrics.Stat("x#y", 1, 1, 0, 1, 1));
            org.junit.Assert.fail("snapshot must be immutable");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
    }

    @Test
    public void nullKeyFallsBackToUnknown() {
        RpcMetrics m = RpcMetrics.getInstance();
        m.record(null, true, 1L);
        assertEquals(1L, m.snapshot().get(0).total());
        assertEquals("unknown", m.snapshot().get(0).key());
    }

    @Test
    public void maxRtMonotonicAcrossConcurrentRecords() throws Exception {
        final RpcMetrics m = RpcMetrics.getInstance();
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            final long rt = (t + 1) * 100L;
            threads[t] = new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int i = 0; i < 1000; i++) {
                        m.record("svc#m", true, rt);
                    }
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join();
        }
        RpcMetrics.Stat stat = m.snapshot().get(0);
        assertEquals(4000L, stat.total());
        assertEquals(400L, stat.maxRtNanos());
        assertTrue("累计 RT 应等于各线程总和", stat.totalRtNanos() >= (100L + 200L + 300L + 400L) * 1000);
    }
}
