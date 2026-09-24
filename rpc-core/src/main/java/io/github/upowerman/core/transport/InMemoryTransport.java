package io.github.upowerman.core.transport;

import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import io.github.upowerman.exception.RpcException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存 Transport：绕过网络直接调 registered provider，供单元测试与全链路测试用
 */
public class InMemoryTransport implements Transport {

    private final ConcurrentMap<String, Invoker> providers = new ConcurrentHashMap<String, Invoker>();

    public void register(String address, Invoker provider) {
        providers.put(address, provider);
    }

    @Override
    public Connection connect(Endpoint endpoint) {
        Invoker provider = providers.get(endpoint.address());
        if (provider == null) {
            throw new RpcException("no in-memory provider at " + endpoint.address());
        }
        return new InMemoryConnection(provider);
    }

    static final class InMemoryConnection implements Connection {

        private final Invoker provider;

        InMemoryConnection(Invoker provider) {
            this.provider = provider;
        }

        @Override
        public CompletableFuture<Result> request(Invocation invocation) {
            try {
                return provider.invoke(invocation);
            } catch (Exception e) {
                return CompletableFuture.completedFuture(DefaultResult.failure(Status.NETWORK_ERROR, e));
            }
        }

        @Override
        public void close() {
            // 内存连接无需关闭
        }
    }
}