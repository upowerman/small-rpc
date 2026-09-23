package io.github.upowerman.core.transport;

import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.result.Result;

import java.util.concurrent.CompletableFuture;

/**
 * 一条已建立的连接。P0 入参是 Invocation；P1 协议化后改为 RpcMessage 请求对象。
 */
public interface Connection {

    CompletableFuture<Result> request(Invocation invocation);

    void close();
}