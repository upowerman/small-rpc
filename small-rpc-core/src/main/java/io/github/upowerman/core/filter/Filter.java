package io.github.upowerman.core.filter;

import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.result.Result;

import java.util.concurrent.CompletableFuture;

/**
 * 横切逻辑过滤器，装饰器模式串成责任链
 */
public interface Filter {

    CompletableFuture<Result> invoke(Invoker next, Invocation invocation);
}