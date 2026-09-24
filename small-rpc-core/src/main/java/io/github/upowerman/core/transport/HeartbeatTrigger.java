package io.github.upowerman.core.transport;

import io.github.upowerman.core.protocol.Frame;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleStateEvent;

/**
 * 写空闲即发心跳帧（协议一等公民，type=HEARTBEAT，无 body）。
 * 服务端读空闲 3 倍间隔判定死连接，见 RpcConstants.SERVER_IDLE_SECONDS。
 */
class HeartbeatTrigger extends ChannelDuplexHandler {

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            ctx.writeAndFlush(Frame.heartbeat(0L));
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }
}
