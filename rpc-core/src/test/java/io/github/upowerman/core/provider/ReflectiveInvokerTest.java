package io.github.upowerman.core.provider;

import io.github.upowerman.core.invocation.GenericInvocation;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.result.Status;
import org.junit.Test;

import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

public class ReflectiveInvokerTest {

    public interface EchoService {
        String echo(String msg);

        void boom();

        CompletableFuture<String> echoAsync(String msg);

        CompletableFuture<String> boomAsync();
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

        @Override
        public CompletableFuture<String> echoAsync(String msg) {
            return CompletableFuture.completedFuture("async:" + msg);
        }

        @Override
        public CompletableFuture<String> boomAsync() {
            CompletableFuture<String> failed = new CompletableFuture<String>();
            failed.completeExceptionally(new IllegalStateException("async biz error"));
            return failed;
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

    @Test
    public void completableFutureReturnValueIsUnwrappedToPlainValue() throws Exception {
        // A2：实现返回 CompletableFuture 时，Result 携带的必须是解包后的业务值（可序列化），
        // 而不是 future 对象本身
        ReflectiveInvoker invoker = invoker();
        io.github.upowerman.core.result.Result result = invoker.invoke(
                invocation("echoAsync", new Class<?>[]{String.class}, new Object[]{"hi"}))
                .get(1, TimeUnit.SECONDS);
        assertSame(Status.SUCCESS, result.status());
        assertEquals("async:hi", result.value());
    }

    @Test
    public void exceptionallyCompletedFutureGivesServerErrorWithRealCause() throws Exception {
        ReflectiveInvoker invoker = invoker();
        io.github.upowerman.core.result.Result result = invoker.invoke(
                invocation("boomAsync", new Class<?>[0], new Object[0])).get(1, TimeUnit.SECONDS);
        assertSame(Status.SERVER_ERROR, result.status());
        assertNotNull(result.exception());
        assertEquals("async biz error", result.exception().getMessage());
    }
}