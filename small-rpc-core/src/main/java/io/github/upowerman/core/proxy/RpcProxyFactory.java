package io.github.upowerman.core.proxy;

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

/**
 * 2.0 代理工厂：InvocationHandler 只做 Invocation 构造 + 发起调用，
 * 与 1.x 上帝类 RpcReferenceInvocationHandler 的对应物。
 */
public class RpcProxyFactory<T> {

    private final Class<T> interfaceClass;
    private final Invoker chainHead;

    public RpcProxyFactory(Class<T> interfaceClass, List<io.github.upowerman.core.filter.Filter> filters,
                           Invoker terminal) {
        this.interfaceClass = interfaceClass;
        this.chainHead = FilterChain.build(filters, terminal);
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
                    Result result = chainHead.invoke(invocation).get();
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
}