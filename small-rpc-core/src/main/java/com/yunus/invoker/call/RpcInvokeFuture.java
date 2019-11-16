package com.yunus.invoker.call;

import com.yunus.exception.RpcException;
import com.yunus.net.base.RpcFutureResponse;
import com.yunus.net.base.RpcResponse;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * @author gaoyunfeng
 */
public class RpcInvokeFuture implements Future {

    private RpcFutureResponse futureResponse;

    private static ThreadLocal<RpcInvokeFuture> threadInvokerFuture = new ThreadLocal<RpcInvokeFuture>();

    public RpcInvokeFuture(RpcFutureResponse futureResponse) {
        this.futureResponse = futureResponse;
    }


    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        return futureResponse.cancel(mayInterruptIfRunning);
    }

    @Override
    public boolean isCancelled() {
        return futureResponse.isCancelled();
    }

    @Override
    public boolean isDone() {
        return futureResponse.isDone();
    }

    @Override
    public Object get() throws ExecutionException, InterruptedException {
        try {
            return get(-1, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new RpcException(e);
        }
    }

    @Override
    public Object get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
        try {
            RpcResponse response = futureResponse.get(timeout, unit);
            if (response.getErrorMsg() != null) {
                throw new RpcException(response.getErrorMsg());
            }
            return response.getResult();
        } finally {
            stop();
        }
    }

    public void stop() {
        futureResponse.removeInvokerFuture();
    }



    /**
     * get future
     *
     * @param type
     * @param <T>
     * @return
     */
    public static <T> Future<T> getFuture(Class<T> type) {
        Future<T> future = (Future<T>) threadInvokerFuture.get();
        threadInvokerFuture.remove();
        return future;
    }

    /**
     * set future
     *
     * @param future
     */
    public static void setFuture(RpcInvokeFuture future) {
        threadInvokerFuture.set(future);
    }

    /**
     * remove future
     */
    public static void removeFuture() {
        threadInvokerFuture.remove();
    }
}
