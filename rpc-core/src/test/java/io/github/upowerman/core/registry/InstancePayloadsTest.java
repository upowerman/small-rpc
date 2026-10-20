package io.github.upowerman.core.registry;

import io.github.upowerman.core.directory.ServiceInstance;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class InstancePayloadsTest {

    @Test
    public void roundTripsWeightStartTimeAndWarmup() {
        ServiceInstance original = new ServiceInstance("10.0.0.1:7081", 3, 1690000000000L, 60000L);
        ServiceInstance decoded = InstancePayloads.decode("10.0.0.1:7081", InstancePayloads.encode(original));

        assertEquals("10.0.0.1:7081", decoded.getAddress());
        assertEquals(3, decoded.getWeight());
        assertEquals(1690000000000L, decoded.getStartTime());
        assertEquals(60000L, decoded.getWarmupMillis());
    }

    @Test
    public void nullOrBlankPayloadFallsBackToDefaults() {
        for (String payload : new String[]{null, "", "   "}) {
            ServiceInstance decoded = InstancePayloads.decode("10.0.0.1:7081", payload);
            assertEquals("10.0.0.1:7081", decoded.getAddress());
            assertEquals("旧格式按等权", 1, decoded.getWeight());
            assertEquals("旧格式按未知启动时间（=已预热）", 0L, decoded.getStartTime());
            assertEquals("旧格式不预热", 0L, decoded.getWarmupMillis());
        }
    }

    @Test
    public void garbageOrPartialPayloadNeverThrows() {
        // 乱码 / 半截 / 坏值：按可解析的部分取值，其余回退缺省——坏行不能炸整份快照
        ServiceInstance garbage = InstancePayloads.decode("a:1", "not-a-payload");
        assertEquals(1, garbage.getWeight());

        ServiceInstance partial = InstancePayloads.decode("a:1", "w=7;??;t=abc");
        assertEquals(7, partial.getWeight());
        assertEquals("坏的 t 值回退 0", 0L, partial.getStartTime());

        ServiceInstance unknownKey = InstancePayloads.decode("a:1", "x=9;w=5;wm=123");
        assertEquals("未知键被忽略", 5, unknownKey.getWeight());
        assertEquals(123L, unknownKey.getWarmupMillis());
    }

    @Test
    public void encodeNullInstanceYieldsEmptyPayload() {
        assertEquals("", InstancePayloads.encode(null));
    }
}
