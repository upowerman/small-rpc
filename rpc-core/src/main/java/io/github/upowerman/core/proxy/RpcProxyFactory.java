package io.github.upowerman.core.proxy;

import io.github.upowerman.core.filter.Filter;
import io.github.upowerman.core.filter.FilterChain;
import io.github.upowerman.core.invocation.GenericInvocation;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import io.github.upowerman.exception.RpcException;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 2.0 代理工厂：InvocationHandler 只做 Invocation 构造 + 发起调用，
 * 与 1.x 上帝类 RpcReferenceInvocationHandler 的对应物。
 * <p>
 * 外层 {@code get(...)} 使用 {@link #callTimeoutMillis} 作为有界兜底：即使下游链路
 * （非正预算 + 静默上游等极端情形）意外未完成，调用也不会永久悬挂，超时抛出
 * {@link RpcException}。正常路径下 ClusterInvoker 会在其 deadline 内先行结算。
 */
public class RpcProxyFactory<T> {

    /** 外层 get 的兜底超时（毫秒）。仅作有界兜底，应大于任何常规 ClusterInvoker 预算。 */
    private static final long DEFAULT_CALL_TIMEOUT_MILLIS = 60000L;

    private final Class<T> interfaceClass;
    private final Invoker chainHead;
    private final long callTimeoutMillis;

    public RpcProxyFactory(Class<T> interfaceClass, List<Filter> filters, Invoker terminal) {
        this(interfaceClass, filters, terminal, DEFAULT_CALL_TIMEOUT_MILLIS);
    }

    public RpcProxyFactory(Class<T> interfaceClass, List<Filter> filters, Invoker terminal,
                           long callTimeoutMillis) {
        this.interfaceClass = interfaceClass;
        this.chainHead = FilterChain.build(filters, terminal);
        this.callTimeoutMillis = callTimeoutMillis;
    }

    @SuppressWarnings("unchecked")
    public T getProxy() {
        return (T) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[]{interfaceClass},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        throw new RpcException("proxy object does not support Object method: "
                                + method.getName());
                    }
                    Invocation invocation = new GenericInvocation(interfaceClass.getName(),
                            method.getName(), method.getParameterTypes(), args,
                            new HashMap<String, Object>());
                    Result result = await(invocation);
                    if (result.status() == Status.SUCCESS) {
                        return result.value();
                    }
                    Throwable cause = result.exception();
                    if (cause instanceof RpcException) {
                        throw (RpcException) cause;
                    }
                    if (cause != null) {
                        throw new RpcException(cause);
                    }
                    throw new RpcException("rpc call failed, status: " + result.status());
                });
    }

    private Result await(Invocation invocation) {
        try {
            return chainHead.invoke(invocation).get(callTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RpcException("rpc call timed out after " + callTimeoutMillis + "ms: "
                    + invocation.serviceName() + "#" + invocation.methodName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RpcException("rpc call interrupted: "
                    + invocation.serviceName() + "#" + invocation.methodName(), e);
        } catch (ExecutionException e) {
            throw new RpcException(e.getCause() != null ? e.getCause() : e);
        }
    }
}
