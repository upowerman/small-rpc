package io.github.upowerman.core.metrics;

import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/** 3.0：治理拒绝与降级计数的单调累计与快照读取 */
public class RpcMetricsRejectionTest {

    @Before
    public void reset() {
        RpcMetrics.getInstance().reset();
    }

    @Test
    public void rejectionCountedPerReason() {
        RpcMetrics m = RpcMetrics.getInstance();
        m.recordRejection("svc#m", "circuit-open");
        m.recordRejection("svc#m", "circuit-open");
        m.recordRejection("svc#m", "rate-limit");

        List<RpcMetrics.Stat> stats = m.snapshot();
        assertEquals(1, stats.size());
        Map<String, Long> rejections = stats.get(0).rejections();
        assertEquals(Long.valueOf(2L), rejections.get("circuit-open"));
        assertEquals(Long.valueOf(1L), rejections.get("rate-limit"));
        assertEquals(0L, stats.get(0).degraded());
    }

    @Test
    public void degradedCountedPerKey() {
        RpcMetrics m = RpcMetrics.getInstance();
        m.recordDegraded("svc#m");
        m.recordDegraded("svc#m");
        m.recordDegraded("svc#other");

        List<RpcMetrics.Stat> stats = m.snapshot();
        assertEquals(2, stats.size());
        assertEquals(2L, find(stats, "svc#m").degraded());
        assertEquals(1L, find(stats, "svc#other").degraded());
    }

    private static RpcMetrics.Stat find(List<RpcMetrics.Stat> stats, String key) {
        for (RpcMetrics.Stat s : stats) {
            if (s.key().equals(key)) {
                return s;
            }
        }
        throw new AssertionError("missing " + key);
    }
}
