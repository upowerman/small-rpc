package io.github.upowerman.core.transport;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Java 8 没有 CompletableFuture.orTimeout(Java 9+)，
 * 用 applyToEither + 调度器实现"先到者赢"的超时语义
 */
public final class Futures {

    private static final ScheduledExecutorService TIMEOUT_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "small-rpc-timeout");
                t.setDaemon(true);
                return t;
            });

    private Futures() {
    }

    public static <T> CompletableFuture<T> withTimeout(CompletableFuture<T> future,
                                                       long timeoutMillis,
                                                       Supplier<T> onTimeout) {
        if (timeoutMillis <= 0) {
            return future;
        }
        CompletableFuture<T> timeout = new CompletableFuture<T>();
        ScheduledFuture<?> task = TIMEOUT_SCHEDULER.schedule(
                () -> timeout.complete(onTimeout.get()), timeoutMillis, TimeUnit.MILLISECONDS);
        return future.applyToEither(timeout, Function.identity());
    }
}