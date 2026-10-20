package io.github.upowerman.core.invoker;

import io.github.upowerman.core.invocation.GenericInvocation;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.metrics.RpcMetrics;
import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import io.github.upowerman.core.transport.Connection;
import io.github.upowerman.core.transport.Endpoint;
import io.github.upowerman.core.transport.Transport;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;

/** 评审修复：传输层 CLIENT_BUSY 拒绝必须在指标中按 client-busy 归因（spec §11.7） */
public class RemoteInvokerClientBusyTest {

    @Before
    public void reset() {
        RpcMetrics.getInstance().reset();
    }

    @Test
    public void clientBusyResultRecordedAsRejection() throws Exception {
        Transport busyTransport = new Transport() {
            @Override public Connection connect(Endpoint endpoint) {
                return new Connection() {
                    @Override public CompletableFuture<Result> request(Invocation invocation) {
                        return CompletableFuture.completedFuture(DefaultResult.failure(Status.CLIENT_BUSY,
                                new io.github.upowerman.exception.RpcException("client busy")));
                    }
                    @Override public void close() { }
                };
            }
        };
        RemoteInvoker invoker = new RemoteInvoker(busyTransport, EchoService.class);
        Invocation invocation = new GenericInvocation(EchoService.class.getName(), "echo",
                new Class<?>[0], new Object[0], new HashMap<String, Object>());
        // Cluster 层会先写入 ATTACH_ADDRESS；本测试直连 RemoteInvoker，补上以通过前置校验
        invocation.attachments().put(io.github.upowerman.core.RpcConstants.ATTACH_ADDRESS, "127.0.0.1:7080");

        assertEquals(Status.CLIENT_BUSY, invoker.invoke(invocation).get().status());

        String key = EchoService.class.getName() + "#echo";
        for (RpcMetrics.Stat s : RpcMetrics.getInstance().snapshot()) {
            if (s.key().equals(key)) {
                assertEquals(Long.valueOf(1L), s.rejections().get("client-busy"));
                return;
            }
        }
        throw new AssertionError("missing client-busy rejection stat for " + key);
    }

    private interface EchoService {
        String echo(String msg);
    }
}
