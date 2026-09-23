package io.github.upowerman.core.provider;

import io.github.upowerman.core.invocation.GenericInvocation;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.result.Status;
import org.junit.Test;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

public class ReflectiveInvokerTest {

    public interface EchoService {
        String echo(String msg);

        void boom();
    }

    public static class EchoServiceImpl implements EchoService {
        @Override
        public String echo(String msg) {
            return "echo:" + msg;
        }

        @Override
        public void boom() {
            throw new IllegalStateException("biz error");
        }
    }

    private ReflectiveInvoker invoker() {
        return new ReflectiveInvoker(EchoService.class, new EchoServiceImpl());
    }

    private Invocation invocation(String method, Class<?>[] types, Object[] args) {
        return new GenericInvocation(EchoService.class.getName(), method, types, args,
                new HashMap<String, Object>());
    }

    @Test
    public void successReturnsValue() throws Exception {
        ReflectiveInvoker invoker = invoker();
        Object value = invoker.invoke(invocation("echo", new Class<?>[]{String.class},
                new Object[]{"hi"})).get(1, TimeUnit.SECONDS).value();
        assertEquals("echo:hi", value);
    }

    @Test
    public void missingMethodGivesMethodNotFound() throws Exception {
        ReflectiveInvoker invoker = invoker();
        io.github.upowerman.core.result.Result result = invoker.invoke(
                invocation("notExists", new Class<?>[]{String.class}, new Object[]{"x"}))
                .get(1, TimeUnit.SECONDS);
        assertSame(Status.METHOD_NOT_FOUND, result.status());
    }

    @Test
    public void businessErrorGivesServerErrorWithCause() throws Exception {
        ReflectiveInvoker invoker = invoker();
        io.github.upowerman.core.result.Result result = invoker.invoke(
                invocation("boom", new Class<?>[0], new Object[0])).get(1, TimeUnit.SECONDS);
        assertSame(Status.SERVER_ERROR, result.status());
        assertNotNull(result.exception());
        assertEquals("biz error", result.exception().getMessage());
    }
}