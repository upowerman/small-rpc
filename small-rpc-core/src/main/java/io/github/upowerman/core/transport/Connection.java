package io.github.upowerman.core.transport;

import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.result.Result;

import java.util.concurrent.CompletableFuture;

/**
 * 一条已建立的连接。协议帧编解码封装在实现内部（NettyConnection / LegacyConnection），
 * 接口层保持 Invocation → CompletableFuture&lt;Result&gt; 语义，上层组件与传输实现解耦。
 */
public interface Connection {

    CompletableFuture<Result> request(Invocation invocation);

    void close();
}