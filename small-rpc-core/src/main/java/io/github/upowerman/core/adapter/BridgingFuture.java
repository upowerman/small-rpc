package io.github.upowerman.core.adapter;

import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Status;
import io.github.upowerman.core.transport.PendingRequests;
import io.github.upowerman.exception.RpcException;
import io.github.upowerman.invoker.RpcInvokerFactory;
import io.github.upowerman.net.base.RpcFutureResponse;
import io.github.upowerman.net.base.RpcRequest;
import io.github.upowerman.net.base.RpcResponse;

/**
 * RpcFutureResponse 子类：构造时自注册进 1.x future 池（super 副作用），
 * 响应到达时把结果桥接给新链路的 PendingRequests。
 */
final class BridgingFuture extends RpcFutureResponse {

    private final long requestId;
    private final PendingRequests pending;

    BridgingFuture(RpcInvokerFactory invokerFactory, RpcRequest request,
                   long requestId, PendingRequests pending) {
        super(invokerFactory, request);
        this.requestId = requestId;
        this.pending = pending;
    }

    @Override
    public void setResponse(RpcResponse response) {
        super.setResponse(response);
        if (response.getErrorMsg() != null) {
            // P1 引入状态码后按错误内容映射到 SERVICE_NOT_FOUND 等
            pending.complete(requestId, DefaultResult.failure(Status.SERVER_ERROR,
                    new RpcException(response.getErrorMsg())));
        } else {
            pending.complete(requestId, DefaultResult.success(response.getResult()));
        }
    }
}