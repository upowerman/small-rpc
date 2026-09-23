package io.github.upowerman.core.invocation;

import io.github.upowerman.core.RpcConstants;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class GenericInvocationTest {

    @Test
    public void carriesAllFields() {
        Map<String, Object> attachments = new HashMap<>();
        attachments.put(RpcConstants.ATTACH_ADDRESS, "127.0.0.1:7080");
        GenericInvocation invocation = new GenericInvocation(
                "com.test.EchoService", "echo", new Class<?>[]{String.class},
                new Object[]{"x"}, attachments);

        assertEquals("com.test.EchoService", invocation.serviceName());
        assertEquals("echo", invocation.methodName());
        assertSame(attachments, invocation.attachments());
        assertEquals(1, invocation.parameterTypes().length);
        assertEquals("x", invocation.arguments()[0]);
        assertEquals("127.0.0.1:7080",
                invocation.attachments().get(RpcConstants.ATTACH_ADDRESS));
    }
}
