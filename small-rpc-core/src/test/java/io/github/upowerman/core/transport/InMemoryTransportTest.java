package io.github.upowerman.core.transport;

import io.github.upowerman.core.invocation.GenericInvocation;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.exception.RpcException;
import org.junit.Test;

import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class InMemoryTransportTest {

    @Test
    public void routesRequestToRegisteredProvider() throws Exception {
        InMemoryTransport transport = new InMemoryTransport();
        transport.register("127.0.0.1:7080", new Invoker() {
            @Override
            public Class<?> interfaceClass() {
                return Object.class;
            }

            @Override
            public CompletableFuture<Result> invoke(Invocation invocation) {
                return CompletableFuture.completedFuture(
                        DefaultResult.success("pong:" + invocation.arguments()[0]));
            }
        });

        Connection connection = transport.connect(Endpoint.of("127.0.0.1:7080"));
        Invocation invocation = new GenericInvocation("com.test.EchoService", "echo",
                new Class<?>[]{String.class}, new Object[]{"ping"}, new HashMap<String, Object>());

        Result result = connection.request(invocation).get(1, TimeUnit.SECONDS);
        assertEquals("pong:ping", result.value());
    }

    @Test
    public void connectUnknownAddressThrows() {
        InMemoryTransport transport = new InMemoryTransport();
        try {
            transport.connect(Endpoint.of("127.0.0.1:9999"));
            fail("expected RpcException");
        } catch (RpcException expected) {
            // ok
        }
    }
}