package io.github.upowerman.core.filter;

import io.github.upowerman.core.RpcConstants;
import io.github.upowerman.core.invocation.GenericInvocation;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

public class FilterChainTest {

    /** 记录经过顺序的测试 Filter */
    static class OrderFilter implements Filter {
        private final String name;
        private final List<String> order;

        OrderFilter(String name, List<String> order) {
            this.name = name;
            this.order = order;
        }

        @Override
        public CompletableFuture<Result> invoke(Invoker next, Invocation invocation) {
            order.add(name + ":before");
            return next.invoke(invocation).thenApply(result -> {
                order.add(name + ":after");
                return result;
            });
        }
    }

    private Invoker terminal(final List<String> order) {
        return new Invoker() {
            @Override
            public Class<?> interfaceClass() {
                return Object.class;
            }

            @Override
            public CompletableFuture<Result> invoke(Invocation invocation) {
                order.add("terminal");
                return CompletableFuture.completedFuture(DefaultResult.success("ok"));
            }
        };
    }

    @Test
    public void filtersExecuteAroundTerminalInOrder() throws Exception {
        List<String> order = new ArrayList<>();
        Invoker head = FilterChain.build(
                Arrays.asList(new OrderFilter("a", order), new OrderFilter("b", order)),
                terminal(order));

        assertEquals("ok", head.invoke(anyInvocation()).get().value());
        assertEquals(Arrays.asList("a:before", "b:before", "terminal", "b:after", "a:after"), order);
    }

    @Test
    public void emptyFiltersReturnsTerminalDirectly() {
        Invoker terminal = terminal(new ArrayList<String>());
        assertSame(terminal, FilterChain.build(new ArrayList<Filter>(), terminal));
    }

    @Test
    public void traceFilterInjectsTraceId() throws Exception {
        HashMap<String, Object> attachments = new HashMap<>();
        Invoker captured = new Invoker() {
            @Override
            public Class<?> interfaceClass() {
                return Object.class;
            }

            @Override
            public CompletableFuture<Result> invoke(Invocation invocation) {
                return CompletableFuture.completedFuture(DefaultResult.success(
                        invocation.attachments().get(RpcConstants.ATTACH_TRACE_ID)));
            }
        };
        Invoker head = FilterChain.build(
                Arrays.<Filter>asList(new TraceFilter()), captured);

        Result result = head.invoke(new GenericInvocation("s", "m",
                new Class<?>[0], new Object[0], attachments)).get();
        assertNotNull(result.value());
    }

    private Invocation anyInvocation() {
        return new GenericInvocation("com.test.EchoService", "echo",
                new Class<?>[0], new Object[0], new HashMap<String, Object>());
    }
}