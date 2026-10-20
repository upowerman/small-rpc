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
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 2.0 provider 容器：serviceName → Invoker 注册 + Netty 协议栈。
 * 与 1.x NettyServer 完全独立（端口、pipeline、生命周期互不共享），可同进程共存。
 * <p>
 * 业务线程池（B4）可配置：线程数与有界队列容量。队列打满时 CallerRuns 让提交线程
 * （Netty IO 线程）亲自执行——天然背压且不丢已接收的请求，优于静默拒绝。
 */
public class RpcServer {

    private static final Logger logger = LoggerFactory.getLogger(RpcServer.class);

    /** 历史缺省业务线程数 */
    static final int DEFAULT_BUSINESS_THREADS = 8;

    /** queueCapacity &lt;= 0 表示无界队列 */
    static final int UNBOUNDED_QUEUE = Integer.MAX_VALUE;

    /** 缺省停机排空上限（毫秒） */
    static final long DEFAULT_DRAIN_TIMEOUT_MILLIS = 10000L;

    private final int port;
    private final SerializerRegistry serializers;
    private final Map<String, Invoker> providers = new ConcurrentHashMap<String, Invoker>();
    private final ExecutorService businessPool;
    /** 在途业务请求计数：提交 +1、完成 -1；停机排空据此等待 */
    private final AtomicInteger inFlight = new AtomicInteger();
    private final long drainTimeoutMillis;

    private EventLoopGroup boss;
    private EventLoopGroup worker;
    private volatile Channel serverChannel;
    /** 停机幂等守卫 */
    private final java.util.concurrent.atomic.AtomicBoolean shutdownOnce =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public RpcServer(int port, SerializerRegistry serializers) {
        this(port, serializers, DEFAULT_BUSINESS_THREADS, 0, DEFAULT_DRAIN_TIMEOUT_MILLIS);
    }

    public RpcServer(int port, SerializerRegistry serializers,
                     int businessThreads, int businessQueueCapacity) {
        this(port, serializers, businessThreads, businessQueueCapacity, DEFAULT_DRAIN_TIMEOUT_MILLIS);
    }

    public RpcServer(int port, SerializerRegistry serializers,
                     int businessThreads, int businessQueueCapacity, long drainTimeoutMillis) {
        this.port = port;
        this.serializers = serializers;
        this.businessPool = createBusinessExecutor(businessThreads, businessQueueCapacity);
        this.drainTimeoutMillis = drainTimeoutMillis > 0 ? drainTimeoutMillis : 0L;
    }

    /** 线程数 &lt;= 1 用单线程池；容量 &gt; 0 有界 + CallerRuns（背压），否则无界 */
    static ExecutorService createBusinessExecutor(int businessThreads, int businessQueueCapacity) {
        int threads = businessThreads > 0 ? businessThreads : DEFAULT_BUSINESS_THREADS;
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "rpc2-server-biz-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        if (threads == 1) {
            return Executors.newSingleThreadExecutor(factory);
        }
        int capacity = businessQueueCapacity > 0 ? businessQueueCapacity : UNBOUNDED_QUEUE;
        return new java.util.concurrent.ThreadPoolExecutor(
                threads, threads, 0L, TimeUnit.MILLISECONDS,
                new java.util.concurrent.LinkedBlockingQueue<Runnable>(capacity),
                factory,
                new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
    }

    public RpcServer register(String serviceName, Invoker invoker) {
        providers.put(serviceName, invoker);
        return this;
    }

    public void start() throws InterruptedException {
        if (serverChannel != null) {
            // 重复 start 会静默覆盖 boss/worker，泄漏上一组事件循环线程——显式失败
            throw new IllegalStateException("rpc2 server already started on port " + port);
        }
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
                                .addLast(new ServerHandler(serializers, providers, inFlightExecutor()));
                    }
                })
                .childOption(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.SO_BACKLOG, 256);
        try {
            // bind().sync() 返回即端口已监听，调用方无需再轮询探活
            serverChannel = bootstrap.bind(port).sync().channel();
        } catch (final Exception e) {
            // bind 失败（端口占用等）时两组事件循环已创建，必须释放后重抛，否则泄漏线程。
            // Netty 对 bind 失败的受检异常（如 BindException）经 sync() sneaky-throw 原样抛出；
            // precise rethrow 让编译器仍按 try 块可抛类型（InterruptedException/RuntimeException）
            // 看待 throw e，运行期则任何异常原样透传——保留原异常语义。
            boss.shutdownGracefully(0, 5, TimeUnit.SECONDS);
            worker.shutdownGracefully(0, 5, TimeUnit.SECONDS);
            throw e;
        }
        logger.info("rpc2 server started on port {}", port);
    }

    /**
     * 优雅停机（B5）：停接新连接 → 等待在途业务请求排空（在途归零，上限
     * {@code drainTimeoutMillis}；期间新到请求同样计数并延续排空）→ 收尾业务池
     * （已提交任务执行完毕）→ 关闭事件循环与全部子连接。幂等：重复调用直接返回——
     * 事件循环终止后再对僵尸 channel 发起 close 会被 Netty 以 RejectedExecutionException 拒绝。
     */
    public void shutdown() {
        if (!shutdownOnce.compareAndSet(false, true)) {
            return;
        }
        try {
            if (serverChannel != null) {
                serverChannel.close().syncUninterruptibly();
            }
        } finally {
            drainInFlight();
            businessPool.shutdown();
            try {
                businessPool.awaitTermination(drainTimeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (boss != null) {
                boss.shutdownGracefully(0, 5, TimeUnit.SECONDS);
            }
            if (worker != null) {
                worker.shutdownGracefully(0, 5, TimeUnit.SECONDS);
            }
        }
        logger.info("rpc2 server shut down");
    }

    /** 在途计数包装：提交 +1、完成/拒绝 -1，任何路径不泄漏计数 */
    private Executor inFlightExecutor() {
        return new Executor() {
            @Override
            public void execute(final Runnable command) {
                inFlight.incrementAndGet();
                try {
                    businessPool.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                command.run();
                            } finally {
                                inFlight.decrementAndGet();
                            }
                        }
                    });
                } catch (Throwable t) {
                    inFlight.decrementAndGet();
                    throw t;
                }
            }
        };
    }

    private void drainInFlight() {
        if (drainTimeoutMillis <= 0) {
            return;
        }
        long deadline = System.currentTimeMillis() + drainTimeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (inFlight.get() == 0) {
                return;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        logger.warn("rpc2 server drain timeout after {}ms, {} request(s) still in flight",
                drainTimeoutMillis, inFlight.get());
    }
}
