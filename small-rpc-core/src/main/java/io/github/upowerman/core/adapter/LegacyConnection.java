package io.github.upowerman.core.adapter;

import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import io.github.upowerman.core.transport.Connection;
import io.github.upowerman.core.transport.PendingRequests;
import io.github.upowerman.exception.RpcException;
import io.github.upowerman.invoker.RpcInvokerFactory;
import io.github.upowerman.net.base.BaseClient;
import io.github.upowerman.net.base.RpcRequest;

import java.util.concurrent.CompletableFuture;

/**
 * 包装 1.x BaseClient 的连接：long requestId → String，
 * 响应经 BridgingFuture 回到新链路。
 */
final class LegacyConnection implements Connection {

    private final BaseClient client;
    private final RpcInvokerFactory invokerFactory;
    private final PendingRequests pending;
    private final String address;
    private final String version;

    LegacyConnection(BaseClient client, RpcInvokerFactory invokerFactory,
                     PendingRequests pending, String address, String version) {
        this.client = client;
        this.invokerFactory = invokerFactory;
        this.pending = pending;
        this.address = address;
        this.version = version;
    }

    @Override
    public CompletableFuture<Result> request(Invocation invocation) {
        long requestId = pending.nextRequestId();
        CompletableFuture<Result> future = pending.register(requestId);

        RpcRequest request = new RpcRequest();
        request.setRequestId(String.valueOf(requestId));
        request.setCreateMillisTime(System.currentTimeMillis());
        request.setClassName(invocation.serviceName());
        request.setMethodName(invocation.methodName());
        request.setParameterTypes(invocation.parameterTypes());
        request.setParameters(invocation.arguments());
        request.setVersion(version);

        // 构造即注册进 1.x future 池，响应到达时经 setResponse 桥接回来
        new BridgingFuture(invokerFactory, request, requestId, pending);

        try {
            client.asyncSend(address, request);
        } catch (Exception e) {
            pending.complete(requestId, DefaultResult.failure(Status.NETWORK_ERROR,
                    new RpcException("send failed to " + address, e)));
        }
        return future;
    }

    @Override
    public void close() {
        // 1.x 连接池由 RpcInvokerFactory.stop 回调统一关闭
    }
}