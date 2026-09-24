package io.github.upowerman.core.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Frame → 字节流（每帧独立写出，粘包由接收侧 FrameDecoder 处理）。
 */
public class FrameEncoder extends MessageToByteEncoder<Frame> {

    @Override
    protected void encode(ChannelHandlerContext ctx, Frame frame, ByteBuf out) {
        FrameCodec.encode(frame, out);
    }
}
