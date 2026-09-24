package io.github.upowerman.core.transport;

import io.github.upowerman.core.RpcConstants;
import io.github.upowerman.core.protocol.FrameDecoder;
import io.github.upowerman.core.protocol.FrameEncoder;
import io.github.upowerman.core.serialize.Serializer;
import io.github.upowerman.exception.RpcException;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * 2.0 自研协议 transport（client 端）：自持 per-address Channel 池与 Netty 客户端，
 * 与 1.x 的进程级静态 ConnectClient 池完全隔离（C2 修复的延续），
 * 因此 1.x 与 2.0 客户端可同进程、同地址共存而互不串话。
 */
public class NettyTransport implements Transport {

    /** 兜底默认单请求超时（毫秒）；调用未显式携带 ATTACH_TIMEOUT 时使用 */
    public static final long DEFAULT_REQUEST_TIMEOUT_MILLIS = 30000L;

    private static final int CONNECT_TIMEOUT_MILLIS = 3000;

    private final Serializer serializer;
    private final PendingRequests pending = new PendingRequests();
    private final long defaultTimeoutMillis;
    private final ConcurrentMap<String, Channel> channels = new ConcurrentHashMap<String, Channel>();
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<String, Object>();
    private final EventLoopGroup group = new NioEventLoopGroup();
    private volatile boolean closed;

    public NettyTransport(Serializer serializer) {
        this(serializer, DEFAULT_REQUEST_TIMEOUT_MILLIS);
    }

    public NettyTransport(Serializer serializer, long defaultTimeoutMillis) {
        this.serializer = serializer;
        // 兜底超时绝不允许非正：非正只会让挂起请求永不超时
        this.defaultTimeoutMillis = defaultTimeoutMillis > 0
                ? defaultTimeoutMillis
                : DEFAULT_REQUEST_TIMEOUT_MILLIS;
    }

    @Override
    public Connection connect(Endpoint endpoint) {
        return new NettyConnection(channelFor(endpoint.address()), pending, serializer,
                defaultTimeoutMillis, endpoint.address());
    }

    private Channel channelFor(String address) {
        Channel channel = channels.get(address);
        if (channel != null && channel.isActive()) {
            return channel;
        }
        Object lock = locks.get(address);
        if (lock == null) {
            locks.putIfAbsent(address, new Object());
            lock = locks.get(address);
        }
        synchronized (lock) {
            channel = channels.get(address);
            if (channel != null && channel.isActive()) {
                return channel;
            }
            if (channel != null) {
                channel.close();
            }
            if (closed) {
                throw new RpcException("transport already closed");
            }
            Channel newChannel = doConnect(address);
            channels.put(address, newChannel);
            return newChannel;
        }
    }

    private Channel doConnect(String address) {
        int colon = address.lastIndexOf(':');
        if (colon <= 0 || colon == address.length() - 1) {
            throw new RpcException("invalid endpoint address: " + address);
        }
        String host = address.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(address.substring(colon + 1));
        } catch (NumberFormatException e) {
            throw new RpcException("invalid endpoint port: " + address, e);
        }
        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new IdleStateHandler(0, RpcConstants.HEARTBEAT_INTERVAL_SECONDS, 0))
                                .addLast(new FrameDecoder())
                                .addLast(new FrameEncoder())
                                .addLast(new ResponseHandler(pending, serializer))
                                .addLast(new HeartbeatTrigger());
                    }
                });
        ChannelFuture future = bootstrap.connect(host, port).awaitUninterruptibly();
        if (!future.isSuccess()) {
            throw new RpcException("connect failed: " + address, future.cause());
        }
        return future.channel();
    }

    /** 关闭全部连接并释放事件循环线程。幂等。 */
    public void shutdown() {
        closed = true;
        for (Channel channel : channels.values()) {
            channel.close();
        }
        channels.clear();
        group.shutdownGracefully(0, 5, TimeUnit.SECONDS);
    }
}
