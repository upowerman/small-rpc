package io.github.upowerman.core.filter;

import io.github.upowerman.core.governance.RateLimitRule;
import io.github.upowerman.core.governance.RateLimiter;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.metrics.RpcMetrics;
import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import io.github.upowerman.exception.RpcException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;

/**
 * 限流 Filter（3.0 治理）：速率闸门（RateLimiter SPI）+ 并发闸门（每资源 Semaphore）。
 * 两个闸门独立可配（null/非正 = 关闭）；拒绝一律 RATE_LIMITED 本地快速结算。
 * 并发许可覆盖成功/失败/异常所有完成路径（whenComplete 释放），异步链路不泄漏。
 */
public class RateLimitFilter implements Filter {

    private final RateLimiter limiter;
    private final RateLimitRule rule;
    private final int maxConcurrent;
    private final ConcurrentMap<String, Semaphore> semaphores = new ConcurrentHashMap<String, Semaphore>();

    public RateLimitFilter(RateLimiter limiter, RateLimitRule rule, int maxConcurrent) {
        this.limiter = limiter;
        this.rule = rule;
        this.maxConcurrent = maxConcurrent;
    }

    @Override
    public CompletableFuture<Result> invoke(Invoker next, Invocation invocation) {
        final String resource = invocation.serviceName() + "#" + invocation.methodName();
        if (limiter != null && rule != null && !limiter.tryAcquire(resource, rule)) {
            RpcMetrics.getInstance().recordRejection(resource, "rate-limit");
            return CompletableFuture.completedFuture(DefaultResult.failure(Status.RATE_LIMITED,
                    new RpcException("rate limited for " + resource)));
        }
        if (maxConcurrent <= 0) {
            return next.invoke(invocation);
        }
        Semaphore semaphore = semaphores.computeIfAbsent(resource, k -> new Semaphore(maxConcurrent));
        if (!semaphore.tryAcquire()) {
            RpcMetrics.getInstance().recordRejection(resource, "concurrent-limit");
            return CompletableFuture.completedFuture(DefaultResult.failure(Status.RATE_LIMITED,
                    new RpcException("concurrent limit reached for " + resource)));
        }
        try {
            return next.invoke(invocation).whenComplete((result, error) -> semaphore.release());
        } catch (Throwable t) {
            // 下游同步抛出时 future 不存在，whenComplete 挂不上——此处不释放即永久泄漏
            semaphore.release();
            throw t;
        }
    }
}
