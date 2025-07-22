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
            try {
                // Wait for graceful shutdown
                thread.join(5000);
            } catch (InterruptedException e) {
                logger.warn("Interrupted while waiting for server thread to stop");
                Thread.currentThread().interrupt();
            }
        }
        // 执行回调函数
        onStop();
        logger.info("RPC服务停止成功");
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
            EventLoopGroup bossGroup = new NioEventLoopGroup(1); // Use single thread for boss group
            EventLoopGroup workerGroup = new NioEventLoopGroup(); // Use default threads for worker group

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
                        .childOption(ChannelOption.SO_KEEPALIVE, true)
                        .childOption(ChannelOption.SO_REUSEADDR, true)
                        .option(ChannelOption.SO_BACKLOG, 1024);

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
                // Graceful shutdown with timeout
                shutdownGracefully(serverHandlerPool, workerGroup, bossGroup);
            }
        }

        private void shutdownGracefully(ThreadPoolExecutor serverHandlerPool, EventLoopGroup workerGroup, EventLoopGroup bossGroup) {
            try {
                // Shutdown thread pool first
                if (serverHandlerPool != null) {
                    serverHandlerPool.shutdown();
                    if (!serverHandlerPool.awaitTermination(10, TimeUnit.SECONDS)) {
                        logger.warn("Server handler pool did not terminate gracefully, forcing shutdown");
                        serverHandlerPool.shutdownNow();
                    }
                }
            } catch (Exception e) {
                logger.error("Error shutting down server handler pool", e);
            }

            try {
                // Shutdown event loop groups
                if (workerGroup != null) {
                    workerGroup.shutdownGracefully(0, 10, TimeUnit.SECONDS);
                }
                if (bossGroup != null) {
                    bossGroup.shutdownGracefully(0, 10, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                logger.error("Error shutting down event loop groups", e);
            }
        }
    }
}
