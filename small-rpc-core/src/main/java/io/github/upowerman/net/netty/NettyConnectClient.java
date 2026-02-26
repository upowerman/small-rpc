package io.github.upowerman.net.netty;

import io.github.upowerman.invoker.RpcInvokerFactory;
import io.github.upowerman.net.base.Beat;
import io.github.upowerman.net.base.ConnectClient;
import io.github.upowerman.net.base.RpcRequest;
import io.github.upowerman.net.base.RpcResponse;
import io.github.upowerman.net.codec.NettyDecoder;
import io.github.upowerman.net.codec.NettyEncoder;
import io.github.upowerman.serialize.BaseSerializer;
import io.github.upowerman.util.IpUtil;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.concurrent.TimeUnit;

/**
 * @author gaoyunfeng
 */
public class NettyConnectClient extends ConnectClient {

    private EventLoopGroup group;
    private Channel channel;

    @Override
    public void init(String address, final BaseSerializer serializer, final RpcInvokerFactory rpcInvokerFactory) throws Exception {
        final NettyConnectClient thisClient = this;
        Object[] array = IpUtil.parseIpPort(address);
        String host = (String) array[0];
        int port = (int) array[1];

        this.group = new NioEventLoopGroup(1); // Use single thread for client
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel channel) throws Exception {
                        channel.pipeline()
                                .addLast(new IdleStateHandler(0, 0, Beat.BEAT_INTERVAL, TimeUnit.SECONDS))
                                .addLast(new NettyEncoder(RpcRequest.class, serializer))
                                .addLast(new NettyDecoder(RpcResponse.class, serializer))
                                .addLast(new NettyClientHandler(rpcInvokerFactory, thisClient));
                    }
                })
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .option(ChannelOption.SO_REUSEADDR, true);
        
        try {
            this.channel = bootstrap.connect(host, port).sync().channel();
            
            if (!isValidate()) {
                close();
                throw new RuntimeException("Failed to establish valid connection to " + address);
            }
        } catch (Exception e) {
            close();
            throw new RuntimeException("Failed to connect to " + address, e);
        }
    }


    @Override
    public boolean isValidate() {
        if (this.channel != null) {
            return this.channel.isActive();
        }
        return false;
    }

    @Override
    public void close() {
        try {
            if (this.channel != null && this.channel.isActive()) {
                this.channel.close().sync();
            }
        } catch (Exception e) {
            logger.error("Error closing channel: {}", e.getMessage(), e);
        }
        
        try {
            if (this.group != null && !this.group.isShutdown()) {
                this.group.shutdownGracefully(0, 5, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            logger.error("Error shutting down event loop group: {}", e.getMessage(), e);
        }
    }


    @Override
    public void send(RpcRequest rpcRequest) throws Exception {
        this.channel.writeAndFlush(rpcRequest).sync();
    }
}
