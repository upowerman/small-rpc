package io.github.upowerman.core.transport;

import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/** 客户端在途上限：超限 CLIENT_BUSY 快速结算、不占表项、完成后恢复放行 */
public class PendingRequestsCapTest {

    @Test
    public void overCapFailsFastWithClientBusy() throws Exception {
        PendingRequests pending = new PendingRequests(2);
        CompletableFuture<Result> f1 = pending.register(1L, 5000);
        CompletableFuture<Result> f2 = pending.register(2L, 5000);
        assertEquals(2, pending.size());

        CompletableFuture<Result> rejected = pending.register(3L, 5000);
        Result r = rejected.get(1, TimeUnit.SECONDS);          // Review Focus #1：立即完成
        assertEquals(Status.CLIENT_BUSY, r.status());
        assertEquals("被拒请求不入表", 2, pending.size());
        assertFalse("在途不受影响", f1.isDone());
    }

    @Test
    public void completingReleasesCapacity() throws Exception {
        PendingRequests pending = new PendingRequests(1);
        CompletableFuture<Result> f1 = pending.register(1L, 5000);
        pending.complete(1L, DefaultResult.success("v"));
        CompletableFuture<Result> f2 = pending.register(2L, 5000);
        assertFalse("空出名额后放行", f2.isDone());
        assertEquals(1, pending.size());
    }

    @Test
    public void zeroMeansUnbounded() {
        PendingRequests pending = new PendingRequests();
        for (long i = 1; i <= 100; i++) {
            pending.register(i, 600_000);
        }
        assertEquals(100, pending.size());                      // 兼容旧行为
    }
}
