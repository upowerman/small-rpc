package io.github.upowerman.core.cluster;

import io.github.upowerman.core.RpcConstants;
import io.github.upowerman.core.directory.ServiceDirectory;
import io.github.upowerman.core.directory.ServiceInstance;
import io.github.upowerman.core.invocation.GenericInvocation;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.loadbalance.LoadBalancer;
import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import io.github.upowerman.core.transport.Futures;
import io.github.upowerman.exception.RpcException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Failover 集群容错：目录查询 → 负载均衡选实例 → 远程调用 →
 * 按状态码决定是否换实例重试；超时统一由本层施加。
 * 熔断留给 3.0 —— 状态码模型已为其预留决策依据。
 */
public class FailoverClusterInvoker implements Invoker {

    private final ServiceDirectory directory;
    private final LoadBalancer loadBalancer;
    private final Invoker remoteInvoker;
    private final int retries;
    private final long defaultTimeoutMillis;

    public FailoverClusterInvoker(ServiceDirectory directory, LoadBalancer loadBalancer,
                                  Invoker remoteInvoker, int retries, long defaultTimeoutMillis) {
        this.directory = directory;
        this.loadBalancer = loadBalancer;
        this.remoteInvoker = remoteInvoker;
        this.retries = retries;
        this.defaultTimeoutMillis = defaultTimeoutMillis;
    }

    @Override
    public Class<?> interfaceClass() {
        return remoteInvoker.interfaceClass();
    }

    @Override
    public CompletableFuture<Result> invoke(Invocation invocation) {
        List<ServiceInstance> instances = directory.list(invocation.serviceName());
        if (instances == null || instances.isEmpty()) {
            return CompletableFuture.completedFuture(DefaultResult.failure(Status.SERVICE_NOT_FOUND,
                    new RpcException("no provider for " + invocation.serviceName())));
        }
        CompletableFuture<Result> root = new CompletableFuture<Result>();
        attempt(invocation, instances, 0, root);
        return root;
    }

    private void attempt(Invocation invocation, List<ServiceInstance> instances,
                         int attempt, CompletableFuture<Result> root) {
        ServiceInstance instance = loadBalancer.select(instances, invocation);
        Map<String, Object> attachments = new HashMap<String, Object>(invocation.attachments());
        attachments.put(RpcConstants.ATTACH_ADDRESS, instance.getAddress());
        Invocation attemptInvocation = new GenericInvocation(invocation.serviceName(),
                invocation.methodName(), invocation.parameterTypes(), invocation.arguments(),
                attachments);

        long timeoutMillis = resolveTimeout(attemptInvocation);
        CompletableFuture<Result> future = Futures.withTimeout(
                remoteInvoker.invoke(attemptInvocation), timeoutMillis,
                new java.util.function.Supplier<Result>() {
                    @Override
                    public Result get() {
                        return DefaultResult.failure(Status.TIMEOUT,
                                new RpcException("timeout after " + timeoutMillis + "ms"));
                    }
                });

        future.whenComplete((result, error) -> {
            if (error != null) {
                result = DefaultResult.failure(Status.NETWORK_ERROR, error);
            }
            if (attempt + 1 <= retries && isRetryable(result.status())) {
                attempt(invocation, instances, attempt + 1, root);
            } else {
                root.complete(result);
            }
        });
    }

    private long resolveTimeout(Invocation invocation) {
        Object timeout = invocation.attachments().get(RpcConstants.ATTACH_TIMEOUT);
        if (timeout instanceof Long && ((Long) timeout) > 0) {
            return (Long) timeout;
        }
        return defaultTimeoutMillis;
    }

    private boolean isRetryable(Status status) {
        return status == Status.TIMEOUT
                || status == Status.NETWORK_ERROR
                || status == Status.SERVER_ERROR;
    }
}
