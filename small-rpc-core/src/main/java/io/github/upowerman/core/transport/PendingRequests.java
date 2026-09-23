package io.github.upowerman.core.transport;

import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import io.github.upowerman.exception.RpcException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * in-flight 请求表：requestId(long, AtomicLong 生成) → CompletableFuture。
 * 取代 1.x 的 RpcFutureResponse(wait/notify) 与 String requestId。
 * <p>
 * 本表拥有 in-flight 条目的生命周期：注册时可附带一个超时，超时未完成会
 * 自动移除表项并以 TIMEOUT 结果完成 future，确保超时调用不会永久滞留
 * （含完整的 RpcRequest 参数图）。{@code timeoutMillis <= 0} 表示不设超时，
 * 仅供明确知情的调用方使用，链路兜底层一律以正值调用。
 */
public class PendingRequests {

    private static final ScheduledThreadPoolExecutor TIMEOUT_SCHEDULER =
            new ScheduledThreadPoolExecutor(1, new ThreadFactory() {
                private final AtomicInteger counter = new AtomicInteger();

                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "small-rpc-pending-" + counter.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                }
            });

    static {
        TIMEOUT_SCHEDULER.setRemoveOnCancelPolicy(true);
    }

    private final ConcurrentMap<Long, CompletableFuture<Result>> pending = new ConcurrentHashMap<Long, CompletableFuture<Result>>();
    private final AtomicLong idGenerator = new AtomicLong();

    public long nextRequestId() {
        return idGenerator.incrementAndGet();
    }

    public CompletableFuture<Result> register(long requestId) {
        return register(requestId, 0L);
    }

    /**
     * 注册 in-flight 条目。{@code timeoutMillis > 0} 时另行调度一个超时任务：
     * 到期若仍未完成，则移除表项并以 TIMEOUT 结果完成 future。
     * 条目一旦正常（完成/失败）或超时完成，定时任务即被取消。
     */
    public CompletableFuture<Result> register(long requestId, long timeoutMillis) {
        final CompletableFuture<Result> future = new CompletableFuture<Result>();
        pending.put(requestId, future);
        if (timeoutMillis > 0) {
            final ScheduledFuture<?> task = TIMEOUT_SCHEDULER.schedule(() -> {
                CompletableFuture<Result> f = pending.remove(requestId);
                if (f != null) {
                    f.complete(DefaultResult.failure(Status.TIMEOUT,
                            new RpcException("request " + requestId + " timed out after " + timeoutMillis + "ms")));
                }
            }, timeoutMillis, TimeUnit.MILLISECONDS);
            // future 已结算（完成/失败/超时）即取消定时任务
            future.whenComplete((r, t) -> task.cancel(false));
        }
        return future;
    }

    /** 完成请求并移除表项；未知 requestId 静默忽略（响应迟到/请求已超时移除） */
    public void complete(long requestId, Result result) {
        CompletableFuture<Result> future = pending.remove(requestId);
        if (future != null) {
            future.complete(result);
        }
    }

    public void remove(long requestId) {
        pending.remove(requestId);
    }

    public int size() {
        return pending.size();
    }
}
