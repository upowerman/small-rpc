package io.github.upowerman.spring;

import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.transport.Connection;
import io.github.upowerman.core.transport.Endpoint;
import io.github.upowerman.core.transport.Transport;

import java.util.concurrent.CompletableFuture;

public class EchoStubTransport implements Transport {
    @Override
    public Connection connect(Endpoint endpoint) {
        return new Connection() {
            @Override
            public CompletableFuture<Result> request(Invocation invocation) {
                return CompletableFuture.completedFuture(DefaultResult.success("echo"));
            }

            @Override
            public void close() {
            }
        };
    }
}
