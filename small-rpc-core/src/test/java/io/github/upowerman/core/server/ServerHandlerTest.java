package io.github.upowerman.core.server;

import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.protocol.Frame;
import io.github.upowerman.core.protocol.FrameCodec;
import io.github.upowerman.core.protocol.FrameDecoder;
import io.github.upowerman.core.protocol.FrameEncoder;
import io.github.upowerman.core.protocol.ProtocolStatus;
import io.github.upowerman.core.protocol.RpcRequestBody;
import io.github.upowerman.core.protocol.RpcResponseBody;
import io.github.upowerman.core.provider.ReflectiveInvoker;
import io.github.upowerman.core.serialize.LegacyHessianSerializer;
import io.github.upowerman.core.serialize.Serializer;
import io.github.upowerman.core.serialize.SerializerRegistry;
import io.github.upowerman.core.testsupport.EchoDTO;
import io.github.upowerman.core.testsupport.EchoService;
import io.github.upowerman.core.testsupport.EchoServiceImpl;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.IdleStateEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ServerHandlerTest {

    private static final Serializer HESSIAN = new LegacyHessianSerializer();

    /** 业务处理在测试线程同步完成，便于断言 */
    private static final Executor DIRECT = new Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };

    private EmbeddedChannel channel;

    @Before
    public void setUp() {
        SerializerRegistry registry = new SerializerRegistry().register(HESSIAN);
        Map<String, Invoker> providers = new HashMap<String, Invoker>();
        providers.put(EchoService.class.getName(),
                new ReflectiveInvoker(EchoService.class, new EchoServiceImpl()));
        channel = new EmbeddedChannel(
                new FrameDecoder(), new FrameEncoder(), new ServerHandler(registry, providers, DIRECT));
    }

    @After
    public void tearDown() {
        channel.finishAndReleaseAll();
    }

    private void writeInbound(Frame frame) {
        ByteBuf buf = Unpooled.buffer();
        FrameCodec.encode(frame, buf);
        channel.writeInbound(buf);
    }

    private Frame readResponse() {
        ByteBuf outbound = channel.readOutbound();
        return outbound == null ? null : FrameCodec.decodeOne(outbound);
    }

    private Frame requestEcho(String msg, long requestId) {
        RpcRequestBody body = new RpcRequestBody();
        body.setServiceName(EchoService.class.getName());
        body.setMethodName("echo");
        body.setParameterTypes(new String[]{EchoDTO.class.getName()});
        body.setArguments(new Object[]{new EchoDTO(msg)});
        return Frame.request(HESSIAN.typeId(), requestId, HESSIAN.serialize(body));
    }

    private RpcResponseBody bodyOf(Frame response) {
        return (RpcResponseBody) HESSIAN.deserialize(response.body(), RpcResponseBody.class);
    }

    @Test
    public void requestRoundTripsToSuccessResponse() {
        writeInbound(requestEcho("world", 100L));
        Frame resp = readResponse();
        assertNotNull(resp);
        assertEquals(Frame.TYPE_RESPONSE, resp.type());
        assertEquals(100L, resp.requestId());
        assertEquals(HESSIAN.typeId(), resp.codec());
        assertEquals(ProtocolStatus.SUCCESS, resp.status());
        assertEquals("echo:world", ((EchoDTO) bodyOf(resp).getValue()).getMsg());
    }

    @Test
    public void heartbeatIsEchoedBack() {
        writeInbound(Frame.heartbeat(55L));
        Frame resp = readResponse();
        assertNotNull(resp);
        assertEquals(Frame.TYPE_HEARTBEAT, resp.type());
        assertEquals(55L, resp.requestId());
    }

    @Test
    public void unknownCodecRespondsSerializationErrorNotThrow() {
        RpcRequestBody body = new RpcRequestBody();
        body.setServiceName(EchoService.class.getName());
        body.setMethodName("echo");
        body.setParameterTypes(new String[]{EchoDTO.class.getName()});
        body.setArguments(new Object[]{new EchoDTO("x")});
        // codec=99：服务端没有注册该序列化器
        writeInbound(Frame.request((byte) 99, 101L, HESSIAN.serialize(body)));
        Frame resp = readResponse();
        assertNotNull(resp);
        assertEquals(ProtocolStatus.SERIALIZATION_ERROR, resp.status());
        assertEquals(101L, resp.requestId());
    }

    @Test
    public void corruptBodyRespondsSerializationError() {
        writeInbound(Frame.request(HESSIAN.typeId(), 102L, new byte[]{1, 2, 3}));
        Frame resp = readResponse();
        assertNotNull(resp);
        assertEquals(ProtocolStatus.SERIALIZATION_ERROR, resp.status());
        assertEquals(102L, resp.requestId());
    }

    /** Review Focus #4：合法 Hessian 但不是 RpcRequestBody 的负载，必须被类型防线拦下 */
    @Test
    public void wrongBodyTypeRespondsSerializationError() {
        writeInbound(Frame.request(HESSIAN.typeId(), 106L, HESSIAN.serialize("not a request body")));
        Frame resp = readResponse();
        assertNotNull(resp);
        assertEquals(ProtocolStatus.SERIALIZATION_ERROR, resp.status());
        assertEquals(106L, resp.requestId());
    }

    @Test
    public void unknownServiceRespondsServiceNotFound() {
        RpcRequestBody body = new RpcRequestBody();
        body.setServiceName("no.such.Service");
        body.setMethodName("go");
        body.setParameterTypes(new String[0]);
        body.setArguments(new Object[0]);
        writeInbound(Frame.request(HESSIAN.typeId(), 103L, HESSIAN.serialize(body)));
        Frame resp = readResponse();
        assertNotNull(resp);
        assertEquals(ProtocolStatus.SERVICE_NOT_FOUND, resp.status());
        assertEquals(103L, resp.requestId());
        assertTrue(bodyOf(resp).getErrorMessage().contains("no.such.Service"));
    }

    @Test
    public void missingMethodRespondsMethodNotFound() {
        RpcRequestBody body = new RpcRequestBody();
        body.setServiceName(EchoService.class.getName());
        body.setMethodName("noSuchMethod");
        body.setParameterTypes(new String[]{EchoDTO.class.getName()});
        body.setArguments(new Object[]{new EchoDTO("x")});
        writeInbound(Frame.request(HESSIAN.typeId(), 104L, HESSIAN.serialize(body)));
        Frame resp = readResponse();
        assertNotNull(resp);
        assertEquals(ProtocolStatus.METHOD_NOT_FOUND, resp.status());
    }

    @Test
    public void businessExceptionRespondsServerErrorWithDescription() {
        writeInbound(requestEcho("boom-now", 105L));
        Frame resp = readResponse();
        assertNotNull(resp);
        assertEquals(ProtocolStatus.SERVER_ERROR, resp.status());
        RpcResponseBody respBody = bodyOf(resp);
        assertNull(respBody.getValue());
        assertEquals("java.lang.IllegalStateException", respBody.getErrorClassName());
        assertTrue(respBody.getErrorMessage().contains("boom happened"));
    }

    /** Review Focus #3：流错位 → 关连接 */
    @Test
    public void protocolViolationClosesChannel() {
        ByteBuf garbage = Unpooled.buffer();
        garbage.writeShort(0x1234);
        garbage.writeBytes(new byte[18]);
        channel.writeInbound(garbage);
        assertFalse("protocol violation must close the channel", channel.isOpen());
        assertNull(readResponse());
    }

    /** I-1：结果序列化超限 MAX_BODY_LENGTH → 降级为空 body 的 SERIALIZATION_ERROR 帧，不写出超大 body */
    @Test
    public void oversizedResponseBodyDegradesToEmptySerializationErrorFrame() {
        final Serializer oversized = new Serializer() {
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
                return HESSIAN.deserialize(bytes, clazz);
            }
        };
        Map<String, Invoker> providers = new HashMap<String, Invoker>();
        providers.put(EchoService.class.getName(),
                new ReflectiveInvoker(EchoService.class, new EchoServiceImpl()));
        EmbeddedChannel local = new EmbeddedChannel(new FrameDecoder(), new FrameEncoder(),
                new ServerHandler(new SerializerRegistry().register(oversized), providers, DIRECT));
        try {
            RpcRequestBody body = new RpcRequestBody();
            body.setServiceName(EchoService.class.getName());
            body.setMethodName("echo");
            body.setParameterTypes(new String[]{EchoDTO.class.getName()});
            body.setArguments(new Object[]{new EchoDTO("big")});
            ByteBuf buf = Unpooled.buffer();
            FrameCodec.encode(Frame.request(oversized.typeId(), 107L, HESSIAN.serialize(body)), buf);
            local.writeInbound(buf);

            ByteBuf outbound = local.readOutbound();
            assertNotNull("超限也必须回帧（降级帧）", outbound);
            Frame resp = FrameCodec.decodeOne(outbound);
            assertEquals(Frame.TYPE_RESPONSE, resp.type());
            assertEquals(ProtocolStatus.SERIALIZATION_ERROR, resp.status());
            assertEquals(107L, resp.requestId());
            assertEquals("降级帧必须空 body", 0, resp.body().length);
        } finally {
            local.finishAndReleaseAll();
        }
    }

    /** 未知帧类型 → 关连接（与客户端 ResponseHandler 的同型分支对称） */
    @Test
    public void unknownFrameTypeClosesChannel() {
        // type=9 非法：Frame 工厂只产三种 type，这里手写头字节构造非法 type
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
        assertNull(readResponse());
    }

    @Test
    public void twoRequestsPipelinedOnOneConnectionBothAnswered() {
        writeInbound(requestEcho("one", 200L));
        writeInbound(requestEcho("two", 201L));
        Frame first = readResponse();
        Frame second = readResponse();
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(200L, first.requestId());
        assertEquals("echo:one", ((EchoDTO) bodyOf(first).getValue()).getMsg());
        assertEquals(201L, second.requestId());
        assertEquals("echo:two", ((EchoDTO) bodyOf(second).getValue()).getMsg());
    }

    /** READER_IDLE（服务端连续 SERVER_IDLE_SECONDS 无读）必须关连接——死连接回收唯一的执行点 */
    @Test
    public void readerIdleEventClosesConnection() {
        channel.pipeline().fireUserEventTriggered(new Object());
        assertTrue("非空闲事件不得关闭连接", channel.isOpen());
        channel.pipeline().fireUserEventTriggered(IdleStateEvent.READER_IDLE_STATE_EVENT);
        assertFalse("READER_IDLE 必须关闭连接", channel.isOpen());
    }

    /** 重复 start 必须显式失败，而非静默覆盖 boss/worker 泄漏上一组事件循环线程 */
    @Test
    public void doubleStartThrowsInsteadOfLeakingEventLoops() throws Exception {
        ServerSocket probe = new ServerSocket(0);
        int port = probe.getLocalPort();
        probe.close();
        RpcServer server = new RpcServer(port, new SerializerRegistry());
        server.start();
        try {
            server.start();
            fail("重复 start 应抛 IllegalStateException");
        } catch (IllegalStateException expected) {
            // ok
        } finally {
            server.shutdown();
        }
    }

    /** M-1：bind 失败（端口被占）须释放已创建的 boss/worker 事件循环组，并原样重抛 */
    @Test
    public void bindFailureReleasesEventLoopsAndRethrows() throws Exception {
        ServerSocket blocker = new ServerSocket(0);
        RpcServer server = new RpcServer(blocker.getLocalPort(), new SerializerRegistry());
        try {
            server.start();
            fail("端口被占用时 start 应抛异常");
        } catch (Exception expected) {
            // bind 失败必须原样重抛（Netty 对 BindException 经 sync() sneaky-throw，仍是受检异常形态）
        } finally {
            blocker.close();
        }
        try {
            assertTrue("boss 必须已进入关闭流程", eventLoopGroup(server, "boss").isShuttingDown());
            assertTrue("worker 必须已进入关闭流程", eventLoopGroup(server, "worker").isShuttingDown());
        } finally {
            // 幂等清理：即使断言失败也不在测试里泄漏事件循环线程
            server.shutdown();
        }
    }

    private static EventLoopGroup eventLoopGroup(RpcServer server, String name) throws Exception {
        Field field = RpcServer.class.getDeclaredField(name);
        field.setAccessible(true);
        return (EventLoopGroup) field.get(server);
    }
}
