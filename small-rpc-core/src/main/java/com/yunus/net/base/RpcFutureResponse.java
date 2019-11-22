package com.yunus.net.base;

import com.yunus.exception.RpcException;
import com.yunus.invoker.RpcInvokerFactory;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * @author gaoyunfeng
 */
public class RpcFutureResponse implements Future<RpcResponse> {

    private RpcInvokerFactory invokerFactory;

    private RpcRequest request;

    private RpcResponse response;

    private boolean done = false;

    private Object lock = new Object();


    public RpcFutureResponse(final RpcInvokerFactory invokerFactory, RpcRequest request) {
        this.invokerFactory = invokerFactory;
        this.request = request;
        setInvokerFuture();
    }


    public void setInvokerFuture() {
        this.invokerFactory.setInvokerFuture(request.getRequestId(), this);
    }

    public void removeInvokerFuture() {
        this.invokerFactory.removeInvokerFuture(request.getRequestId());
    }


    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        // TODO
        return false;
    }

    @Override
    public boolean isCancelled() {
        // TODO
        return false;
    }

    @Override
    public boolean isDone() {
        return done;
    }

    /**
     * 设置响应 response
     *
     * @param response 返回的响应
     */
    public void setResponse(RpcResponse response) {
        this.response = response;
        synchronized (lock) {
            done = true;
            // 通知
            lock.notifyAll();
        }
    }

    @Override
    public RpcResponse get(long timeout, TimeUnit unit) throws InterruptedException {
        if (!done) {
            synchronized (lock) {
                try {
                    if (timeout < 0) {
                        lock.wait();
                    } else {
                        long timeoutMillis = (TimeUnit.MILLISECONDS == unit) ? timeout : TimeUnit.MILLISECONDS.convert(timeout, unit);
                        lock.wait(timeoutMillis);
                    }
                } catch (InterruptedException e) {
                    throw e;
                }
            }
        }
        if (!done) {
            throw new RpcException("rpc, request timeout at:" + System.currentTimeMillis() + ", request:" + request.toString());
        }
        return response;
    }

    @Override
    public RpcResponse get() throws InterruptedException {
        return get(-1, TimeUnit.MILLISECONDS);
    }

    /**
     * 获取原始的请求对象
     *
     * @return
     */
    public RpcRequest getRequest() {
        return request;
    }
}
