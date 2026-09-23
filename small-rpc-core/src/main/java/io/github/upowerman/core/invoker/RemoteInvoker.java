package io.github.upowerman.core.invoker;

import io.github.upowerman.core.RpcConstants;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import io.github.upowerman.core.transport.Endpoint;
import io.github.upowerman.core.transport.Transport;
import io.github.upowerman.exception.RpcException;

import java.util.concurrent.CompletableFuture;

/**
 * Consumer 端远程 Invoker：从 attachments 取目标地址，经 Transport 发起调用。
 * 目标地址由 ClusterInvoker 选好实例后写入（每次重试可能不同）。
 */
public class RemoteInvoker implements Invoker {

    private final Transport transport;
    private final Class<?> interfaceClass;

    public RemoteInvoker(Transport transport, Class<?> interfaceClass) {
        this.transport = transport;
        this.interfaceClass = interfaceClass;
    }

    @Override
    public Class<?> interfaceClass() {
        return interfaceClass;
    }

    @Override
    public CompletableFuture<Result> invoke(Invocation invocation) {
        Object address = invocation.attachments().get(RpcConstants.ATTACH_ADDRESS);
        if (address == null || address.toString().trim().isEmpty()) {
            return CompletableFuture.completedFuture(DefaultResult.failure(Status.NETWORK_ERROR,
                    new RpcException("invocation missing attachment: " + RpcConstants.ATTACH_ADDRESS)));
        }
        try {
            return transport.connect(Endpoint.of(address.toString())).request(invocation);
        } catch (Exception e) {
            return CompletableFuture.completedFuture(DefaultResult.failure(Status.NETWORK_ERROR, e));
        }
    }
}
