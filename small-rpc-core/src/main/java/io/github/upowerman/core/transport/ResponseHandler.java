package io.github.upowerman.core.transport;

import io.github.upowerman.core.protocol.Frame;
import io.github.upowerman.core.protocol.ProtocolStatus;
import io.github.upowerman.core.protocol.RpcResponseBody;
import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import io.github.upowerman.core.serialize.Serializer;
import io.github.upowerman.exception.RpcException;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * 客户端入站帧处理：RESPONSE → 反序列化 → 按 requestId 结算 PendingRequests；
 * HEARTBEAT 忽略；未知 type 关连接（流不可信）。
 * 反序列化在 I/O 线程执行（Hessian 微秒级，P1 不引入额外线程切换）；
 * 任何解析失败都以 SERIALIZATION_ERROR 结算——in-flight 条目绝不悬挂。
 */
class ResponseHandler extends SimpleChannelInboundHandler<Frame> {

    private final PendingRequests pending;
    private final Serializer serializer;

    ResponseHandler(PendingRequests pending, Serializer serializer) {
        this.pending = pending;
        this.serializer = serializer;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Frame frame) {
        if (frame.type() == Frame.TYPE_HEARTBEAT) {
            return;
        }
        if (frame.type() != Frame.TYPE_RESPONSE) {
            ctx.close();
            return;
        }
        pending.complete(frame.requestId(), decodeResult(frame));
    }

    private Result decodeResult(Frame frame) {
        try {
            // 委托的 Hessian 忽略 clazz，转型即类型防线
            RpcResponseBody body = (RpcResponseBody) serializer.deserialize(frame.body(), RpcResponseBody.class);
            if (frame.status() == ProtocolStatus.SUCCESS) {
                return DefaultResult.success(body.getValue());
            }
            return DefaultResult.failure(ProtocolStatus.fromCode(frame.status()), rebuildException(body));
        } catch (Exception e) {
            // 反序列化失败 / 未知状态码 / 类型不符：SERIALIZATION_ERROR 结算
            return DefaultResult.failure(Status.SERIALIZATION_ERROR, e);
        }
    }

    private static Throwable rebuildException(RpcResponseBody body) {
        String className = body.getErrorClassName();
        String message = body.getErrorMessage();
        return new RpcException(className == null ? String.valueOf(message) : className + ": " + message);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }
}
