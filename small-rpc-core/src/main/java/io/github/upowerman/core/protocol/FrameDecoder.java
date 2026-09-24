package io.github.upowerman.core.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.DecoderException;

import java.util.List;

/**
 * 累积字节流 → Frame。协议错误（magic/ver/bodyLen 非法）即关连接：
 * 流已错位，继续读只会产出垃圾帧。
 */
public class FrameDecoder extends ByteToMessageDecoder {

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        Frame frame = FrameCodec.decodeOne(in);
        if (frame != null) {
            out.add(frame);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Throwable real = cause instanceof DecoderException && cause.getCause() != null ? cause.getCause() : cause;
        if (real instanceof ProtocolException) {
            ctx.close();
        } else {
            ctx.fireExceptionCaught(cause);
        }
    }
}
