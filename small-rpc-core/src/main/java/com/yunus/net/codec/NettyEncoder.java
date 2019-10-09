package com.yunus.net.codec;

import com.yunus.serialize.BaseSerializer;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * @author gaoyunfeng
 */
public class NettyEncoder extends MessageToByteEncoder<Object> {
    private Class<?> genericClass;
    private BaseSerializer serializer;

    public NettyEncoder(Class<?> genericClass, final BaseSerializer serializer) {
        this.genericClass = genericClass;
        this.serializer = serializer;
    }

    @Override
    public void encode(ChannelHandlerContext ctx, Object in, ByteBuf out) throws Exception {
        if (genericClass.isInstance(in)) {
            byte[] data = serializer.serialize(in);
            out.writeInt(data.length);
            out.writeBytes(data);
        }
    }
}
