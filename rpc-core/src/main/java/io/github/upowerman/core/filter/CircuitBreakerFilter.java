package io.github.upowerman.core.filter;

import io.github.upowerman.core.RpcConstants;
import io.github.upowerman.core.governance.CircuitBreaker;
import io.github.upowerman.core.governance.CircuitBreakerRule;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.metrics.RpcMetrics;
import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import io.github.upowerman.exception.RpcException;

import java.util.concurrent.CompletableFuture;

/**
 * 熔断 Filter（3.0 治理）：tryPass 拒绝 → CIRCUIT_OPEN 本地快速结算（绝不悬挂、不进重试）；
 * 调用终态回填熔断器（一次 Filter 调用恰一次 onComplete，重试在 Cluster 内部不放大统计）。
 * 超时预算解析与 FailoverClusterInvoker 同规则：attachments[ATTACH_TIMEOUT] > 全局默认。
 */
public class CircuitBreakerFilter implements Filter {

    private final CircuitBreaker breaker;
    private final CircuitBreakerRule rule;
    private final long defaultTimeoutMillis;

    public CircuitBreakerFilter(CircuitBreaker breaker, CircuitBreakerRule rule,
                                long defaultTimeoutMillis) {
        this.breaker = breaker;
        this.rule = rule;
        this.defaultTimeoutMillis = defaultTimeoutMillis;
    }

    @Override
    public CompletableFuture<Result> invoke(Invoker next, Invocation invocation) {
        final String resource = invocation.serviceName() + "#" + invocation.methodName();
        if (!breaker.tryPass(resource, rule)) {
            RpcMetrics.getInstance().recordRejection(resource, "circuit-open");
            return CompletableFuture.completedFuture(DefaultResult.failure(Status.CIRCUIT_OPEN,
                    new RpcException("circuit open for " + resource)));
        }
        final long budget = resolveBudget(invocation);
        final long startNanos = System.nanoTime();
        return next.invoke(invocation).whenComplete((result, error) -> {
            boolean success = error == null && result != null && result.status() == Status.SUCCESS;
            long rtMillis = (System.nanoTime() - startNanos) / 1_000_000L;
            breaker.onComplete(resource, rule, success, rtMillis, budget);
        });
    }

    private long resolveBudget(Invocation invocation) {
        Object timeout = invocation.attachments().get(RpcConstants.ATTACH_TIMEOUT);
        if (timeout instanceof Number && ((Number) timeout).longValue() > 0) {
            return ((Number) timeout).longValue();
        }
        return defaultTimeoutMillis;
    }
}
