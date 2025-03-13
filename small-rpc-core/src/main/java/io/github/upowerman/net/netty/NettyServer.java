package io.github.upowerman.net.netty;

import io.github.upowerman.net.base.BaseServer;
import io.github.upowerman.net.base.Beat;
import io.github.upowerman.net.base.RpcRequest;
import io.github.upowerman.net.base.RpcResponse;
import io.github.upowerman.net.codec.NettyDecoder;
import io.github.upowerman.net.codec.NettyEncoder;
import io.github.upowerman.provider.RpcProviderFactory;
import io.github.upowerman.util.ThreadPoolUtil;
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

        if (thread != null && thread.isAlive()) {
            thread.interrupt();
        }
        // 执行回调函数
        onStop();
        logger.info("RPC服务启动成功");
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
                ServerBootstrap bootstrap = new ServerBootstrap();
                bootstrap.group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel.class)
                        .childHandler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            public void initChannel(SocketChannel channel) throws Exception {
                                channel.pipeline()
                                        // 添加心跳handler
                                        .addLast(new IdleStateHandler(0, 0, Beat.BEAT_INTERVAL * 3, TimeUnit.SECONDS))
                                        .addLast(new NettyDecoder(RpcRequest.class, rpcProviderFactory.getSerializer()))
                                        .addLast(new NettyEncoder(RpcResponse.class, rpcProviderFactory.getSerializer()))
                                        .addLast(new NettyServerHandler(rpcProviderFactory, serverHandlerPool));
                            }
                        })
                        .childOption(ChannelOption.TCP_NODELAY, true)
                        .childOption(ChannelOption.SO_KEEPALIVE, true);

                // 绑定端口
                ChannelFuture future = bootstrap.bind(rpcProviderFactory.getPort()).sync();

                logger.info("服务启动成功 服务类型 = {}, 端口 = {}", NettyServer.class.getName(), rpcProviderFactory.getPort());
                // 启动前执行回调函数
                onStart();

                future.channel().closeFuture().sync();

            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    logger.info("服务停止");
                } else {
                    logger.error("服务错误", e);
                }
            } finally {
                try {
                    // 关闭线程池
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
