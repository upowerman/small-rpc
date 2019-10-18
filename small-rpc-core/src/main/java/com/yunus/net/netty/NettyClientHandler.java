package com.yunus.net.netty;

import com.yunus.invoker.RpcInvokerFactory;
import com.yunus.net.base.Beat;
import com.yunus.net.base.RpcResponse;
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
    protected void channelRead0(ChannelHandlerContext ctx, RpcResponse xxlRpcResponse) throws Exception {
        rpcInvokerFactory.notifyInvokerFuture(xxlRpcResponse.getRequestId(), xxlRpcResponse);
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
