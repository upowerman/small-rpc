package io.github.upowerman.core.server;

import org.junit.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * B4：业务线程池构造规则——有界队列打满时 CallerRuns 在提交线程执行（背压），不拒绝。
 */
public class RpcServerThreadPoolTest {

    @Test
    public void boundedQueueSaturatesIntoCallerRunsInsteadOfRejection() throws Exception {
        // 2 线程 + 容量 2 队列：最多并行 2、排队 2，其后任务由提交线程亲自执行
        //（任务短阻塞，等待超时后放行，避免 CallerRuns 把提交线程堵死在测试里）
        ExecutorService pool = RpcServer.createBusinessExecutor(2, 2);
        final AtomicInteger executed = new AtomicInteger();
        try {
            for (int i = 0; i < 10; i++) {
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        executed.incrementAndGet();
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
            }
        } finally {
            pool.shutdown();
        }
        assertTrue("池内任务应全部完成", pool.awaitTermination(15, TimeUnit.SECONDS));
        assertEquals("CallerRuns 下 10 次提交必须全部执行，不丢不拒", 10, executed.get());
    }

    @Test
    public void nonPositiveThreadsFallBackToDefault() {
        // 非法线程数回退缺省 8（与历史行为一致），构造可用即可
        ExecutorService pool = RpcServer.createBusinessExecutor(0, 0);
        try {
            final AtomicInteger ran = new AtomicInteger();
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    ran.incrementAndGet();
                }
            });
            long deadline = System.currentTimeMillis() + 3000;
            while (ran.get() == 0 && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            assertEquals(1, ran.get());
        } finally {
            pool.shutdown();
        }
    }
}
