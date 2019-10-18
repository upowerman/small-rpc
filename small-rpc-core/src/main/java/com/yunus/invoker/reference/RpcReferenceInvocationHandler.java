package com.yunus.invoker.reference;

import com.yunus.exception.RpcException;
import com.yunus.invoker.RpcInvokerFactory;
import com.yunus.invoker.call.CallType;
import com.yunus.invoker.call.RpcInvokeFuture;
import com.yunus.invoker.route.LoadBalance;
import com.yunus.net.base.*;
import com.yunus.provider.RpcProviderFactory;
import com.yunus.registry.BaseServiceRegistry;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * @author gaoyunfeng
 */
public class RpcReferenceInvocationHandler implements InvocationHandler {

    private RpcReferenceBean referenceBean;

    public RpcReferenceInvocationHandler(RpcReferenceBean rpcReferenceBean) {
        this.referenceBean = rpcReferenceBean;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String className = method.getDeclaringClass().getName();
        String version = referenceBean.getVersion();
        String methodName = method.getName();
        Class<?>[] parameterTypes = method.getParameterTypes();
        Object[] parameters = args;
        if (className.equals(Object.class.getName())) {
            throw new RpcException("rpc proxy class-method not support");
        }
        String finalAddress = referenceBean.getAddress();
        RpcInvokerFactory invokerFactory = referenceBean.getInvokerFactory();
        CallType callType = referenceBean.getCallType();
        RpcInvokeCallback invokeCallback = referenceBean.getInvokeCallback();
        long timeout = referenceBean.getTimeout();
        BaseClient client = referenceBean.getClient();
        if (finalAddress == null || finalAddress.trim().length() == 0) {
            finalAddress = getBalanceAddress(invokerFactory.getServiceRegistry(), className, version);
        }
        RpcRequest request = new RpcRequest();
        request.setRequestId(UUID.randomUUID().toString());
        request.setCreateMillisTime(System.currentTimeMillis());
        request.setClassName(className);
        request.setMethodName(methodName);
        request.setParameterTypes(parameterTypes);
        request.setParameters(parameters);

        if (CallType.SYNC == callType) {
            RpcFutureResponse futureResponse = new RpcFutureResponse(invokerFactory, request, null);
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
        } else if (CallType.FUTURE == callType) {
            RpcFutureResponse futureResponse = new RpcFutureResponse(invokerFactory, request, null);
            try {
                RpcInvokeFuture invokeFuture = new RpcInvokeFuture(futureResponse);
                RpcInvokeFuture.setFuture(invokeFuture);
                client.asyncSend(finalAddress, request);
                return null;
            } catch (Exception e) {
                futureResponse.removeInvokerFuture();
                throw (e instanceof RpcException) ? e : new RpcException(e);
            }

        } else if (CallType.CALLBACK == callType) {
            RpcInvokeCallback finalInvokeCallback = invokeCallback;
            RpcInvokeCallback threadInvokeCallback = RpcInvokeCallback.getCallback();
            if (threadInvokeCallback != null) {
                finalInvokeCallback = threadInvokeCallback;
            }
            if (finalInvokeCallback == null) {
                throw new RpcException("rpc RpcInvokeCallback（CallType=" + CallType.CALLBACK.name() + "） cannot be null.");
            }
            RpcFutureResponse futureResponse = new RpcFutureResponse(invokerFactory, request, finalInvokeCallback);
            try {
                client.asyncSend(finalAddress, request);
            } catch (Exception e) {
                futureResponse.removeInvokerFuture();
                throw (e instanceof RpcException) ? e : new RpcException(e);
            }
            return null;
        } else {
            throw new RpcException("rpc callType[" + callType + "] invalid");
        }
    }

    /**
     * 获取负载均衡后的地址
     *
     * @param serviceRegistry 服务注册类
     * @param className       调用接口名称
     * @param version         版本信息
     * @return
     */
    private String getBalanceAddress(BaseServiceRegistry serviceRegistry, String className, String version) {
        String address = null;
        if (serviceRegistry != null) {
            String serviceKey = RpcProviderFactory.makeServiceKey(className, version);
            TreeSet<String> addressSet = serviceRegistry.discovery(serviceKey);
            if (addressSet == null || addressSet.size() == 0) {
                // todo 后续引入注册中心时 注册中心拉取地址
            } else if (addressSet.size() == 1) {
                address = addressSet.first();
            } else {
                LoadBalance loadBalance = referenceBean.getLoadBalance();
                address = loadBalance.rpcInvokerRouter.route(serviceKey, addressSet);
            }
        }
        if (address == null || address.trim().length() == 0) {
            throw new RpcException("rpc reference bean[" + className + "] address empty");
        } else {
            return address;
        }
    }

}
