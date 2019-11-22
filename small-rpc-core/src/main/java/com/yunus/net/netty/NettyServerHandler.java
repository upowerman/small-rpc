package com.yunus.net.netty;


import com.yunus.net.base.Beat;
import com.yunus.net.base.RpcRequest;
import com.yunus.net.base.RpcResponse;
import com.yunus.provider.RpcProviderFactory;
import com.yunus.util.ThrowableUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadPoolExecutor;


/**
 * @author gaoyunfeng
 */
public class NettyServerHandler extends SimpleChannelInboundHandler<RpcRequest> {

    private static final Logger logger = LoggerFactory.getLogger(NettyServerHandler.class);

    private RpcProviderFactory rpcProviderFactory;
    private ThreadPoolExecutor serverHandlerPool;

    public NettyServerHandler(final RpcProviderFactory rpcProviderFactory, final ThreadPoolExecutor serverHandlerPool) {
        this.rpcProviderFactory = rpcProviderFactory;
        this.serverHandlerPool = serverHandlerPool;
    }


    @Override
    public void channelRead0(final ChannelHandlerContext ctx, final RpcRequest rpcRequest) throws Exception {

        // 过滤掉心跳数据包
        if (Beat.BEAT_ID.equalsIgnoreCase(rpcRequest.getRequestId())) {
            logger.debug("服务提供者 接收到心跳");
            return;
        }

        try {
            // 执行远程调用
            serverHandlerPool.execute(new Runnable() {
                @Override
                public void run() {

                    RpcResponse rpcResponse = rpcProviderFactory.invokeService(rpcRequest);
                    // 执行结果回写给调用方
                    ctx.writeAndFlush(rpcResponse);
                }
            });
        } catch (Exception e) {
            RpcResponse rpcResponse = new RpcResponse();
            rpcResponse.setRequestId(rpcRequest.getRequestId());
            rpcResponse.setErrorMsg(ThrowableUtil.toString(e));

            ctx.writeAndFlush(rpcResponse);
        }

    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("服务提供方异常--->", cause);
        ctx.close();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            ctx.channel().close();
            logger.debug("服务提供者关闭--->idle channel.");
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

}
