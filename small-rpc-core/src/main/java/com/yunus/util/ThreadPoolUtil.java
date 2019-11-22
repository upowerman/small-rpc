package com.yunus.util;

import com.yunus.exception.RpcException;

import java.util.concurrent.*;

/**
 * @author gaoyunfeng
 */
public class ThreadPoolUtil {

    /**
     * 生成线程池
     *
     * @param serverType   服务类型 ：NettyServer | NettyClient
     * @param corePoolSize 核心线程数
     * @param maxPoolSize  最大线程数
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
                    @Override
                    public Thread newThread(Runnable r) {
                        return new Thread(r, "RPC, " + serverType + "-serverHandlerPool-" + r.hashCode());
                    }
                },
                new RejectedExecutionHandler() {
                    @Override
                    public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
                        throw new RpcException("RPC " + serverType + " Thread pool is EXHAUSTED!");
                    }
                });

        return serverHandlerPool;
    }

}
