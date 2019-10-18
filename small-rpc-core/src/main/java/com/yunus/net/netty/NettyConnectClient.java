package com.yunus.net.netty;

import com.yunus.invoker.RpcInvokerFactory;
import com.yunus.net.base.Beat;
import com.yunus.net.base.ConnectClient;
import com.yunus.net.base.RpcRequest;
import com.yunus.net.base.RpcResponse;
import com.yunus.net.codec.NettyDecoder;
import com.yunus.net.codec.NettyEncoder;
import com.yunus.serialize.BaseSerializer;
import com.yunus.util.IpUtil;
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

        this.group = new NioEventLoopGroup();
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
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000);
        this.channel = bootstrap.connect(host, port).sync().channel();

        if (!isValidate()) {
            close();
            return;
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
        if (this.channel != null && this.channel.isActive()) {
            this.channel.close();
        }
        if (this.group != null && !this.group.isShutdown()) {
            this.group.shutdownGracefully();
        }
    }


    @Override
    public void send(RpcRequest rpcRequest) throws Exception {
        this.channel.writeAndFlush(rpcRequest).sync();
    }
}
