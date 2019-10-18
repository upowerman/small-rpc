package com.yunus.invoker;

import com.yunus.exception.RpcException;
import com.yunus.net.base.BaseCallback;
import com.yunus.net.base.RpcFutureResponse;
import com.yunus.net.base.RpcResponse;
import com.yunus.registry.BaseServiceRegistry;
import com.yunus.registry.impl.LocalServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * @author gaoyunfeng
 */
public class RpcInvokerFactory {

    private static Logger logger = LoggerFactory.getLogger(RpcInvokerFactory.class);

    private static volatile RpcInvokerFactory instance = new RpcInvokerFactory(LocalServiceRegistry.class, null);

    private Class<? extends BaseServiceRegistry> serviceRegistryClass;
    private Map<String, String> serviceRegistryParam;

    private BaseServiceRegistry serviceRegistry;

    /**
     * 服务停止时的回调函数
     */
    private List<BaseCallback> stopCallbackList = new ArrayList<BaseCallback>();

    private ThreadPoolExecutor responseCallbackThreadPool = null;

    /**
     * 请求cache
     * key-requestId
     * value-响应体
     */
    private ConcurrentMap<String, RpcFutureResponse> futureResponsePool = new ConcurrentHashMap<String, RpcFutureResponse>();

    public RpcInvokerFactory() {
    }

    public RpcInvokerFactory(Class<? extends BaseServiceRegistry> serviceRegistryClass, Map<String, String> serviceRegistryParam) {
        this.serviceRegistryClass = serviceRegistryClass;
        this.serviceRegistryParam = serviceRegistryParam;
    }


    public void start() throws Exception {
        if (serviceRegistryClass != null) {
            serviceRegistry = serviceRegistryClass.newInstance();
            serviceRegistry.start(serviceRegistryParam);
        }
    }

    public void stop() throws Exception {
        if (serviceRegistry != null) {
            serviceRegistry.stop();
        }
        if (stopCallbackList.size() > 0) {
            for (BaseCallback callback : stopCallbackList) {
                try {
                    callback.run();
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
            }
        }
        stopCallbackThreadPool();
    }

    public void notifyInvokerFuture(String requestId, final RpcResponse response) {
        final RpcFutureResponse futureResponse = futureResponsePool.get(requestId);
        if (futureResponse == null) {
            return;
        }
        if (futureResponse.getInvokeCallback() != null) {
            try {
                executeResponseCallback(new Runnable() {
                    @Override
                    public void run() {
                        if (response.getErrorMsg() != null) {
                            futureResponse.getInvokeCallback().onFailure(new RpcException(response.getErrorMsg()));
                        } else {
                            futureResponse.getInvokeCallback().onSuccess(response.getResult());
                        }
                    }
                });
            } catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        } else {
            futureResponse.setResponse(response);
        }
        // 移除 response
        futureResponsePool.remove(requestId);
    }

    public void executeResponseCallback(Runnable runnable) {
        if (responseCallbackThreadPool == null) {
            synchronized (this) {
                if (responseCallbackThreadPool == null) {
                    responseCallbackThreadPool = new ThreadPoolExecutor(
                            10,
                            100,
                            60L,
                            TimeUnit.SECONDS,
                            new LinkedBlockingQueue<Runnable>(1000),
                            new ThreadFactory() {
                                @Override
                                public Thread newThread(Runnable r) {
                                    return new Thread(r, "rpc, RpcInvokerFactory-responseCallbackThreadPool-" + r.hashCode());
                                }
                            },
                            new RejectedExecutionHandler() {
                                @Override
                                public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
                                    throw new RpcException("rpc Invoke Callback Thread pool is EXHAUSTED!");
                                }
                            });
                }
            }
        }
        responseCallbackThreadPool.execute(runnable);
    }

    public BaseServiceRegistry getServiceRegistry() {
        return serviceRegistry;
    }

    public void addStopCallBack(BaseCallback callback) {
        stopCallbackList.add(callback);
    }

    public void setInvokerFuture(String requestId, RpcFutureResponse futureResponse) {
        futureResponsePool.put(requestId, futureResponse);
    }

    public void removeInvokerFuture(String requestId) {
        futureResponsePool.remove(requestId);
    }

    public void stopCallbackThreadPool() {
        if (responseCallbackThreadPool != null) {
            responseCallbackThreadPool.shutdown();
        }
    }

    public static RpcInvokerFactory getInstance() {
        return instance;
    }
}
