package io.github.upowerman.core.filter;

import io.github.upowerman.core.RpcConstants;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.result.Result;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * trace 透传：无 traceId 时生成一个，随 attachments 传递
 */
public class TraceFilter implements Filter {

    @Override
    public CompletableFuture<Result> invoke(Invoker next, Invocation invocation) {
        if (!invocation.attachments().containsKey(RpcConstants.ATTACH_TRACE_ID)) {
            invocation.attachments().put(RpcConstants.ATTACH_TRACE_ID, UUID.randomUUID().toString());
        }
        return next.invoke(invocation);
    }
}