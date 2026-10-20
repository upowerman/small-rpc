package io.github.upowerman.core.generic;

import io.github.upowerman.core.directory.ServiceDirectory;
import io.github.upowerman.core.invocation.GenericInvocation;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Results;
import io.github.upowerman.exception.RpcException;

import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 泛化调用默认实现：每次调用按 serviceName 懒建立目录订阅（幂等），参数类型名在
 * 消费端 classpath 解析（基本类型缩写映射 + {@link Class#forName}）。
 * 同步 $invoke 的外层 get 兜底 = 预算 + 余量（与 ReferenceBeanPostProcessor 同一规则）。
 */
final class DefaultGenericService implements GenericService {

    /** 外层 get 兜底相对 ClusterInvoker 预算的余量（毫秒） */
    private static final long OUTER_GET_MARGIN_MILLIS = 1000L;

    /** 基本类型缩写名 → Class（Class.forName 不认识 "int" 这类缩写） */
    private static final HashMap<String, Class<?>> PRIMITIVES = new HashMap<String, Class<?>>();

    static {
        PRIMITIVES.put("boolean", Boolean.TYPE);
        PRIMITIVES.put("byte", Byte.TYPE);
        PRIMITIVES.put("char", Character.TYPE);
        PRIMITIVES.put("short", Short.TYPE);
        PRIMITIVES.put("int", Integer.TYPE);
        PRIMITIVES.put("long", Long.TYPE);
        PRIMITIVES.put("float", Float.TYPE);
        PRIMITIVES.put("double", Double.TYPE);
        PRIMITIVES.put("void", Void.TYPE);
        PRIMITIVES.put("boolean[]", boolean[].class);
        PRIMITIVES.put("byte[]", byte[].class);
        PRIMITIVES.put("char[]", char[].class);
        PRIMITIVES.put("short[]", short[].class);
        PRIMITIVES.put("int[]", int[].class);
        PRIMITIVES.put("long[]", long[].class);
        PRIMITIVES.put("float[]", float[].class);
        PRIMITIVES.put("double[]", double[].class);
    }

    private final ServiceDirectory directory;
    private final Invoker chainHead;
    private final long timeoutMillis;

    DefaultGenericService(ServiceDirectory directory, Invoker chainHead, long timeoutMillis) {
        this.directory = directory;
        this.chainHead = chainHead;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public Object $invoke(String serviceName, String methodName,
                          String[] parameterTypeNames, Object[] args) {
        CompletableFuture<Object> future = $invokeAsync(serviceName, methodName, parameterTypeNames, args);
        try {
            return future.get(timeoutMillis + OUTER_GET_MARGIN_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RpcException("generic invoke timed out: " + serviceName + "#" + methodName, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RpcException("generic invoke interrupted: " + serviceName + "#" + methodName, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RpcException) {
                throw (RpcException) cause;
            }
            throw new RpcException(cause);
        }
    }

    @Override
    public CompletableFuture<Object> $invokeAsync(String serviceName, String methodName,
                                                  String[] parameterTypeNames, Object[] args) {
        // 订阅幂等：同服务首次调用建立，之后零开销
        directory.subscribe(serviceName);
        Invocation invocation = new GenericInvocation(serviceName, methodName,
                resolveParameterTypes(serviceName, methodName, parameterTypeNames),
                args == null ? new Object[0] : args, new HashMap<String, Object>());
        try {
            return chainHead.invoke(invocation).thenApply(Results::unwrap);
        } catch (RpcException e) {
            CompletableFuture<Object> failed = new CompletableFuture<Object>();
            failed.completeExceptionally(e);
            return failed;
        }
    }

    /** 参数类型名解析：基本类型缩写表 → Class.forName；找不到大声失败（含类型名与方法签名） */
    static Class<?>[] resolveParameterTypes(String serviceName, String methodName, String[] typeNames) {
        if (typeNames == null || typeNames.length == 0) {
            return new Class<?>[0];
        }
        Class<?>[] types = new Class<?>[typeNames.length];
        for (int i = 0; i < typeNames.length; i++) {
            String name = typeNames[i];
            Class<?> primitive = PRIMITIVES.get(name);
            if (primitive != null) {
                types[i] = primitive;
                continue;
            }
            try {
                types[i] = Class.forName(name, false, Thread.currentThread().getContextClassLoader());
            } catch (ClassNotFoundException e) {
                throw new RpcException("generic invoke 参数类型在消费端 classpath 不可见: " + name
                        + " (" + serviceName + "#" + methodName + ")", e);
            }
        }
        return types;
    }
}
