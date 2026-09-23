package io.github.upowerman.core.transport;

import io.github.upowerman.core.result.Result;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * in-flight 请求表：requestId(long, AtomicLong 生成) → CompletableFuture。
 * 取代 1.x 的 RpcFutureResponse(wait/notify) 与 String requestId。
 */
public class PendingRequests {

    private final ConcurrentMap<Long, CompletableFuture<Result>> pending = new ConcurrentHashMap<Long, CompletableFuture<Result>>();
    private final AtomicLong idGenerator = new AtomicLong();

    public long nextRequestId() {
        return idGenerator.incrementAndGet();
    }

    public CompletableFuture<Result> register(long requestId) {
        CompletableFuture<Result> future = new CompletableFuture<Result>();
        pending.put(requestId, future);
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