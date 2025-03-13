package io.github.upowerman.net.netty;

import io.github.upowerman.invoker.RpcInvokerFactory;
import io.github.upowerman.net.base.Beat;
import io.github.upowerman.net.base.RpcResponse;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author gaoyunfeng
 */
public class NettyClientHandler extends SimpleChannelInboundHandler<RpcResponse> {
    private static final Logger logger = LoggerFactory.getLogger(NettyClientHandler.class);


    private RpcInvokerFactory rpcInvokerFactory;
    private NettyConnectClient nettyConnectClient;

    public NettyClientHandler(final RpcInvokerFactory rpcInvokerFactory, NettyConnectClient nettyConnectClient) {
        this.rpcInvokerFactory = rpcInvokerFactory;
        this.nettyConnectClient = nettyConnectClient;
    }


    @Override
    protected void channelRead0(ChannelHandlerContext ctx, RpcResponse response) throws Exception {
        rpcInvokerFactory.notifyInvokerFuture(response.getRequestId(), response);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        ctx.close();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            nettyConnectClient.send(Beat.BEAT_PING);
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

}
