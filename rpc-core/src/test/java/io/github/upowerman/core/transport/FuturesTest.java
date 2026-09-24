package io.github.upowerman.core.transport;

import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class FuturesTest {

    @Test
    public void returnsUpstreamValueWhenCompletedInTime() throws Exception {
        CompletableFuture<Result> upstream = new CompletableFuture<>();
        upstream.complete(DefaultResult.success("fast"));

        Result result = Futures.withTimeout(upstream, 1000L,
                () -> DefaultResult.failure(io.github.upowerman.core.result.Status.TIMEOUT))
                .get(2, TimeUnit.SECONDS);

        assertEquals("fast", result.value());
    }

    @Test
    public void completesWithTimeoutValueWhenUpstreamIsSilent() throws Exception {
        CompletableFuture<Result> upstream = new CompletableFuture<>();

        Result result = Futures.withTimeout(upstream, 50L,
                () -> DefaultResult.failure(io.github.upowerman.core.result.Status.TIMEOUT))
                .get(2, TimeUnit.SECONDS);

        assertSame(io.github.upowerman.core.result.Status.TIMEOUT, result.status());
    }

    @Test
    public void nonPositiveTimeoutReturnsSameFuture() {
        CompletableFuture<Result> upstream = new CompletableFuture<>();
        assertSame(upstream, Futures.withTimeout(upstream, 0L, null));
    }
}