package io.github.upowerman.core.adapter;

import io.github.upowerman.core.RpcConstants;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import io.github.upowerman.core.transport.Connection;
import io.github.upowerman.core.transport.PendingRequests;
import io.github.upowerman.exception.RpcException;
import io.github.upowerman.invoker.RpcInvokerFactory;
import io.github.upowerman.net.base.ConnectClient;
import io.github.upowerman.net.base.RpcRequest;

import java.util.concurrent.CompletableFuture;

/**
 * 包装 1.x ConnectClient 的一条连接：long requestId → String，
 * 响应经 BridgingFuture 回到新链路。
 * <p>
 * 本连接<b>拥有</b> in-flight 条目生命周期：返回给调用方的 future 在其完成、
 * 失败或超时时，一定同时满足：{@link PendingRequests} 移除条目、1.x
 * {@link RpcInvokerFactory} future 池移除 BridgingFuture——超时由
 * {@link PendingRequests#register(long, long)} 自行兜底，发送抛异常由
 * {@link #request(Invocation)} 直接结算捕获，两者都不再泄漏。
 */
final class LegacyConnection implements Connection {

    private final ConnectClient client;
    private final RpcInvokerFactory invokerFactory;
    private final PendingRequests pending;
    private final long defaultTimeoutMillis;
    private final String address;
    private final String version;

    LegacyConnection(ConnectClient client, RpcInvokerFactory invokerFactory,
                     PendingRequests pending, long defaultTimeoutMillis,
                     String address, String version) {
        this.client = client;
        this.invokerFactory = invokerFactory;
        this.pending = pending;
        this.defaultTimeoutMillis = defaultTimeoutMillis;
        this.address = address;
        this.version = version;
    }

    @Override
    public CompletableFuture<Result> request(Invocation invocation) {
        long requestId = pending.nextRequestId();
        long timeoutMillis = resolveTimeout(invocation);
        CompletableFuture<Result> future = pending.register(requestId, timeoutMillis);

        RpcRequest request = new RpcRequest();
        request.setRequestId(String.valueOf(requestId));
        request.setCreateMillisTime(System.currentTimeMillis());
        request.setClassName(invocation.serviceName());
        request.setMethodName(invocation.methodName());
        request.setParameterTypes(invocation.parameterTypes());
        request.setParameters(invocation.arguments());
        request.setVersion(version);

        // 构造即注册进 1.x future 池，响应到达时经 setResponse 桥接回来
        BridgingFuture bridgingFuture = new BridgingFuture(invokerFactory, request, requestId, pending);

        // 结算路径统一清理两张表，保证完成/失败/超时都必然移除条目
        CompletableFuture<Result> settled = future.handle((result, error) -> {
            bridgingFuture.removeInvokerFuture();
            pending.remove(requestId);
            if (error != null) {
                return DefaultResult.failure(Status.NETWORK_ERROR, error);
            }
            return result;
        });

        try {
            client.send(request);
        } catch (Exception e) {
            pending.complete(requestId, DefaultResult.failure(Status.NETWORK_ERROR,
                    new RpcException("send failed to " + address, e)));
        }
        return settled;
    }

    private long resolveTimeout(Invocation invocation) {
        Object timeout = invocation.attachments().get(RpcConstants.ATTACH_TIMEOUT);
        if (timeout instanceof Long && ((Long) timeout) > 0) {
            return (Long) timeout;
        }
        // defaultTimeoutMillis 已在构造时保证为正，兜底条目绝不无限滞留
        return defaultTimeoutMillis;
    }

    @Override
    public void close() {
        // 连接由 LegacyNettyTransport 的连接池统一关闭
    }
}
