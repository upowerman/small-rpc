package io.github.upowerman.core.server;

import io.github.upowerman.core.RpcConstants;
import io.github.upowerman.core.invocation.GenericInvocation;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.protocol.Frame;
import io.github.upowerman.core.protocol.ProtocolStatus;
import io.github.upowerman.core.protocol.RpcRequestBody;
import io.github.upowerman.core.protocol.RpcResponseBody;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import io.github.upowerman.core.serialize.Serializer;
import io.github.upowerman.core.serialize.SerializerRegistry;
import io.github.upowerman.exception.RpcException;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 服务端帧处理器：REQUEST → 业务线程池（反序列化 → Invoker → 序列化）→ RESPONSE；
 * HEARTBEAT 立即回显；未知 type 关连接。业务处理不占用 I/O 线程。
 * <p>
 * 一切失败都以状态码回帧，不抛裸异常：未知 codec / 反序列化失败 / 类型不符 →
 * SERIALIZATION_ERROR，无 provider → SERVICE_NOT_FOUND，Invoker 侧失败按 Result 状态映射。
 */
public class ServerHandler extends SimpleChannelInboundHandler<Frame> {

    private static final Logger logger = LoggerFactory.getLogger(ServerHandler.class);

    private final SerializerRegistry serializers;
    private final Map<String, Invoker> providers;
    private final Executor businessExecutor;

    public ServerHandler(SerializerRegistry serializers, Map<String, Invoker> providers,
                         Executor businessExecutor) {
        this.serializers = serializers;
        this.providers = providers;
        this.businessExecutor = businessExecutor;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, final Frame frame) {
        if (frame.type() == Frame.TYPE_HEARTBEAT) {
            ctx.writeAndFlush(Frame.heartbeat(frame.requestId()));
            return;
        }
        if (frame.type() != Frame.TYPE_REQUEST) {
            ctx.close();
            return;
        }
        final Channel channel = ctx.channel();
        businessExecutor.execute(new Runnable() {
            @Override
            public void run() {
                handleRequest(channel, frame);
            }
        });
    }

    private void handleRequest(Channel channel, Frame frame) {
        Serializer serializer = serializers.find(frame.codec());
        if (serializer == null) {
            writeError(channel, frame, ProtocolStatus.SERIALIZATION_ERROR,
                    "unknown codec: " + frame.codec(), null);
            return;
        }
        RpcRequestBody body;
        try {
            // 委托的 Hessian 反序列化忽略 clazz，转型即类型防线（ClassCastException 在此被捕获）
            body = (RpcRequestBody) serializer.deserialize(frame.body(), RpcRequestBody.class);
        } catch (Exception e) {
            writeError(channel, frame, ProtocolStatus.SERIALIZATION_ERROR,
                    "cannot read request body", e);
            return;
        }
        Invoker invoker = providers.get(body.getServiceName());
        if (invoker == null) {
            writeError(channel, frame, ProtocolStatus.SERVICE_NOT_FOUND,
                    "no provider for " + body.getServiceName(), null);
            return;
        }
        try {
            Invocation invocation = toInvocation(body);
            // P1 服务端 Invoker 全部同步完成（ReflectiveInvoker），get() 即取结果
            Result result = invoker.invoke(invocation).get();
            writeResult(channel, frame, serializer, result);
        } catch (Exception e) {
            writeError(channel, frame, ProtocolStatus.SERVER_ERROR, "server failed to invoke", e);
        }
    }

    private void writeResult(Channel channel, Frame frame, Serializer serializer, Result result) {
        byte status;
        try {
            status = ProtocolStatus.toCode(result.status());
        } catch (IllegalArgumentException e) {
            // 调用方本地态（TIMEOUT 等）不应出现在服务端；兜底按 SERVER_ERROR 回
            status = ProtocolStatus.SERVER_ERROR;
        }
        RpcResponseBody body = new RpcResponseBody();
        if (result.status() == Status.SUCCESS) {
            body.setValue(result.value());
        } else {
            Throwable error = result.exception();
            body.setErrorClassName(error == null ? RpcException.class.getName() : error.getClass().getName());
            body.setErrorMessage(error == null ? String.valueOf(result.status()) : error.getMessage());
        }
        writeBody(channel, frame, serializer, status, body);
    }

    private void writeError(Channel channel, Frame frame, byte status, String message, Throwable cause) {
        RpcResponseBody body = new RpcResponseBody();
        body.setErrorClassName(RpcException.class.getName());
        body.setErrorMessage(cause == null ? message : message + ": " + cause);
        writeBody(channel, frame, serializers.find(frame.codec()), status, body);
    }

    private void writeBody(Channel channel, Frame frame, Serializer serializer, byte status,
                           RpcResponseBody body) {
        byte[] bytes;
        try {
            bytes = serializer == null ? new byte[0] : serializer.serialize(body);
        } catch (Exception e) {
            // 结果本体序列化失败：降级为空 body 的 SERIALIZATION_ERROR 帧，调用方仍能结算
            channel.writeAndFlush(Frame.response(frame.codec(), ProtocolStatus.SERIALIZATION_ERROR,
                    frame.requestId(), new byte[0]));
            return;
        }
        channel.writeAndFlush(Frame.response(frame.codec(), status, frame.requestId(), bytes));
    }

    private static Invocation toInvocation(RpcRequestBody body) throws ClassNotFoundException {
        String[] typeNames = body.getParameterTypes();
        Class<?>[] types = new Class<?>[typeNames == null ? 0 : typeNames.length];
        for (int i = 0; i < types.length; i++) {
            types[i] = Class.forName(typeNames[i]);
        }
        Map<String, Object> attachments = body.getAttachments();
        return new GenericInvocation(body.getServiceName(), body.getMethodName(), types,
                body.getArguments() == null ? new Object[0] : body.getArguments(),
                attachments == null ? new HashMap<String, Object>() : attachments);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        // READER_IDLE = 连续 SERVER_IDLE_SECONDS 无读事件：对端已死（心跳保活失灵），回收半开连接。
        // IdleStateHandler 只发事件不关连接，这里是死连接回收唯一的执行点。
        if (evt instanceof IdleStateEvent && ((IdleStateEvent) evt).state() == IdleState.READER_IDLE) {
            logger.warn("rpc2 server closes idle connection: {} (no read for {}s)",
                    ctx.channel().remoteAddress(), RpcConstants.SERVER_IDLE_SECONDS);
            ctx.close();
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.warn("server channel error, close: {}", cause.toString());
        ctx.close();
    }
}
