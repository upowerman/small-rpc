package io.github.upowerman.core.transport;

import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class PendingRequestsTest {

    @Test
    public void requestIdIncrements() {
        PendingRequests pending = new PendingRequests();
        assertEquals(1L, pending.nextRequestId());
        assertEquals(2L, pending.nextRequestId());
    }

    @Test
    public void completeResolvesFutureAndRemovesEntry() throws Exception {
        PendingRequests pending = new PendingRequests();
        long id = pending.nextRequestId();
        CompletableFuture<Result> future = pending.register(id);
        assertEquals(1, pending.size());

        pending.complete(id, DefaultResult.success("ok"));

        assertSame("ok", future.get(1, TimeUnit.SECONDS).value());
        assertEquals(0, pending.size());
    }

    @Test
    public void completeUnknownIdIsIgnored() {
        PendingRequests pending = new PendingRequests();
        pending.complete(999L, DefaultResult.success("ok"));
        assertEquals(0, pending.size());
    }

    @Test
    public void removeDropsPendingEntry() {
        PendingRequests pending = new PendingRequests();
        long id = pending.nextRequestId();
        pending.register(id);
        pending.remove(id);
        assertEquals(0, pending.size());

        // remove 后再 complete：未知 id 静默忽略，不会悬挂
        pending.complete(id, DefaultResult.success("x"));
        assertEquals(0, pending.size());
    }
}