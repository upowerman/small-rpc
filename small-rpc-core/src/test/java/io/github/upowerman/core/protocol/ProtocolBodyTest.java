package io.github.upowerman.core.protocol;

import io.github.upowerman.core.RpcConstants;
import io.github.upowerman.core.serialize.LegacyHessianSerializer;
import io.github.upowerman.core.serialize.Serializer;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

public class ProtocolBodyTest {

    private final Serializer serializer = new LegacyHessianSerializer();

    @Test
    public void requestBodyRoundtripThroughHessian() {
        RpcRequestBody in = new RpcRequestBody();
        in.setServiceName("io.github.upowerman.service.HelloService");
        in.setMethodName("hello");
        in.setParameterTypes(new String[]{"java.lang.String"});
        in.setArguments(new Object[]{"world"});
        Map<String, Object> attachments = new HashMap<String, Object>();
        attachments.put(RpcConstants.ATTACH_TRACE_ID, "t-1");
        attachments.put(RpcConstants.ATTACH_TIMEOUT, 3000L);
        in.setAttachments(attachments);

        RpcRequestBody out = (RpcRequestBody) serializer.deserialize(
                serializer.serialize(in), RpcRequestBody.class);

        assertEquals("io.github.upowerman.service.HelloService", out.getServiceName());
        assertEquals("hello", out.getMethodName());
        assertArrayEquals(new String[]{"java.lang.String"}, out.getParameterTypes());
        assertEquals("world", out.getArguments()[0]);
        assertEquals("t-1", out.getAttachments().get(RpcConstants.ATTACH_TRACE_ID));
        assertEquals(3000L, out.getAttachments().get(RpcConstants.ATTACH_TIMEOUT));
    }

    @Test
    public void responseBodyRoundtripsValueAndErrorDescription() {
        RpcResponseBody withValue = new RpcResponseBody();
        withValue.setValue("ok");
        RpcResponseBody outValue = (RpcResponseBody) serializer.deserialize(
                serializer.serialize(withValue), RpcResponseBody.class);
        assertEquals("ok", outValue.getValue());
        assertNull(outValue.getErrorMessage());

        RpcResponseBody withError = new RpcResponseBody();
        withError.setErrorClassName("java.lang.IllegalStateException");
        withError.setErrorMessage("boom happened");
        RpcResponseBody outError = (RpcResponseBody) serializer.deserialize(
                serializer.serialize(withError), RpcResponseBody.class);
        assertNull(outError.getValue());
        assertEquals("java.lang.IllegalStateException", outError.getErrorClassName());
        assertEquals("boom happened", outError.getErrorMessage());
    }

    /**
     * 行为锚点：委托的 1.x Hessian 反序列化忽略 clazz 参数——喂一个合法 Hessian
     * 但不是 RpcRequestBody 的负载，它会原样返回 String。因此网络层的强制转型
     * 才是类型防线（Task 3/4 据此断言 SERIALIZATION_ERROR）。
     */
    @Test
    public void deserializerIgnoresClazzSoCastIsTheTypeGuard() {
        Object decoded = serializer.deserialize(
                serializer.serialize("not a request body"), RpcRequestBody.class);
        assertFalse("delegate must not fabricate a RpcRequestBody", decoded instanceof RpcRequestBody);
        assertEquals("not a request body", decoded);
    }
}
