package io.github.upowerman.core.provider;

import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

/**
 * Provider 端 Invoker：把 Invocation 反射调用到本地 serviceBean。
 * 所有失败都转成带状态码的 Result，不抛裸异常。
 */
public class ReflectiveInvoker implements Invoker {

    private final Class<?> interfaceClass;
    private final Object serviceBean;

    public ReflectiveInvoker(Class<?> interfaceClass, Object serviceBean) {
        this.interfaceClass = interfaceClass;
        this.serviceBean = serviceBean;
    }

    @Override
    public Class<?> interfaceClass() {
        return interfaceClass;
    }

    @Override
    public CompletableFuture<Result> invoke(Invocation invocation) {
        try {
            Method method = serviceBean.getClass()
                    .getMethod(invocation.methodName(), invocation.parameterTypes());
            method.setAccessible(true);
            Object value = method.invoke(serviceBean, invocation.arguments());
            return CompletableFuture.completedFuture(DefaultResult.success(value));
        } catch (NoSuchMethodException e) {
            return CompletableFuture.completedFuture(DefaultResult.failure(Status.METHOD_NOT_FOUND, e));
        } catch (InvocationTargetException e) {
            return CompletableFuture.completedFuture(
                    DefaultResult.failure(Status.SERVER_ERROR, e.getTargetException()));
        } catch (Exception e) {
            return CompletableFuture.completedFuture(DefaultResult.failure(Status.SERVER_ERROR, e));
        }
    }
}