package io.github.upowerman.core.transport;

import io.github.upowerman.core.RpcConstants;
import io.github.upowerman.core.invocation.GenericInvocation;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.protocol.Frame;
import io.github.upowerman.core.protocol.FrameCodec;
import io.github.upowerman.core.protocol.FrameDecoder;
import io.github.upowerman.core.protocol.FrameEncoder;
import io.github.upowerman.core.protocol.ProtocolStatus;
import io.github.upowerman.core.protocol.RpcRequestBody;
import io.github.upowerman.core.protocol.RpcResponseBody;
import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import io.github.upowerman.core.serialize.HessianSerializer;
import io.github.upowerman.core.serialize.Serializer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.IdleStateEvent;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NettyClientHandlersTest {

    private static final Serializer HESSIAN = new HessianSerializer();

    private static final long TIMEOUT_MILLIS = 30000L;

    private static Invocation invocation() {
        Map<String, Object> attachments = new HashMap<String, Object>();
        attachments.put(RpcConstants.ATTACH_ADDRESS, "127.0.0.1:7080");
        return new GenericInvocation("com.test.EchoService", "echo",
                new Class<?>[]{String.class}, new Object[]{"world"}, attachments);
    }

    private static EmbeddedChannel responseChannel(PendingRequests pending) {
        return new EmbeddedChannel(new FrameDecoder(), new FrameEncoder(),
                new ResponseHandler(pending, HESSIAN));
    }

    private static void writeInbound(EmbeddedChannel channel, Frame frame) {
        ByteBuf buf = Unpooled.buffer();
        FrameCodec.encode(frame, buf);
        channel.writeInbound(buf);
    }

    private static Frame readOutbound(EmbeddedChannel channel) {
        ByteBuf outbound = channel.readOutbound();
        return outbound == null ? null : FrameCodec.decodeOne(outbound);
    }

    // ---------- NettyConnection ----------

    @Test
    public void requestWritesRequestFrameCarryingInvocation() {
        EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder(), new FrameEncoder());
        PendingRequests pending = new PendingRequests();
        NettyConnection connection = new NettyConnection(channel, pending, HESSIAN, TIMEOUT_MILLIS, "127.0.0.1:7080");

        CompletableFuture<Result> future = connection.request(invocation());
        Frame sent = readOutbound(channel);
        assertNotNull(sent);
        assertEquals(Frame.TYPE_REQUEST, sent.type());
        assertEquals(HESSIAN.typeId(), sent.codec());
        assertTrue("requestId must be a positive long", sent.requestId() > 0L);
        assertFalse("request must stay in flight until answered", future.isDone());
        assertEquals(1, pending.size());

        RpcRequestBody body = (RpcRequestBody) HESSIAN.deserialize(sent.body(), RpcRequestBody.class);
        assertEquals("com.test.EchoService", body.getServiceName());
        assertEquals("echo", body.getMethodName());
        assertEquals("java.lang.String", body.getParameterTypes()[0]);
        assertEquals("world", body.getArguments()[0]);
        assertEquals("127.0.0.1:7080", body.getAttachments().get(RpcConstants.ATTACH_ADDRESS));

        // 清理：应答以结算 in-flight 条目
        pending.complete(sent.requestId(), DefaultResult.success("ok"));
        channel.finishAndReleaseAll();
    }

    @Test
    public void serializationFailureSettlesAsSerializationErrorAndEvictsPending() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder(), new FrameEncoder());
        PendingRequests pending = new PendingRequests();
        Serializer broken = new Serializer() {
            @Override
            public byte typeId() {
                return 42;
            }

            @Override
            public byte[] serialize(Object obj) {
                throw new IllegalStateException("cannot serialize");
            }

            @Override
            public Object deserialize(byte[] bytes, Class<?> clazz) {
                throw new IllegalStateException("cannot deserialize");
            }
        };
        NettyConnection connection = new NettyConnection(channel, pending, broken, TIMEOUT_MILLIS, "127.0.0.1:7080");

        Result result = connection.request(invocation()).get(1, TimeUnit.SECONDS);

        assertEquals(Status.SERIALIZATION_ERROR, result.status());
        assertEquals(0, pending.size());
        assertNull("nothing must be written when the request cannot be encoded", readOutbound(channel));
        channel.finishAndReleaseAll();
    }

    /** I-1：请求 body 超 Frame.MAX_BODY_LENGTH → 本地以 SERIALIZATION_ERROR 结算，不写出任何字节 */
    @Test
    public void oversizedRequestBodySettlesAsSerializationErrorWithoutWrite() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder(), new FrameEncoder());
        PendingRequests pending = new PendingRequests();
        Serializer oversized = new Serializer() {
            @Override
            public byte typeId() {
                return 43;
            }

            @Override
            public byte[] serialize(Object obj) {
                return new byte[Frame.MAX_BODY_LENGTH + 1];
            }

            @Override
            public Object deserialize(byte[] bytes, Class<?> clazz) {
                throw new IllegalStateException("not expected in this test");
            }
        };
        NettyConnection connection = new NettyConnection(channel, pending, oversized, TIMEOUT_MILLIS, "127.0.0.1:7080");

        Result result = connection.request(invocation()).get(1, TimeUnit.SECONDS);

        assertEquals(Status.SERIALIZATION_ERROR, result.status());
        assertTrue("诊断信息必须指明请求过大", result.exception().getMessage().contains("too large"));
        assertEquals("超限请求必须当场驱逐 in-flight 条目", 0, pending.size());
        assertNull("超限请求不得写出任何字节", readOutbound(channel));
        channel.finishAndReleaseAll();
    }

    @Test
    public void sendFailureSettlesAsNetworkErrorAndEvictsPending() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder(), new FrameEncoder());
        PendingRequests pending = new PendingRequests();
        NettyConnection connection = new NettyConnection(channel, pending, HESSIAN, TIMEOUT_MILLIS, "127.0.0.1:7080");
        channel.close();

        Result result = connection.request(invocation()).get(1, TimeUnit.SECONDS);

        assertEquals(Status.NETWORK_ERROR, result.status());
        assertEquals(0, pending.size());
    }

    // ---------- ResponseHandler ----------

    @Test
    public void successResponseCompletesMatchingPendingRequest() throws Exception {
        PendingRequests pending = new PendingRequests();
        EmbeddedChannel channel = responseChannel(pending);
        long requestId = pending.nextRequestId();
        CompletableFuture<Result> future = pending.register(requestId);

        RpcResponseBody body = new RpcResponseBody();
        body.setValue("echo:world");
        writeInbound(channel, Frame.response(HESSIAN.typeId(), ProtocolStatus.SUCCESS, requestId,
                HESSIAN.serialize(body)));

        assertEquals("echo:world", future.get(1, TimeUnit.SECONDS).value());
        assertEquals(0, pending.size());
        channel.finishAndReleaseAll();
    }

    @Test
    public void responseForUnknownRequestIdIsIgnored() {
        PendingRequests pending = new PendingRequests();
        EmbeddedChannel channel = responseChannel(pending);

        RpcResponseBody body = new RpcResponseBody();
        body.setValue("late");
        writeInbound(channel, Frame.response(HESSIAN.typeId(), ProtocolStatus.SUCCESS, 999L,
                HESSIAN.serialize(body)));

        assertEquals(0, pending.size());
        assertTrue(channel.isOpen());
        channel.finishAndReleaseAll();
    }

    @Test
    public void errorStatusMapsBackToResultStatusWithRebuiltException() throws Exception {
        PendingRequests pending = new PendingRequests();
        EmbeddedChannel channel = responseChannel(pending);
        long requestId = pending.nextRequestId();
        CompletableFuture<Result> future = pending.register(requestId);

        RpcResponseBody body = new RpcResponseBody();
        body.setErrorClassName("java.lang.IllegalStateException");
        body.setErrorMessage("boom happened");
        writeInbound(channel, Frame.response(HESSIAN.typeId(), ProtocolStatus.SERVER_ERROR, requestId,
                HESSIAN.serialize(body)));

        Result result = future.get(1, TimeUnit.SECONDS);
        assertEquals(Status.SERVER_ERROR, result.status());
        assertNotNull(result.exception());
        assertTrue(result.exception().getMessage().contains("boom happened"));
        assertTrue(result.exception().getMessage().contains("java.lang.IllegalStateException"));
        channel.finishAndReleaseAll();
    }

    @Test
    public void corruptResponseBodySettlesAsSerializationError() throws Exception {
        PendingRequests pending = new PendingRequests();
        EmbeddedChannel channel = responseChannel(pending);
        long requestId = pending.nextRequestId();
        CompletableFuture<Result> future = pending.register(requestId);

        writeInbound(channel, Frame.response(HESSIAN.typeId(), ProtocolStatus.SUCCESS, requestId,
                new byte[]{1, 2, 3}));

        assertEquals(Status.SERIALIZATION_ERROR, future.get(1, TimeUnit.SECONDS).status());
        channel.finishAndReleaseAll();
    }

    @Test
    public void unknownStatusByteSettlesAsSerializationError() throws Exception {
        PendingRequests pending = new PendingRequests();
        EmbeddedChannel channel = responseChannel(pending);
        long requestId = pending.nextRequestId();
        CompletableFuture<Result> future = pending.register(requestId);

        RpcResponseBody body = new RpcResponseBody();
        body.setValue("ok");
        writeInbound(channel, Frame.response(HESSIAN.typeId(), (byte) 99, requestId,
                HESSIAN.serialize(body)));

        assertEquals(Status.SERIALIZATION_ERROR, future.get(1, TimeUnit.SECONDS).status());
        channel.finishAndReleaseAll();
    }

    @Test
    public void heartbeatResponseIsIgnoredWithoutSideEffects() {
        PendingRequests pending = new PendingRequests();
        EmbeddedChannel channel = responseChannel(pending);

        writeInbound(channel, Frame.heartbeat(1L));

        assertEquals(0, pending.size());
        assertNull(channel.readOutbound());
        assertTrue(channel.isOpen());
        channel.finishAndReleaseAll();
    }

    @Test
    public void unknownFrameTypeClosesChannel() {
        PendingRequests pending = new PendingRequests();
        EmbeddedChannel channel = responseChannel(pending);

        // type=9 非法：Frame 工厂只产三种 type，这里直接编码非法 type 字节
        ByteBuf buf = Unpooled.buffer();
        buf.writeShort(Frame.MAGIC);
        buf.writeByte(Frame.VERSION);
        buf.writeByte(9);
        buf.writeByte(0);
        buf.writeByte(0);
        buf.writeShort(0);
        buf.writeLong(1L);
        buf.writeInt(0);
        channel.writeInbound(buf);

        assertFalse("unknown frame type must close the channel", channel.isOpen());
        channel.finishAndReleaseAll();
    }

    // ---------- HeartbeatTrigger ----------

    @Test
    public void writeIdleEventTriggersHeartbeatFrame() {
        EmbeddedChannel channel = new EmbeddedChannel(new FrameEncoder(), new HeartbeatTrigger());

        // Netty 4.1 的 IdleStateEvent(IdleState, boolean) 构造器是 protected，
        // 首次写空闲事件的公开等价物即 FIRST_WRITER_IDLE_STATE_EVENT
        channel.pipeline().fireUserEventTriggered(IdleStateEvent.FIRST_WRITER_IDLE_STATE_EVENT);

        Frame heartbeat = readOutbound(channel);
        assertNotNull("write-idle must emit a heartbeat frame", heartbeat);
        assertEquals(Frame.TYPE_HEARTBEAT, heartbeat.type());
        assertEquals(0, heartbeat.body().length);
        channel.finishAndReleaseAll();
    }
}
