package io.github.upowerman.core.result;

import io.github.upowerman.exception.RpcException;

/**
 * Result → 业务值/异常 的统一解包规则：成功返回 value；失败抛 {@link RpcException}
 * （保留原始 cause）。同步调用路径直接抛出；异步路径在 thenApply 里抛出转为异常结算。
 */
public final class Results {

    private Results() {
    }

    public static Object unwrap(Result result) {
        if (result.status() == Status.SUCCESS) {
            return result.value();
        }
        throw toRpcException(result);
    }

    public static RpcException toRpcException(Result result) {
        Throwable cause = result.exception();
        if (cause instanceof RpcException) {
            return (RpcException) cause;
        }
        if (cause != null) {
            return new RpcException(cause);
        }
        return new RpcException("rpc call failed, status: " + result.status());
    }
}
