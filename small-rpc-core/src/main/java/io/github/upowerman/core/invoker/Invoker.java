package io.github.upowerman.core.invoker;

import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.result.Result;

import java.util.concurrent.CompletableFuture;

/**
 * 调用链上所有可执行节点的统一抽象
 */
public interface Invoker {

    Class<?> interfaceClass();

    CompletableFuture<Result> invoke(Invocation invocation);
}