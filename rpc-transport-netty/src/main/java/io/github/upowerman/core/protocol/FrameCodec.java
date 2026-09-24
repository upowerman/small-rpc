package io.github.upowerman.core.protocol;

import io.netty.buffer.ByteBuf;

/**
 * 帧编解码：纯字节层静态方法，不依赖 ChannelHandler，可脱离 Netty pipeline 单测。
 * decodeOne 数据不足返回 null（半包等待）；magic/ver 非法或 bodyLen 超限抛 ProtocolException。
 * 前置 magic 校验让非法流在头 2 字节即被拒绝，不必等满一个 header。
 */
public final class FrameCodec {

    private FrameCodec() {
    }

    public static void encode(Frame frame, ByteBuf out) {
        out.writeShort(Frame.MAGIC);
        out.writeByte(Frame.VERSION);
        out.writeByte(frame.type());
        out.writeByte(frame.codec());
        out.writeByte(frame.status());
        out.writeShort(0);   // rsvd
        out.writeLong(frame.requestId());
        out.writeInt(frame.body().length);
        out.writeBytes(frame.body());
    }

    /** 从累积缓冲解出一帧；数据不足（半包）返回 null；流非法抛 ProtocolException。 */
    public static Frame decodeOne(ByteBuf in) {
        if (in.readableBytes() < 2) {
            return null;
        }
        in.markReaderIndex();
        short magic = in.readShort();
        if (magic != Frame.MAGIC) {
            throw new ProtocolException("bad magic: 0x" + Integer.toHexString(magic & 0xFFFF));
        }
        if (in.readableBytes() < Frame.HEADER_LENGTH - 2) {
            in.resetReaderIndex();
            return null;
        }
        byte version = in.readByte();
        if (version != Frame.VERSION) {
            throw new ProtocolException("unsupported version: " + version);
        }
        byte type = in.readByte();
        byte codec = in.readByte();
        byte status = in.readByte();
        in.skipBytes(2);   // rsvd
        long requestId = in.readLong();
        int bodyLen = in.readInt();
        if (bodyLen < 0 || bodyLen > Frame.MAX_BODY_LENGTH) {
            throw new ProtocolException("bad bodyLen: " + bodyLen);
        }
        if (in.readableBytes() < bodyLen) {
            in.resetReaderIndex();
            return null;
        }
        byte[] body = new byte[bodyLen];
        in.readBytes(body);
        return new Frame(type, codec, status, requestId, body);
    }
}
