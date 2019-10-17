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

    public static RpcInvokerFactory getInstance() {
        return instance;
    }


    private Class<? extends BaseServiceRegistry> serviceRegistryClass;
    private Map<String, String> serviceRegistryParam;


    public RpcInvokerFactory() {
    }

    public RpcInvokerFactory(Class<? extends BaseServiceRegistry> serviceRegistryClass, Map<String, String> serviceRegistryParam) {
        this.serviceRegistryClass = serviceRegistryClass;
        this.serviceRegistryParam = serviceRegistryParam;
    }


    public void start() throws Exception {
        // start registry
        if (serviceRegistryClass != null) {
            serviceRegistry = serviceRegistryClass.newInstance();
            serviceRegistry.start(serviceRegistryParam);
        }
    }

    public void  stop() throws Exception {
        if (serviceRegistry != null) {
            serviceRegistry.stop();
        }
        if (stopCallbackList.size() > 0) {
            for (BaseCallback callback: stopCallbackList) {
                try {
                    callback.run();
                } catch (Exception e) {
                    logger.error(e.getMessage(), e);
                }
            }
        }
        stopCallbackThreadPool();
    }


    private BaseServiceRegistry serviceRegistry;

    public BaseServiceRegistry getServiceRegistry() {
        return serviceRegistry;
    }


    private List<BaseCallback> stopCallbackList = new ArrayList<BaseCallback>();

    public void addStopCallBack(BaseCallback callback){
        stopCallbackList.add(callback);
    }



    private ConcurrentMap<String, RpcFutureResponse> futureResponsePool = new ConcurrentHashMap<String, RpcFutureResponse>();
    public void setInvokerFuture(String requestId, RpcFutureResponse futureResponse){
        futureResponsePool.put(requestId, futureResponse);
    }
    public void removeInvokerFuture(String requestId){
        futureResponsePool.remove(requestId);
    }
    public void notifyInvokerFuture(String requestId, final RpcResponse response){

        // get
        final RpcFutureResponse futureResponse = futureResponsePool.get(requestId);
        if (futureResponse == null) {
            return;
        }

        // notify
        if (futureResponse.getInvokeCallback()!=null) {

            // callback type
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
            }catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
        } else {

            // other nomal type
            futureResponse.setResponse(response);
        }

        // do remove
        futureResponsePool.remove(requestId);

    }


    private ThreadPoolExecutor responseCallbackThreadPool = null;
    public void executeResponseCallback(Runnable runnable){

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

    public void stopCallbackThreadPool() {
        if (responseCallbackThreadPool != null) {
            responseCallbackThreadPool.shutdown();
        }
    }
}
