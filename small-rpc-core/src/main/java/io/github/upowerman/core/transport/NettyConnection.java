package io.github.upowerman.core.transport;

import io.github.upowerman.core.RpcConstants;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.protocol.Frame;
import io.github.upowerman.core.protocol.RpcRequestBody;
import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import io.github.upowerman.core.serialize.Serializer;
import io.github.upowerman.exception.RpcException;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.util.concurrent.GenericFutureListener;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 一条 2.0 协议连接：Invocation → 协议帧，响应帧经 ResponseHandler 回来。
 * 结算路径统一清理：超时由 PendingRequests 兜底驱逐；send 失败当场结算；
 * 序列化失败以 SERIALIZATION_ERROR 结算——任何路径都不泄漏 in-flight 条目。
 */
final class NettyConnection implements Connection {

    private final Channel channel;
    private final PendingRequests pending;
    private final Serializer serializer;
    private final long defaultTimeoutMillis;
    private final String address;

    NettyConnection(Channel channel, PendingRequests pending, Serializer serializer,
                    long defaultTimeoutMillis, String address) {
        this.channel = channel;
        this.pending = pending;
        this.serializer = serializer;
        this.defaultTimeoutMillis = defaultTimeoutMillis;
        this.address = address;
    }

    @Override
    public CompletableFuture<Result> request(Invocation invocation) {
        long requestId = pending.nextRequestId();
        final CompletableFuture<Result> future = pending.register(requestId, resolveTimeout(invocation));

        byte[] bodyBytes;
        try {
            bodyBytes = serializer.serialize(toBody(invocation));
        } catch (Exception e) {
            pending.complete(requestId, DefaultResult.failure(Status.SERIALIZATION_ERROR,
                    new RpcException("serialize request failed", e)));
            return future;
        }

        channel.writeAndFlush(Frame.request(serializer.typeId(), requestId, bodyBytes))
                .addListener(new GenericFutureListener<ChannelFuture>() {
                    @Override
                    public void operationComplete(ChannelFuture f) {
                        if (!f.isSuccess()) {
                            // 连接失效/发送失败：NETWORK_ERROR 当场结算（register 的超时任务随后取消）
                            pending.complete(requestId, DefaultResult.failure(Status.NETWORK_ERROR,
                                    new RpcException("send failed to " + address, f.cause())));
                        }
                    }
                });
        return future;
    }

    private long resolveTimeout(Invocation invocation) {
        Object timeout = invocation.attachments().get(RpcConstants.ATTACH_TIMEOUT);
        if (timeout instanceof Long && ((Long) timeout) > 0) {
            return (Long) timeout;
        }
        return defaultTimeoutMillis;
    }

    private RpcRequestBody toBody(Invocation invocation) {
        RpcRequestBody body = new RpcRequestBody();
        body.setServiceName(invocation.serviceName());
        body.setMethodName(invocation.methodName());
        Class<?>[] types = invocation.parameterTypes();
        String[] typeNames = new String[types == null ? 0 : types.length];
        for (int i = 0; i < typeNames.length; i++) {
            typeNames[i] = types[i].getName();
        }
        body.setParameterTypes(typeNames);
        body.setArguments(invocation.arguments());
        Map<String, Object> attachments = invocation.attachments();
        body.setAttachments(attachments == null ? new HashMap<String, Object>() : attachments);
        return body;
    }

    @Override
    public void close() {
        // Channel 由 NettyTransport 的连接池统一管理关闭
    }
}
