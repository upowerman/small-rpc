package io.github.upowerman.invoker;

import io.github.upowerman.net.base.BaseCallback;
import io.github.upowerman.net.base.RpcFutureResponse;
import io.github.upowerman.net.base.RpcResponse;
import io.github.upowerman.registry.BaseServiceRegistry;
import io.github.upowerman.registry.impl.LocalServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author gaoyunfeng
 */
public class RpcInvokerFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(RpcInvokerFactory.class);

    private static final RpcInvokerFactory INSTANCE = new RpcInvokerFactory(LocalServiceRegistry.class, null);

    private Class<? extends BaseServiceRegistry> serviceRegistryClass;
    private Map<String, String> serviceRegistryParam;

    private BaseServiceRegistry serviceRegistry;

    /**
     * 服务停止时的回调函数
     */
    private final List<BaseCallback> stopCallbackList = new ArrayList<BaseCallback>();

    /**
     * 请求cache
     * key-requestId
     * value-响应体
     */
    private final ConcurrentMap<String, RpcFutureResponse> futureResponsePool = new ConcurrentHashMap<String, RpcFutureResponse>();

    public RpcInvokerFactory() {
    }

    public RpcInvokerFactory(Class<? extends BaseServiceRegistry> serviceRegistryClass, Map<String, String> serviceRegistryParam) {
        this.serviceRegistryClass = serviceRegistryClass;
        this.serviceRegistryParam = serviceRegistryParam;
    }


    public void start() throws Exception {
        if (serviceRegistryClass != null) {
            serviceRegistry = serviceRegistryClass.getDeclaredConstructor().newInstance();
            serviceRegistry.start(serviceRegistryParam);
        }
    }

    public void stop() {
        if (serviceRegistry != null) {
            serviceRegistry.stop();
        }
        if (!stopCallbackList.isEmpty()) {
            for (BaseCallback callback : stopCallbackList) {
                try {
                    callback.run();
                } catch (Exception e) {
                    LOGGER.error(e.getMessage(), e);
                }
            }
        }
    }

    public void notifyInvokerFuture(String requestId, final RpcResponse response) {
        final RpcFutureResponse futureResponse = futureResponsePool.get(requestId);
        if (futureResponse == null) {
            return;
        }
        futureResponse.setResponse(response);
        // 移除 response
        futureResponsePool.remove(requestId);
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

    public static RpcInvokerFactory getInstance() {
        return INSTANCE;
    }
}
