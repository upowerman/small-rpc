package io.github.upowerman.core.spi;

import io.github.upowerman.core.spi.fixture.CycleSpi;
import io.github.upowerman.core.spi.fixture.DemoSpi;
import io.github.upowerman.core.spi.fixture.WithInject;
import org.junit.Test;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @SpiInject：默认扩展按类型注入；循环依赖大声失败。
 */
public class SpiInjectTest {

    @Test
    public void spiInjectFieldGetsDefaultExtension() {
        WithInject ext = (WithInject) SpiLoader.of(DemoSpi.class).getExtension("with");
        assertTrue(ext.dependency instanceof DemoSpi);
        // 注入的是同一 SpiLoader 池里的单例
        assertSame(SpiLoader.of(DemoSpi.class).getDefaultExtension(), ext.dependency);
    }

    @Test
    public void cycleDependencyFailsLoudlyNotStackOverflow() {
        try {
            SpiLoader.of(CycleSpi.class).getExtension("cycle-a");
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("cycle"));
        }
    }
}
