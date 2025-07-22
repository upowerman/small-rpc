package io.github.upowerman.util;

import io.github.upowerman.exception.RpcException;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Enhanced Thread Pool Utility with better performance and monitoring
 * 
 * @author gaoyunfeng
 */
public class ThreadPoolUtil {

    /**
     * Generate optimized thread pool with better naming and rejection policy
     *
     * @param serverType   服务类型 ：NettyServer | NettyClient
     * @param corePoolSize 核心线程数
     * @param maxPoolSize  最大线程数
     * @return ThreadPoolExecutor
     */
    public static ThreadPoolExecutor makeServerThreadPool(final String serverType, int corePoolSize, int maxPoolSize) {
        // Validate parameters
        if (corePoolSize <= 0 || maxPoolSize <= 0 || maxPoolSize < corePoolSize) {
            throw new IllegalArgumentException("Invalid thread pool parameters: core=" + corePoolSize + ", max=" + maxPoolSize);
        }
        
        ThreadPoolExecutor serverHandlerPool = new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1000),
                new NamedThreadFactory(serverType),
                new CallerRunsPolicy(serverType));

        // Allow core threads to timeout for better resource management
        serverHandlerPool.allowCoreThreadTimeOut(true);
        
        return serverHandlerPool;
    }

    /**
     * Named thread factory for better debugging and monitoring
     */
    private static class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String namePrefix;

        NamedThreadFactory(String serverType) {
            this.namePrefix = "RPC-" + serverType + "-pool-thread-";
        }

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, namePrefix + threadNumber.getAndIncrement());
            thread.setDaemon(false);
            thread.setPriority(Thread.NORM_PRIORITY);
            return thread;
        }
    }

    /**
     * Custom rejection policy that provides better error information
     */
    private static class CallerRunsPolicy implements RejectedExecutionHandler {
        private final String serverType;

        CallerRunsPolicy(String serverType) {
            this.serverType = serverType;
        }

        @Override
        public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
            if (!executor.isShutdown()) {
                // Log warning and execute in caller thread as fallback
                System.err.println("WARN: RPC " + serverType + " thread pool is busy, executing in caller thread");
                r.run();
            } else {
                throw new RpcException("RPC " + serverType + " thread pool is EXHAUSTED and shutdown!");
            }
        }
    }
}
