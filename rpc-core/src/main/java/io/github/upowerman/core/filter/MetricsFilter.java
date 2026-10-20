package io.github.upowerman.core.filter;

import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.metrics.RpcMetrics;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;

import java.util.concurrent.CompletableFuture;

/**
 * 调用指标 Filter（C8）：消费端链路上按 {@code service#method} 计数与计时。
 * 计时覆盖整条下游链路（Cluster 容错重试含在内），调用完成（成功/失败/异常）即记录；
 * 不改变调用结果，记录本身失败（理论上只有 key 为 null 的防御分支）不传播。
 */
public class MetricsFilter implements Filter {

    private final RpcMetrics metrics = RpcMetrics.getInstance();

    @Override
    public CompletableFuture<Result> invoke(Invoker next, Invocation invocation) {
        final String key = invocation.serviceName() + "#" + invocation.methodName();
        final long startNanos = System.nanoTime();
        return next.invoke(invocation).whenComplete((result, error) -> {
            boolean success = error == null && result != null && result.status() == Status.SUCCESS;
            metrics.record(key, success, System.nanoTime() - startNanos);
        });
    }
}
