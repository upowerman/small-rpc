package io.github.upowerman.core.filter;

import io.github.upowerman.core.invocation.GenericInvocation;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.metrics.RpcMetrics;
import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import io.github.upowerman.exception.RpcException;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Fallback Filter：兜底值接管、降级计数、fallback 异常语义、异步方法适配 */
public class FallbackFilterTest {

    public interface EchoService {
        String echo(String msg);
        CompletableFuture<String> echoAsync(String msg);
    }

    public static class EchoFallback implements EchoService {
        @Override public String echo(String msg) {
            return "fallback:" + msg;
        }
        @Override public CompletableFuture<String> echoAsync(String msg) {
            return CompletableFuture.completedFuture("fallback-async:" + msg);
        }
    }

    public static class ThrowingFallback implements EchoService {
        @Override public String echo(String msg) {
            throw new IllegalStateException("fallback broken");
        }
        @Override public CompletableFuture<String> echoAsync(String msg) {
            throw new IllegalStateException("fallback broken");
        }
    }

    @Before
    public void reset() {
        RpcMetrics.getInstance().reset();
    }

    private static Invocation invocation(String method, Object... args) {
        return new GenericInvocation(EchoService.class.getName(), method,
                new Class<?>[]{String.class}, args, new HashMap<String, Object>());
    }

    private static Invoker failing(final Status status) {
        return new Invoker() {
            @Override public Class<?> interfaceClass() { return EchoService.class; }
            @Override public CompletableFuture<Result> invoke(Invocation inv) {
                return CompletableFuture.completedFuture(DefaultResult.failure(status,
                        new RuntimeException("downstream boom")));
            }
        };
    }

    @Test
    public void fallbackValueReplacesFailureAndCountsDegraded() throws Exception {
        FallbackFilter filter = new FallbackFilter(new EchoFallback());
        Result r = filter.invoke(failing(Status.SERVER_ERROR), invocation("echo", "x")).get();
        assertEquals(Status.SUCCESS, r.status());
        assertEquals("fallback:x", r.value());
        assertEquals(1L, findStat(EchoService.class.getName() + "#echo").degraded());
    }

    @Test
    public void successPassesThroughWithoutFallback() throws Exception {
        FallbackFilter filter = new FallbackFilter(new EchoFallback());
        Result r = filter.invoke(new Invoker() {
            @Override public Class<?> interfaceClass() { return EchoService.class; }
            @Override public CompletableFuture<Result> invoke(Invocation inv) {
                return CompletableFuture.completedFuture(DefaultResult.success("real"));
            }
        }, invocation("echo", "x")).get();
        assertEquals("real", r.value());
    }

    @Test
    public void asyncFallbackFutureIsUnwrapped() throws Exception {   // Review Focus #3
        FallbackFilter filter = new FallbackFilter(new EchoFallback());
        Result r = filter.invoke(failing(Status.TIMEOUT), invocation("echoAsync", "x")).get();
        assertEquals(Status.SUCCESS, r.status());
        assertEquals("fallback-async:x", r.value());
    }

    @Test
    public void throwingFallbackKeepsOriginalSuppressed() throws Exception {
        FallbackFilter filter = new FallbackFilter(new ThrowingFallback());
        Result r = filter.invoke(failing(Status.SERVER_ERROR), invocation("echo", "x")).get();
        assertEquals("沿用原失败状态", Status.SERVER_ERROR, r.status());
        assertTrue(r.exception() instanceof RpcException);
        assertTrue("消息应指向 fallback 失败: " + r.exception().getMessage(),
                r.exception().getMessage() != null
                        && r.exception().getMessage().contains("fallback invocation failed"));
        assertEquals("原失败进 suppressed", 1, r.exception().getSuppressed().length);
    }

    @Test
    public void chainLevelThrowableTriggersFallback() throws Exception {
        FallbackFilter filter = new FallbackFilter(new EchoFallback());
        Invoker exploding = new Invoker() {
            @Override public Class<?> interfaceClass() { return EchoService.class; }
            @Override public CompletableFuture<Result> invoke(Invocation inv) {
                CompletableFuture<Result> f = new CompletableFuture<Result>();
                f.completeExceptionally(new RpcException("chain exploded"));
                return f;
            }
        };
        Result r = filter.invoke(exploding, invocation("echo", "x")).get();
        assertEquals(Status.SUCCESS, r.status());
        assertEquals("fallback:x", r.value());
    }

    private static RpcMetrics.Stat findStat(String key) {
        for (RpcMetrics.Stat s : RpcMetrics.getInstance().snapshot()) {
            if (s.key().equals(key)) {
                return s;
            }
        }
        throw new AssertionError("missing stat " + key);
    }
}
