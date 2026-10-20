package io.github.upowerman.core.filter;

import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.metrics.RpcMetrics;
import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import io.github.upowerman.core.result.Results;
import io.github.upowerman.exception.RpcException;

import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CompletableFuture;

/**
 * 降级 Filter（3.0 治理）：下游非 SUCCESS 或链路异常 → 调用 fallback 实例的同名方法兜底。
 * 异步方法天然适配——fallback 实现同一接口，future 签名方法的返回值在此解包。
 * fallback 自身失败：状态沿用原失败（throwable 分支 NETWORK_ERROR），原异常进 suppressed。
 */
public class FallbackFilter implements Filter {

    private final Object fallbackInstance;

    public FallbackFilter(Object fallbackInstance) {
        this.fallbackInstance = fallbackInstance;
    }

    @Override
    public CompletableFuture<Result> invoke(Invoker next, Invocation invocation) {
        final CompletableFuture<Result> downstream = new CompletableFuture<Result>();
        next.invoke(invocation).whenComplete((result, error) -> {
            if (error == null && result != null && result.status() == Status.SUCCESS) {
                downstream.complete(result);
                return;
            }
            final Status originalStatus = error == null && result != null
                    ? result.status() : Status.NETWORK_ERROR;
            final RpcException original = error != null
                    ? (error instanceof RpcException ? (RpcException) error : new RpcException(error))
                    : Results.toRpcException(result);
            final String resource = invocation.serviceName() + "#" + invocation.methodName();
            try {
                Object value = fallbackInstance.getClass()
                        .getMethod(invocation.methodName(), invocation.parameterTypes())
                        .invoke(fallbackInstance, invocation.arguments());
                RpcMetrics.getInstance().recordDegraded(resource);
                if (value instanceof CompletableFuture) {
                    ((CompletableFuture<?>) value).whenComplete((v, ft) -> {
                        if (ft != null) {
                            downstream.complete(fallbackFailure(resource, originalStatus, original, ft));
                        } else {
                            downstream.complete(DefaultResult.success(v));
                        }
                    });
                } else {
                    downstream.complete(DefaultResult.success(value));
                }
            } catch (Throwable t) {
                downstream.complete(fallbackFailure(resource, originalStatus, original, t));
            }
        });
        return downstream;
    }

    private static Result fallbackFailure(String resource, Status status,
                                          RpcException original, Throwable fallbackError) {
        Throwable cause = fallbackError instanceof InvocationTargetException
                && fallbackError.getCause() != null ? fallbackError.getCause() : fallbackError;
        RpcException ex = new RpcException("fallback invocation failed for " + resource, cause);
        ex.addSuppressed(original);
        return DefaultResult.failure(status, ex);
    }
}
