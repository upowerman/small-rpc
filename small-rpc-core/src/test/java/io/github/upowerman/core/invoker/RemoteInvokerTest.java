package io.github.upowerman.core.invoker;

import io.github.upowerman.core.RpcConstants;
import io.github.upowerman.core.invocation.GenericInvocation;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import io.github.upowerman.core.transport.Connection;
import io.github.upowerman.core.transport.Endpoint;
import io.github.upowerman.core.transport.InMemoryTransport;
import io.github.upowerman.core.transport.Transport;
import org.junit.Test;

import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class RemoteInvokerTest {

    public interface EchoService {
    }

    private Invocation invocation(String address) {
        HashMap<String, Object> attachments = new HashMap<>();
        if (address != null) {
            attachments.put(RpcConstants.ATTACH_ADDRESS, address);
        }
        return new GenericInvocation("com.test.EchoService", "echo",
                new Class<?>[0], new Object[0], attachments);
    }

    @Test
    public void missingAddressAttachmentFailsWithNetworkError() throws Exception {
        RemoteInvoker remote = new RemoteInvoker(new InMemoryTransport(), EchoService.class);
        Result result = remote.invoke(invocation(null)).get(1, TimeUnit.SECONDS);
        assertSame(Status.NETWORK_ERROR, result.status());
    }

    @Test
    public void delegatesToTransportConnection() throws Exception {
        InMemoryTransport transport = new InMemoryTransport();
        transport.register("127.0.0.1:7080", new Invoker() {
            @Override
            public Class<?> interfaceClass() {
                return Object.class;
            }

            @Override
            public CompletableFuture<Result> invoke(Invocation inv) {
                return CompletableFuture.completedFuture(DefaultResult.success("ok"));
            }
        });
        RemoteInvoker remote = new RemoteInvoker(transport, EchoService.class);

        Result result = remote.invoke(invocation("127.0.0.1:7080")).get(1, TimeUnit.SECONDS);

        assertEquals("ok", result.value());
    }

    @Test
    public void transportFailureBecomesNetworkError() throws Exception {
        RemoteInvoker remote = new RemoteInvoker(new InMemoryTransport(), EchoService.class);
        Result result = remote.invoke(invocation("127.0.0.1:9999")).get(1, TimeUnit.SECONDS);
        assertSame(Status.NETWORK_ERROR, result.status());
    }

    /** Connection.request 抛同步异常也要转 NETWORK_ERROR */
    @Test
    public void connectionSyncExceptionBecomesNetworkError() throws Exception {
        Transport broken = new Transport() {
            @Override
            public Connection connect(Endpoint endpoint) {
                return new Connection() {
                    @Override
                    public CompletableFuture<Result> request(Invocation invocation) {
                        throw new IllegalStateException("connection broken");
                    }

                    @Override
                    public void close() {
                    }
                };
            }
        };
        RemoteInvoker remote = new RemoteInvoker(broken, EchoService.class);
        Result result = remote.invoke(invocation("127.0.0.1:1")).get(1, TimeUnit.SECONDS);
        assertSame(Status.NETWORK_ERROR, result.status());
    }
}
