package io.github.upowerman.core.filter;

import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.invocation.GenericInvocation;
import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.governance.RateLimitRule;
import io.github.upowerman.core.governance.TokenBucketRateLimiter;
import io.github.upowerman.core.metrics.RpcMetrics;
import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import org.junit.Test;

import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** 限流 Filter：令牌桶拒绝 / 并发闸门拒绝与许可释放（含失败与异步路径） */
public class RateLimitFilterTest {

    private static Invocation invocation(String method) {
        return new GenericInvocation("svc.S", method, new Class<?>[0], new Object[0],
                new HashMap<String, Object>());
    }

    /** 直通下游，可选阻塞以占用并发许可 */
    private static Invoker passthrough(final long blockMillis) {
        return new Invoker() {
            @Override public Class<?> interfaceClass() { return Object.class; }
            @Override public CompletableFuture<Result> invoke(Invocation inv) {
                if (blockMillis > 0) {
                    try {
                        Thread.sleep(blockMillis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return CompletableFuture.completedFuture(DefaultResult.success("ok"));
            }
        };
    }

    private static Invoker failing() {
        return new Invoker() {
            @Override public Class<?> interfaceClass() { return Object.class; }
            @Override public CompletableFuture<Result> invoke(Invocation inv) {
                return CompletableFuture.completedFuture(
                        DefaultResult.failure(Status.SERVER_ERROR, new RuntimeException("boom")));
            }
        };
    }

    @Test
    public void qpsExhaustedRejectsImmediately() {
        AtomicLong now = new AtomicLong(0);
        LongSupplier clock = new LongSupplier() {
            @Override public long getAsLong() { return now.get(); }
        };
        RateLimitFilter filter = new RateLimitFilter(new TokenBucketRateLimiter(clock),
                new RateLimitRule(1), 0);
        assertEquals(Status.SUCCESS, filter.invoke(passthrough(0), invocation("m")).join().status());
        Result rejected = filter.invoke(passthrough(0), invocation("m")).join();
        assertEquals(Status.RATE_LIMITED, rejected.status());   // Review Focus #1：join 立即返回
        now.set(1000);
        assertEquals(Status.SUCCESS, filter.invoke(passthrough(0), invocation("m")).join().status());
    }

    @Test
    public void concurrentGateRejectsAndReleases() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(null, null, 1);
        final CountDownLatch inFlight = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        // 阻塞必须在后台线程：Invoker 同步 await 会与主线程的 countDown 互锁
        final CompletableFuture<Result> blockedFuture = new CompletableFuture<Result>();
        Invoker blocking = new Invoker() {
            @Override public Class<?> interfaceClass() { return Object.class; }
            @Override public CompletableFuture<Result> invoke(Invocation inv) {
                inFlight.countDown();
                new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            release.await();                    // 持有许可不放（模拟慢在途请求）
                            blockedFuture.complete(DefaultResult.success("ok"));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            blockedFuture.completeExceptionally(e);
                        }
                    }
                }).start();
                return blockedFuture;
            }
        };
        CompletableFuture<Result> first = filter.invoke(blocking, invocation("m"));
        assertTrue(inFlight.await(2, TimeUnit.SECONDS));
        assertEquals(Status.RATE_LIMITED, filter.invoke(passthrough(0), invocation("m")).join().status());
        release.countDown();
        first.join();
        assertEquals("许可释放后可再次通过", Status.SUCCESS,
                filter.invoke(passthrough(0), invocation("m")).join().status());
    }

    @Test
    public void permitReleasedOnFailure() {                      // Review Focus #2
        RateLimitFilter filter = new RateLimitFilter(null, null, 1);
        assertEquals(Status.SERVER_ERROR, filter.invoke(failing(), invocation("m")).join().status());
        assertEquals("失败路径也要释放许可", Status.SUCCESS,
                filter.invoke(passthrough(0), invocation("m")).join().status());
    }

    @Test
    public void permitReleasedOnSynchronousDownstreamThrow() {   // 评审 Important #2
        RateLimitFilter filter = new RateLimitFilter(null, null, 1);
        Invoker exploding = new Invoker() {
            @Override public Class<?> interfaceClass() { return Object.class; }
            @Override public CompletableFuture<Result> invoke(Invocation inv) {
                throw new IllegalStateException("sync boom before future");
            }
        };
        try {
            filter.invoke(exploding, invocation("m"));
            org.junit.Assert.fail("同步异常应原样上抛");
        } catch (IllegalStateException expected) {
            // 预期：异常不被吞
        }
        assertEquals("同步抛出路径也要释放许可", Status.SUCCESS,
                filter.invoke(passthrough(0), invocation("m")).join().status());
    }

    @Test
    public void rejectsRecordedInMetrics() {
        RpcMetrics.getInstance().reset();
        AtomicLong now = new AtomicLong(0);
        RateLimitFilter filter = new RateLimitFilter(new TokenBucketRateLimiter(new LongSupplier() {
            @Override public long getAsLong() { return now.get(); }
        }), new RateLimitRule(1), 0);
        filter.invoke(passthrough(0), invocation("m")).join();
        filter.invoke(passthrough(0), invocation("m")).join();
        for (RpcMetrics.Stat s : RpcMetrics.getInstance().snapshot()) {
            if (s.key().equals("svc.S#m")) {
                assertEquals(Long.valueOf(1L), s.rejections().get("rate-limit"));
                return;
            }
        }
        throw new AssertionError("missing rejection stat");
    }
}
