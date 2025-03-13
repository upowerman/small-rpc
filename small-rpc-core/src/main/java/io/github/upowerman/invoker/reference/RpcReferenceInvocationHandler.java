package io.github.upowerman.invoker.reference;

import io.github.upowerman.exception.RpcException;
import io.github.upowerman.invoker.RpcInvokerFactory;
import io.github.upowerman.invoker.route.LoadBalance;
import io.github.upowerman.net.base.BaseClient;
import io.github.upowerman.net.base.RpcFutureResponse;
import io.github.upowerman.net.base.RpcRequest;
import io.github.upowerman.net.base.RpcResponse;
import io.github.upowerman.provider.RpcProviderFactory;
import io.github.upowerman.registry.BaseServiceRegistry;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * @author gaoyunfeng
 */
public class RpcReferenceInvocationHandler implements InvocationHandler {

    private final RpcReferenceBean referenceBean;

    public RpcReferenceInvocationHandler(RpcReferenceBean rpcReferenceBean) {
        this.referenceBean = rpcReferenceBean;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String className = method.getDeclaringClass().getName();
        String version = referenceBean.getVersion();
        String methodName = method.getName();
        Class<?>[] parameterTypes = method.getParameterTypes();
        if (className.equals(Object.class.getName())) {
            throw new RpcException("服务类不支持调用方法");
        }
        String finalAddress = referenceBean.getAddress();
        RpcInvokerFactory invokerFactory = referenceBean.getInvokerFactory();
        long timeout = referenceBean.getTimeout();
        BaseClient client = referenceBean.getClient();
        if (finalAddress == null || finalAddress.trim().isEmpty()) {
            // 从注册中心获取调用地址
            finalAddress = getBalanceAddress(invokerFactory.getServiceRegistry(), className, version);
        }
        RpcRequest request = new RpcRequest();
        request.setRequestId(UUID.randomUUID().toString());
        request.setCreateMillisTime(System.currentTimeMillis());
        request.setClassName(className);
        request.setMethodName(methodName);
        request.setParameterTypes(parameterTypes);
        request.setParameters(args);

        RpcFutureResponse futureResponse = new RpcFutureResponse(invokerFactory, request);
        try {
            client.asyncSend(finalAddress, request);
            RpcResponse rpcResponse = futureResponse.get(timeout, TimeUnit.MILLISECONDS);
            if (rpcResponse.getErrorMsg() != null) {
                throw new RpcException(rpcResponse.getErrorMsg());
            }
            return rpcResponse.getResult();
        } catch (Exception e) {
            throw (e instanceof RpcException) ? e : new RpcException(e);
        } finally {
            futureResponse.removeInvokerFuture();
        }
    }

    /**
     * 获取负载均衡后的地址
     *
     * @param serviceRegistry 服务注册类
     * @param className       调用接口名称
     * @param version         版本信息
     * @return address
     */
    private String getBalanceAddress(BaseServiceRegistry serviceRegistry, String className, String version) {
        String address = null;
        if (serviceRegistry != null) {
            String serviceKey = RpcProviderFactory.makeServiceKey(className, version);
            TreeSet<String> addressSet = serviceRegistry.discovery(serviceKey);
            if (addressSet == null || addressSet.isEmpty()) {
                // todo 后续引入注册中心时 注册中心拉取地址
            } else if (addressSet.size() == 1) {
                address = addressSet.first();
            } else {
                LoadBalance loadBalance = referenceBean.getLoadBalance();
                address = loadBalance.rpcInvokerRouter.route(serviceKey, addressSet);
            }
        }
        if (address == null || address.trim().isEmpty()) {
            throw new RpcException("rpc reference bean[" + className + "] address empty");
        } else {
            return address;
        }
    }

}
