package io.github.upowerman.core.server;

import io.github.upowerman.core.RpcConstants;
import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.protocol.FrameDecoder;
import io.github.upowerman.core.protocol.FrameEncoder;
import io.github.upowerman.core.serialize.SerializerRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 2.0 provider 容器：serviceName → Invoker 注册 + Netty 协议栈。
 * 与 1.x NettyServer 完全独立（端口、pipeline、生命周期互不共享），可同进程共存。
 */
public class RpcServer {

    private static final Logger logger = LoggerFactory.getLogger(RpcServer.class);

    private static final int BUSINESS_THREADS = 8;

    private final int port;
    private final SerializerRegistry serializers;
    private final Map<String, Invoker> providers = new ConcurrentHashMap<String, Invoker>();
    private final ExecutorService businessPool;

    private EventLoopGroup boss;
    private EventLoopGroup worker;
    private volatile Channel serverChannel;

    public RpcServer(int port, SerializerRegistry serializers) {
        this.port = port;
        this.serializers = serializers;
        this.businessPool = Executors.newFixedThreadPool(BUSINESS_THREADS, new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "rpc2-server-biz-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
    }

    public RpcServer register(String serviceName, Invoker invoker) {
        providers.put(serviceName, invoker);
        return this;
    }

    public void start() throws InterruptedException {
        boss = new NioEventLoopGroup(1);
        worker = new NioEventLoopGroup();
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new IdleStateHandler(RpcConstants.SERVER_IDLE_SECONDS, 0, 0))
                                .addLast(new FrameDecoder())
                                .addLast(new FrameEncoder())
                                .addLast(new ServerHandler(serializers, providers, businessPool));
                    }
                })
                .childOption(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.SO_BACKLOG, 256);
        // bind().sync() 返回即端口已监听，调用方无需再轮询探活
        serverChannel = bootstrap.bind(port).sync().channel();
        logger.info("rpc2 server started on port {}", port);
    }

    /** 幂等关闭：连接 → 事件循环 → 业务线程池 */
    public void shutdown() {
        try {
            if (serverChannel != null) {
                serverChannel.close().syncUninterruptibly();
            }
        } finally {
            if (boss != null) {
                boss.shutdownGracefully(0, 5, TimeUnit.SECONDS);
            }
            if (worker != null) {
                worker.shutdownGracefully(0, 5, TimeUnit.SECONDS);
            }
            businessPool.shutdown();
        }
        logger.info("rpc2 server shut down");
    }
}
