package io.github.upowerman.core.spi;

import io.github.upowerman.core.spi.fixture.ConstructionGate;
import io.github.upowerman.core.spi.fixture.SlowSpi;
import io.github.upowerman.core.spi.fixture.SlowSpiImpl;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * C-2：扩展实例化不得持 SpiLoader 监视器。
 * 持锁实例化会串行化全部扩展创建，且扩展间互相 @SpiInject 时形成跨 loader 的 ABBA 死锁。
 */
public class SpiLoaderConcurrencyTest {

    @Test
    public void concurrentGetExtensionOfSameNameReturnsSameInstance() throws Exception {
        ConstructionGate gate = new ConstructionGate();
        SlowSpiImpl.gate = gate;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            final CyclicBarrier barrier = new CyclicBarrier(4);
            List<Future<SlowSpi>> futures = new ArrayList<Future<SlowSpi>>();
            for (int i = 0; i < 4; i++) {
                futures.add(pool.submit(new Callable<SlowSpi>() {
                    @Override
                    public SlowSpi call() throws Exception {
                        barrier.await(10, TimeUnit.SECONDS);
                        return SpiLoader.of(SlowSpi.class).getExtension("slow-a");
                    }
                }));
            }
            assertTrue("至少一个线程进入构造器", gate.awaitEntered(10000));
            gate.open();
            SlowSpi first = futures.get(0).get(10, TimeUnit.SECONDS);
            assertNotNull(first);
            for (Future<SlowSpi> future : futures) {
                assertSame(first, future.get(10, TimeUnit.SECONDS));
            }
        } finally {
            gate.open();
            SlowSpiImpl.gate = null;
            pool.shutdownNow();
        }
    }

    @Test
    public void instantiationOfOneExtensionDoesNotBlockOtherNames() throws Exception {
        ConstructionGate gate = new ConstructionGate();
        SlowSpiImpl.gate = gate;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<SlowSpi> blocking = pool.submit(new Callable<SlowSpi>() {
                @Override
                public SlowSpi call() {
                    return SpiLoader.of(SlowSpi.class).getExtension("slow-b");
                }
            });
            assertTrue("slow-b 已进入构造器", gate.awaitEntered(10000));

            // 构造进行中，另一个名字的扩展仍须立刻可取（修复前会阻塞在 SpiLoader 监视器上）
            Future<SlowSpi> other = pool.submit(new Callable<SlowSpi>() {
                @Override
                public SlowSpi call() {
                    return SpiLoader.of(SlowSpi.class).getExtension("fast-a");
                }
            });
            try {
                assertNotNull(other.get(1, TimeUnit.SECONDS));
            } catch (TimeoutException e) {
                gate.open();
                fail("实例化持有 SpiLoader 监视器：fast-a 被 slow-b 的构造阻塞");
            }
            gate.open();
            assertNotNull(blocking.get(10, TimeUnit.SECONDS));
        } finally {
            gate.open();
            SlowSpiImpl.gate = null;
            pool.shutdownNow();
        }
    }
}
