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
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
}
