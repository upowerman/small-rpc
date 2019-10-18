package com.yunus.invoker.reference;

import com.yunus.exception.RpcException;
import com.yunus.invoker.RpcInvokerFactory;
import com.yunus.invoker.call.CallType;
import com.yunus.invoker.call.RpcInvokeFuture;
import com.yunus.invoker.generic.RpcGenericService;
import com.yunus.invoker.route.LoadBalance;
import com.yunus.net.base.*;
import com.yunus.provider.RpcProviderFactory;
import com.yunus.serialize.BaseSerializer;
import com.yunus.util.ClassUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * @author gaoyunfeng
 */
public class RpcReferenceBean {

    private static final Logger logger = LoggerFactory.getLogger(RpcReferenceBean.class);

    private NetEnum netType;
    private BaseSerializer serializer;
    private CallType callType;
    private LoadBalance loadBalance;

    private Class<?> iface;
    private String version;

    private long timeout = 1000;

    private String address;

    private RpcInvokeCallback invokeCallback;

    private RpcInvokerFactory invokerFactory;

    public RpcReferenceBean(NetEnum netType,
                            BaseSerializer serializer,
                            CallType callType,
                            LoadBalance loadBalance,
                            Class<?> iface,
                            String version,
                            long timeout,
                            String address,
                            RpcInvokeCallback invokeCallback,
                            RpcInvokerFactory invokerFactory) {

        this.netType = netType;
        this.serializer = serializer;
        this.callType = callType;
        this.loadBalance = loadBalance;
        this.iface = iface;
        this.version = version;
        this.timeout = timeout;
        this.address = address;
        this.invokeCallback = invokeCallback;
        this.invokerFactory = invokerFactory;

        // valid
        if (this.netType == null) {
            throw new RpcException("rpc reference netType missing.");
        }
        if (this.serializer == null) {
            throw new RpcException("rpc reference serializer missing.");
        }
        if (this.callType == null) {
            throw new RpcException("rpc reference callType missing.");
        }
        if (this.loadBalance == null) {
            throw new RpcException("rpc reference loadBalance missing.");
        }
        if (this.iface == null) {
            throw new RpcException("rpc reference iface missing.");
        }
        if (this.timeout < 0) {
            this.timeout = 0;
        }
        if (this.invokerFactory == null) {
            this.invokerFactory = RpcInvokerFactory.getInstance();
        }

        // init Client
        initClient();
    }

    public BaseSerializer getSerializer() {
        return serializer;
    }

    public long getTimeout() {
        return timeout;
    }

    public RpcInvokerFactory getInvokerFactory() {
        return invokerFactory;
    }


    BaseClient client = null;

    private void initClient() {
        try {
            client = netType.clientClass.newInstance();
            client.init(this);
        } catch (InstantiationException | IllegalAccessException e) {
            throw new RpcException(e);
        }
    }


