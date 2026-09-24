package io.github.upowerman.core.spi;

import io.github.upowerman.core.loadbalance.LoadBalancer;
import io.github.upowerman.core.loadbalance.RandomLoadBalancer;
import io.github.upowerman.core.loadbalance.RoundRobinLoadBalancer;
import io.github.upowerman.core.serialize.LegacyHessianSerializer;
import io.github.upowerman.core.serialize.Serializer;
import io.github.upowerman.core.spi.fixture.BoomSpi;
import io.github.upowerman.core.spi.fixture.BoomSpiGood;
import io.github.upowerman.core.spi.fixture.DemoSpi;
import io.github.upowerman.core.spi.fixture.DemoSpiA;
import io.github.upowerman.core.spi.fixture.DemoSpiNoDefault;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * SpiLoader 按名查找：登记解析、单例、默认扩展、大声失败。
 * fixtures 用 test 资源里独立登记的 DemoSpi，不与生产 META-INF 混用。
 */
public class SpiLoaderTest {

    @Test
    public void getExtensionReturnsRegisteredImplByName() {
        DemoSpi a = SpiLoader.of(DemoSpi.class).getExtension("a");
        assertTrue(a instanceof DemoSpiA);
    }

    @Test
    public void getExtensionIsSingletonPerName() {
        SpiLoader<DemoSpi> loader = SpiLoader.of(DemoSpi.class);
        assertSame(loader.getExtension("a"), loader.getExtension("a"));
    }

    @Test
    public void getSupportedExtensionsListsAllNames() {
        Set<String> names = SpiLoader.of(DemoSpi.class).getSupportedExtensions();
        assertEquals(Arrays.asList("a", "b", "with"), new ArrayList<String>(names));
    }

    @Test
    public void getDefaultExtensionReadsSpiAnnotationOnInterface() {
        assertTrue(SpiLoader.of(DemoSpi.class).getDefaultExtension() instanceof DemoSpiA);
    }

    @Test
    public void nullOrEmptyNameFallsBackToDefault() {
        assertTrue(SpiLoader.of(DemoSpi.class).getExtension(null) instanceof DemoSpiA);
        assertTrue(SpiLoader.of(DemoSpi.class).getExtension("") instanceof DemoSpiA);
    }

    @Test
    public void unknownNameFailsLoudlyWithSupportedList() {
        try {
            SpiLoader.of(DemoSpi.class).getExtension("nope");
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("nope"));
            assertTrue(e.getMessage().contains("a, b"));
        }
    }

    @Test
    public void interfaceWithoutSpiAnnotationHasNoDefault() {
        try {
            SpiLoader.of(DemoSpiNoDefault.class).getDefaultExtension();
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("@Spi"));
        }
    }

    @Test
    public void productionLoadBalancerRegistrationResolves() {
        SpiLoader<LoadBalancer> loader = SpiLoader.of(LoadBalancer.class);
        assertEquals(Arrays.asList("random", "roundrobin"), new ArrayList<String>(loader.getSupportedExtensions()));
        assertTrue(loader.getDefaultExtension() instanceof RandomLoadBalancer);
        assertTrue(loader.getExtension("roundrobin") instanceof RoundRobinLoadBalancer);
    }

    @Test
    public void productionSerializerRegistrationResolves() {
        SpiLoader<Serializer> loader = SpiLoader.of(Serializer.class);
        assertEquals(Collections.singletonList("hessian"), new ArrayList<String>(loader.getSupportedExtensions()));
        assertTrue(loader.getDefaultExtension() instanceof LegacyHessianSerializer);
    }

    @Test
    public void brokenExtensionDoesNotPoisonRegistrationAndFailsLoudlyOnDemand() {
        SpiLoader<BoomSpi> loader = SpiLoader.of(BoomSpi.class);   // 解析期不得触发任何扩展的静态初始化
        assertTrue(loader.getExtension("good") instanceof BoomSpiGood);
        try {
            loader.getExtension("boom");
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("BoomSpiBad"));
        }
    }
}
