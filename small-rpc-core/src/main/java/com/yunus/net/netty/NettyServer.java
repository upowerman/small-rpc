package com.yunus.net.netty;

import com.yunus.net.base.BaseServer;
import com.yunus.net.base.Beat;
import com.yunus.net.base.RpcRequest;
import com.yunus.net.base.RpcResponse;
import com.yunus.net.codec.NettyDecoder;
import com.yunus.net.codec.NettyEncoder;
import com.yunus.provider.RpcProviderFactory;
import com.yunus.util.ThreadPoolUtil;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author gaoyunfeng
 */
public class NettyServer extends BaseServer {

    private Thread thread;

    @Override
    public void start(final RpcProviderFactory rpcProviderFactory) throws Exception {

        thread = new Thread(new BootStrap(rpcProviderFactory));
        thread.setDaemon(true);
        thread.start();

    }

    @Override
    public void stop() throws Exception {

        // destroy server thread
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
        }

        // on stop
        onStoped();
        logger.info(">>>>>>>>>>> rpc remoting server destroy success.");
    }

    class BootStrap implements Runnable {

        private RpcProviderFactory rpcProviderFactory;

        public BootStrap(RpcProviderFactory rpcProviderFactory) {
            this.rpcProviderFactory = rpcProviderFactory;
        }

        @Override
        public void run() {
            final ThreadPoolExecutor serverHandlerPool = ThreadPoolUtil.makeServerThreadPool(
                    NettyServer.class.getSimpleName(),
                    rpcProviderFactory.getCorePoolSize(),
                    rpcProviderFactory.getMaxPoolSize());
            EventLoopGroup bossGroup = new NioEventLoopGroup();
            EventLoopGroup workerGroup = new NioEventLoopGroup();

            try {
                // start server
                ServerBootstrap bootstrap = new ServerBootstrap();
                bootstrap.group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel.class)
                        .childHandler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            public void initChannel(SocketChannel channel) throws Exception {
                                channel.pipeline()
                                        .addLast(new IdleStateHandler(0, 0, Beat.BEAT_INTERVAL * 3, TimeUnit.SECONDS))
                                        .addLast(new NettyDecoder(RpcRequest.class, rpcProviderFactory.getSerializer()))
                                        .addLast(new NettyEncoder(RpcResponse.class, rpcProviderFactory.getSerializer()))
                                        .addLast(new NettyServerHandler(rpcProviderFactory, serverHandlerPool));
                            }
                        })
                        .childOption(ChannelOption.TCP_NODELAY, true)
                        .childOption(ChannelOption.SO_KEEPALIVE, true);

                // bind
                ChannelFuture future = bootstrap.bind(rpcProviderFactory.getPort()).sync();

                logger.info(">>>>>>>>>>> rpc remoting server start success, nettype = {}, port = {}", NettyServer.class.getName(), rpcProviderFactory.getPort());
                onStarted();

                // wait util stop
                future.channel().closeFuture().sync();

            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    logger.info(">>>>>>>>>>> rpc remoting server stop.");
                } else {
                    logger.error(">>>>>>>>>>> rpc remoting server error.", e);
                }
            } finally {
                try {
                    serverHandlerPool.shutdown();
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
                try {
                    workerGroup.shutdownGracefully();
                    bossGroup.shutdownGracefully();
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
            }
        }
    }
}
