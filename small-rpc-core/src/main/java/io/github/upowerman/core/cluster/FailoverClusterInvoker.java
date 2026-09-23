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
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Failover 集群容错：目录查询 → 负载均衡选实例 → 远程调用 →
 * 按状态码决定是否换实例重试。
 * <p>
 * <b>超时语义（P0）</b>：
 * <ul>
 *   <li>budget &gt; 0（{@code defaultTimeoutMillis} 或 attachments 里
 *       {@link RpcConstants#ATTACH_TIMEOUT} 的覆盖值）→ <b>deadline 模式</b>：该预算是一次调用的
 *       总预算，由 {@code retries + 1} 次尝试共享；每次尝试拿到"剩余预算"（{@code deadline - now}），
 *       剩余预算非正时不再发起尝试、直接以 TIMEOUT 结算。调用总耗时不会突破预算。</li>
 *   <li>budget &lt;= 0 → <b>显式"不设超时"</b>：本层不施加 deadline（也绝不会把非正值传给
 *       {@link Futures#withTimeout} 造成"意外不超时"）；传输层以其自身默认超时兜底
 *       in-flight 条目，最终调用方由 RpcProxyFactory 的 bounded get 兜底，任何路径都不会永久悬挂。</li>
 * </ul>
 * <p>
 * 重试延续（{@code whenComplete}）通过专用 {@link #RETRY_EXECUTOR} 从上游完成线程
 * （真实响应时是 Netty 事件循环线程）跳出，避免在事件循环上重入阻塞的
 * {@code writeAndFlush().sync()}；延续体整体 try/catch，任何 Throwable 都必然结算 root，
 * 调用方（如 RpcProxyFactory 的 get）不会悬挂。
 * 熔断留给 3.0 —— 状态码模型已为其预留决策依据。
 */
public class FailoverClusterInvoker implements Invoker {

    /** 无期限哨兵：budget &lt;= 0 显式表示"不设超时" */
    private static final long NO_DEADLINE = -1L;

    /** 重试延续专用线程池：非事件循环线程，运行阻塞发送与结果结算。守护线程，进程退出不阻塞。 */
    private static final Executor RETRY_EXECUTOR = Executors.newFixedThreadPool(4, new ThreadFactory() {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "small-rpc-retry-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

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
        long budget = resolveTimeout(invocation);
        long deadline = budget > 0 ? System.currentTimeMillis() + budget : NO_DEADLINE;
        try {
            attempt(invocation, instances, 0, root, deadline);
        } catch (Throwable t) {
            // 首次尝试同步阶段的任何异常也保证 root 结算，调用方 get 不悬挂
            root.complete(DefaultResult.failure(Status.NETWORK_ERROR, t));
        }
        return root;
    }

    private void attempt(final Invocation invocation, final List<ServiceInstance> instances,
                         final int attempt, final CompletableFuture<Result> root, final long deadline) {
        final long remaining;
        if (deadline == NO_DEADLINE) {
            // 显式"不设超时"：0 交给 Futures.withTimeout 表示直通；传输层用自身默认兜底
            remaining = 0L;
        } else {
            remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                // 预算耗尽：不再发起尝试（也不会把非正超时传给 Futures.withTimeout）
                root.complete(DefaultResult.failure(Status.TIMEOUT,
                        new RpcException("call deadline exceeded before attempt " + attempt)));
                return;
            }
        }

        ServiceInstance instance = loadBalancer.select(instances, invocation);
        Map<String, Object> attachments = new HashMap<String, Object>(invocation.attachments());
        attachments.put(RpcConstants.ATTACH_ADDRESS, instance.getAddress());
        // 把剩余预算写入本次尝试，供传输层（PendingRequests 兜底超时）使用；不设超时则不加
        if (remaining > 0) {
            attachments.put(RpcConstants.ATTACH_TIMEOUT, remaining);
        }
        Invocation attemptInvocation = new GenericInvocation(invocation.serviceName(),
                invocation.methodName(), invocation.parameterTypes(), invocation.arguments(),
                attachments);

        final long timeoutMillis = remaining;
        CompletableFuture<Result> future = Futures.withTimeout(
                remoteInvoker.invoke(attemptInvocation), timeoutMillis,
                new Supplier<Result>() {
                    @Override
                    public Result get() {
                        return DefaultResult.failure(Status.TIMEOUT,
                                new RpcException("timeout after " + timeoutMillis + "ms"));
                    }
                });

        future.whenCompleteAsync((result, error) -> {
            try {
                if (error != null) {
                    result = DefaultResult.failure(Status.NETWORK_ERROR, error);
                }
                if (attempt + 1 <= retries && isRetryable(result.status())) {
                    attempt(invocation, instances, attempt + 1, root, deadline);
                } else {
                    root.complete(result);
                }
            } catch (Throwable t) {
                // 延续体内任何异常都兜底结算 root，绝不悬挂
                root.complete(DefaultResult.failure(Status.NETWORK_ERROR, t));
            }
        }, RETRY_EXECUTOR);
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
