package io.github.upowerman.core.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class FrameCodecTest {

    @Test
    public void roundtripPreservesAllHeaderFieldsAndBody() {
        Frame f = Frame.response((byte) 1, (byte) 0, 42L, new byte[]{1, 2, 3});
        ByteBuf buf = Unpooled.buffer();
        FrameCodec.encode(f, buf);
        Frame out = FrameCodec.decodeOne(buf);
        assertEquals(Frame.TYPE_RESPONSE, out.type());
        assertEquals(1, out.codec());
        assertEquals(0, out.status());
        assertEquals(42L, out.requestId());
        assertArrayEquals(new byte[]{1, 2, 3}, out.body());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void heartbeatHasEmptyBodyAndRoundtrips() {
        ByteBuf buf = Unpooled.buffer();
        FrameCodec.encode(Frame.heartbeat(7L), buf);
        Frame out = FrameCodec.decodeOne(buf);
        assertEquals(Frame.TYPE_HEARTBEAT, out.type());
        assertEquals(7L, out.requestId());
        assertEquals(0, out.body().length);
    }

    @Test
    public void twoFramesInOneBufferAreSplitCorrectly() {
        ByteBuf buf = Unpooled.buffer();
        FrameCodec.encode(Frame.request((byte) 1, 1L, new byte[]{7}), buf);
        FrameCodec.encode(Frame.heartbeat(2L), buf);
        Frame first = FrameCodec.decodeOne(buf);
        assertEquals(1L, first.requestId());
        Frame second = FrameCodec.decodeOne(buf);
        assertEquals(Frame.TYPE_HEARTBEAT, second.type());
        assertEquals(2L, second.requestId());
        assertNull(FrameCodec.decodeOne(buf));
    }

    @Test
    public void partialFrameWaitsForMoreBytes() {
        ByteBuf full = Unpooled.buffer();
        FrameCodec.encode(Frame.request((byte) 1, 9L, new byte[]{1, 2, 3, 4, 5}), full);
        byte[] all = new byte[full.readableBytes()];
        full.readBytes(all);
        ByteBuf in = Unpooled.buffer();
        for (int i = 0; i < all.length; i++) {
            in.writeByte(all[i]);
            if (i < all.length - 1) {
                assertNull("incomplete at " + (i + 1) + " bytes", FrameCodec.decodeOne(in));
            }
        }
        Frame out = FrameCodec.decodeOne(in);
        assertEquals(9L, out.requestId());
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, out.body());
    }

    @Test
    public void badMagicThrows() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeShort(0x1234);
        buf.writeBytes(new byte[18]);
        try {
            FrameCodec.decodeOne(buf);
            fail("expected ProtocolException");
        } catch (ProtocolException e) {
            assertTrue(e.getMessage().contains("magic"));
        }
    }

    @Test
    public void unsupportedVersionThrows() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeShort(Frame.MAGIC);
        buf.writeByte(9);
        buf.writeBytes(new byte[17]);
        try {
            FrameCodec.decodeOne(buf);
            fail("expected ProtocolException");
        } catch (ProtocolException e) {
            assertTrue(e.getMessage().contains("version"));
        }
    }

    /** 钉住负 bodyLen 分支：负长度与超限同走 bodyLen 拒绝，先于任何分配 */
    @Test
    public void negativeBodyLengthThrows() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeShort(Frame.MAGIC);
        buf.writeByte(Frame.VERSION);
        buf.writeByte(Frame.TYPE_REQUEST);
        buf.writeByte(0);
        buf.writeByte(0);
        buf.writeShort(0);
        buf.writeLong(1L);
        buf.writeInt(-1);
        try {
            FrameCodec.decodeOne(buf);
            fail("expected ProtocolException");
        } catch (ProtocolException e) {
            assertTrue(e.getMessage().contains("bodyLen"));
        }
    }

    @Test
    public void oversizedBodyLengthIsRejected() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeShort(Frame.MAGIC);
        buf.writeByte(Frame.VERSION);
        buf.writeByte(Frame.TYPE_REQUEST);
        buf.writeByte(0);
        buf.writeByte(0);
        buf.writeShort(0);
        buf.writeLong(1L);
        buf.writeInt(Integer.MAX_VALUE);
        try {
            FrameCodec.decodeOne(buf);
            fail("expected ProtocolException");
        } catch (ProtocolException e) {
            assertTrue(e.getMessage().contains("bodyLen"));
        }
    }
}
