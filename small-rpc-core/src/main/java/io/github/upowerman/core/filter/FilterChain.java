package io.github.upowerman.core.filter;

import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.result.Result;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 把 filters 串成责任链，链尾是 terminal Invoker（通常是 ClusterInvoker）
 */
public final class FilterChain {

    private FilterChain() {
    }

    public static Invoker build(List<Filter> filters, Invoker terminal) {
        Invoker next = terminal;
        for (int i = filters.size() - 1; i >= 0; i--) {
            next = new FilterNode(filters.get(i), next);
        }
        return next;
    }

    static final class FilterNode implements Invoker {

        private final Filter filter;
        private final Invoker next;

        FilterNode(Filter filter, Invoker next) {
            this.filter = filter;
            this.next = next;
        }

        @Override
        public Class<?> interfaceClass() {
            return next.interfaceClass();
        }

        @Override
        public CompletableFuture<Result> invoke(Invocation invocation) {
            return filter.invoke(next, invocation);
        }
    }
}