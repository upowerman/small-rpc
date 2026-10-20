package io.github.upowerman.core.serialize;

import io.github.upowerman.core.protocol.RpcRequestBody;
import io.github.upowerman.core.protocol.RpcResponseBody;
import io.github.upowerman.core.testsupport.EchoDTO;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** C6：JSON 序列化实现——DTO 经 default typing 保持具体类型往返，与 Hessian typeId 共存。 */
public class JsonSerializerTest {

    private final JsonSerializer json = new JsonSerializer();

    @Test
    public void typeIdCoexistsWithHessian() {
        assertEquals(2, json.typeId());
        assertNotEqualByte(HessianSerializer.TYPE_ID, JsonSerializer.TYPE_ID);
    }

    private static void assertNotEqualByte(byte a, byte b) {
        assertTrue("json typeId 不得与 hessian 复用同一字节", a != b);
    }

    @Test
    public void requestBodyRoundTripsDtoArgumentsWithConcreteType() {
        RpcRequestBody in = new RpcRequestBody();
        in.setServiceName("io.github.upowerman.service.EchoService");
        in.setMethodName("echo");
        in.setParameterTypes(new String[]{"io.github.upowerman.core.testsupport.EchoDTO"});
        in.setArguments(new Object[]{new EchoDTO("world")});
        Map<String, Object> attachments = new HashMap<String, Object>();
        attachments.put("traceId", "t-9");
        in.setAttachments(attachments);

        RpcRequestBody out = (RpcRequestBody) json.deserialize(json.serialize(in), RpcRequestBody.class);

        assertEquals("io.github.upowerman.service.EchoService", out.getServiceName());
        assertArrayEquals(new String[]{"io.github.upowerman.core.testsupport.EchoDTO"}, out.getParameterTypes());
        Object arg = out.getArguments()[0];
        assertTrue("DTO 参数经 JSON 往返必须保持具体类型（default typing），实际: " + arg.getClass(),
                arg instanceof EchoDTO);
        assertEquals("world", ((EchoDTO) arg).getMsg());
    }

    @Test
    public void responseBodyRoundTripsDtoValueAndErrorDescription() {
        RpcResponseBody withValue = new RpcResponseBody();
        withValue.setValue(new EchoDTO("payload"));
        RpcResponseBody outValue = (RpcResponseBody) json.deserialize(
                json.serialize(withValue), RpcResponseBody.class);
        assertTrue(outValue.getValue() instanceof EchoDTO);
        assertEquals("payload", ((EchoDTO) outValue.getValue()).getMsg());

        RpcResponseBody withError = new RpcResponseBody();
        withError.setErrorClassName("java.lang.IllegalStateException");
        withError.setErrorMessage("boom");
        RpcResponseBody outError = (RpcResponseBody) json.deserialize(
                json.serialize(withError), RpcResponseBody.class);
        assertEquals("java.lang.IllegalStateException", outError.getErrorClassName());
        assertEquals("boom", outError.getErrorMessage());
    }

    @Test
    public void numericAttachmentSurvivesAsNumber() {
        // Long 3000 经 JSON 往返可能是 Integer——值语义不丢即可（消费端按 Number 解析）
        RpcRequestBody in = new RpcRequestBody();
        in.setArguments(new Object[0]);
        Map<String, Object> attachments = new HashMap<String, Object>();
        attachments.put("rpc.timeout", 3000L);
        in.setAttachments(attachments);

        RpcRequestBody out = (RpcRequestBody) json.deserialize(json.serialize(in), RpcRequestBody.class);
        Object timeout = out.getAttachments().get("rpc.timeout");
        assertTrue("数值附件应保持 Number，实际: " + timeout.getClass(), timeout instanceof Number);
        assertEquals(3000L, ((Number) timeout).longValue());
    }

    @Test
    public void spiRegistersJsonExtensionAlongsideHessian() {
        SerializerRegistry registry = SerializerRegistry.fromSpi();
        assertNotNull("typeId=2 应登记为 json", registry.find((byte) 2));
        assertNotNull("typeId=1 应保持 hessian", registry.find((byte) 1));
        assertTrue(registry.find((byte) 2) instanceof JsonSerializer);
    }
}