    /**
     * 获取代理对象
     *
     * @return
     */
    public Object getObject() {
        return Proxy.newProxyInstance(Thread.currentThread()
                        .getContextClassLoader(), new Class[]{iface},
                new InvocationHandler() {
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {

                        // method param
                        String className = method.getDeclaringClass().getName();
                        String version = RpcReferenceBean.this.version;
                        String methodName = method.getName();
                        Class<?>[] parameterTypes = method.getParameterTypes();
                        Object[] parameters = args;

                        if (className.equals(RpcGenericService.class.getName()) && methodName.equals("invoke")) {

                            Class<?>[] paramTypes = null;
                            if (args[3] != null) {
                                String[] paramTypes_str = (String[]) args[3];
                                if (paramTypes_str.length > 0) {
                                    paramTypes = new Class[paramTypes_str.length];
                                    for (int i = 0; i < paramTypes_str.length; i++) {
                                        paramTypes[i] = ClassUtil.resolveClass(paramTypes_str[i]);
                                    }
                                }
                            }

                            className = (String) args[0];
                            version = (String) args[1];
                            methodName = (String) args[2];
                            parameterTypes = paramTypes;
                            parameters = (Object[]) args[4];
                        }

                        if (className.equals(Object.class.getName())) {
                            throw new RpcException("rpc proxy class-method not support");
                        }

                        // address
                        String finalAddress = address;
                        if (finalAddress == null || finalAddress.trim().length() == 0) {
                            if (invokerFactory != null && invokerFactory.getServiceRegistry() != null) {
                                // discovery
                                String serviceKey = RpcProviderFactory.makeServiceKey(className, version);
                                TreeSet<String> addressSet = invokerFactory.getServiceRegistry().discovery(serviceKey);
                                // load balance
                                if (addressSet == null || addressSet.size() == 0) {
                                    // pass
                                } else if (addressSet.size() == 1) {
                                    finalAddress = addressSet.first();
                                } else {
                                    finalAddress = loadBalance.rpcInvokerRouter.route(serviceKey, addressSet);
                                }

                            }
                        }
                        if (finalAddress == null || finalAddress.trim().length() == 0) {
                            throw new RpcException("rpc reference bean[" + className + "] address empty");
                        }

                        // request
                        RpcRequest RpcRequest = new RpcRequest();
                        RpcRequest.setRequestId(UUID.randomUUID().toString());
                        RpcRequest.setCreateMillisTime(System.currentTimeMillis());
                        RpcRequest.setClassName(className);
                        RpcRequest.setMethodName(methodName);
                        RpcRequest.setParameterTypes(parameterTypes);
                        RpcRequest.setParameters(parameters);

                        // send
                        if (CallType.SYNC == callType) {
                            RpcFutureResponse futureResponse = new RpcFutureResponse(invokerFactory, RpcRequest, null);
                            try {
                                // do invoke
                                client.asyncSend(finalAddress, RpcRequest);

                                RpcResponse rpcResponse = futureResponse.get(timeout, TimeUnit.MILLISECONDS);
                                if (rpcResponse.getErrorMsg() != null) {
                                    throw new RpcException(rpcResponse.getErrorMsg());
                                }
                                return rpcResponse.getResult();
                            } catch (Exception e) {
                                logger.info(">>>>>>>>>>> rpc, invoke error, address:{}, RpcRequest{}", finalAddress, RpcRequest);

                                throw (e instanceof RpcException) ? e : new RpcException(e);
                            } finally {
                                // future-response remove
                                futureResponse.removeInvokerFuture();
                            }
                        } else if (CallType.FUTURE == callType) {
                            RpcFutureResponse futureResponse = new RpcFutureResponse(invokerFactory, RpcRequest, null);
                            try {
                                // invoke future set
                                RpcInvokeFuture invokeFuture = new RpcInvokeFuture(futureResponse);
                                RpcInvokeFuture.setFuture(invokeFuture);

                                // do invoke
                                client.asyncSend(finalAddress, RpcRequest);

                                return null;
                            } catch (Exception e) {
                                logger.info(">>>>>>>>>>> rpc, invoke error, address:{}, RpcRequest{}", finalAddress, RpcRequest);

                                // future-response remove
                                futureResponse.removeInvokerFuture();

                                throw (e instanceof RpcException) ? e : new RpcException(e);
                            }

                        } else if (CallType.CALLBACK == callType) {

                            // get callback
                            RpcInvokeCallback finalInvokeCallback = invokeCallback;
                            RpcInvokeCallback threadInvokeCallback = RpcInvokeCallback.getCallback();
                            if (threadInvokeCallback != null) {
                                finalInvokeCallback = threadInvokeCallback;
                            }
                            if (finalInvokeCallback == null) {
                                throw new RpcException("rpc RpcInvokeCallback（CallType=" + CallType.CALLBACK.name() + "） cannot be null.");
                            }

                            // future-response set
                            RpcFutureResponse futureResponse = new RpcFutureResponse(invokerFactory, RpcRequest, finalInvokeCallback);
                            try {
                                client.asyncSend(finalAddress, RpcRequest);
                            } catch (Exception e) {
                                logger.info(">>>>>>>>>>> rpc, invoke error, address:{}, RpcRequest{}", finalAddress, RpcRequest);

                                futureResponse.removeInvokerFuture();

                                throw (e instanceof RpcException) ? e : new RpcException(e);
                            }

                            return null;
                        } else {
                            throw new RpcException("rpc callType[" + callType + "] invalid");
                        }

                    }
                });
    }


    public Class<?> getObjectType() {
        return iface;
    }
}
