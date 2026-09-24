package io.github.upowerman.core.transport;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Java 8 没有 CompletableFuture.orTimeout(Java 9+)，
 * 用 applyToEither + 调度器实现"先到者赢"的超时语义。
 * <p>
 * 语义约定：{@code timeoutMillis <= 0} 表示<b>不设超时</b>（直接原样返回上游 future）。
 * 该语义只允许在明确知情的调用方使用——例如 FailoverClusterInvoker 在剩余预算耗尽时
 * 直接短路返回 TIMEOUT，而绝不会把非正超时传给本方法。链路中任何需要
 * "请求必须被兜底"的位置（如传输层 PendingRequests）都必须以正值调度，
 * 避免非正超时导致挂起的请求永不超时。
 */
public final class Futures {

    private static final ScheduledThreadPoolExecutor TIMEOUT_SCHEDULER =
            new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
                private final AtomicInteger counter = new AtomicInteger();

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "small-rpc-timeout-" + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            });

    static {
        // 已取消的定时任务立即从队列移除，避免 retries+1 个任务堆积在单一线程上
        TIMEOUT_SCHEDULER.setRemoveOnCancelPolicy(true);
    }

    private Futures() {
    }

    public static <T> CompletableFuture<T> withTimeout(CompletableFuture<T> future,
                                                       long timeoutMillis,
                                                       Supplier<T> onTimeout) {
        if (timeoutMillis <= 0) {
            // 显式约定的"不设超时"：仅调用方自行保证上游一定完成（或另有兜底）
            return future;
        }
        CompletableFuture<T> timeout = new CompletableFuture<T>();
        ScheduledFuture<?> task = TIMEOUT_SCHEDULER.schedule(
                () -> timeout.complete(onTimeout.get()), timeoutMillis, TimeUnit.MILLISECONDS);
        CompletableFuture<T> result = future.applyToEither(timeout, Function.identity());
        // 任一方先完成即取消定时任务，避免定时任务无谓存留
        result.whenComplete((r, t) -> task.cancel(false));
        return result;
    }
}
