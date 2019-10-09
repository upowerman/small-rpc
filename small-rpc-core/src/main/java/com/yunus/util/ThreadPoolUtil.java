package com.yunus.util;

import com.yunus.exception.RpcException;

import java.util.concurrent.*;

/**
 * @author xuxueli 2019-02-18
 */
public class ThreadPoolUtil {

    /**
     * make server thread pool
     *
     * @param serverType
     * @return
     */
    public static ThreadPoolExecutor makeServerThreadPool(final String serverType, int corePoolSize, int maxPoolSize) {
        ThreadPoolExecutor serverHandlerPool = new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>(1000),
                new ThreadFactory() {
                    public Thread newThread(Runnable r) {
                        return new Thread(r, "xxl-rpc, " + serverType + "-serverHandlerPool-" + r.hashCode());
                    }
                },
                new RejectedExecutionHandler() {
                    public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
                        throw new RpcException("xxl-rpc " + serverType + " Thread pool is EXHAUSTED!");
                    }
                });        // default maxThreads 300, minThreads 60

        return serverHandlerPool;
    }

}
