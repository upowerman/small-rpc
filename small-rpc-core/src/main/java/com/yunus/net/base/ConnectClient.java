package com.yunus.net.base;

import com.yunus.invoker.RpcInvokerFactory;
import com.yunus.invoker.reference.RpcReferenceBean;
import com.yunus.serialize.BaseSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author gaoyunfeng
 */
public abstract class ConnectClient {
    protected static transient Logger logger = LoggerFactory.getLogger(ConnectClient.class);

    private static volatile ConcurrentMap<String, ConnectClient> connectClientMap;
    private static volatile ConcurrentMap<String, Object> connectClientLockMap = new ConcurrentHashMap<>();

    /**
     * 初始化方法
     *
     * @param address           地址
     * @param serializer        序列化方式
     * @param rpcInvokerFactory 工厂类
     * @throws Exception
     */
    public abstract void init(String address, final BaseSerializer serializer, final RpcInvokerFactory rpcInvokerFactory) throws Exception;

    /**
     * 关闭方法
     */
    public abstract void close();

    /**
     * 验证方法
     *
     * @return
     */
    public abstract boolean isValidate();

    /**
     * 调用方法
     *
     * @param request 请求wrap
     * @throws Exception
     */
    public abstract void send(RpcRequest request) throws Exception;

    /**
     * 异步发送
     *
     * @param rpcRequest
     * @param address
     * @param connectClientImpl
     * @param rpcReferenceBean
     * @throws Exception
     */
    public static void asyncSend(RpcRequest rpcRequest, String address,
                                 Class<? extends ConnectClient> connectClientImpl,
                                 final RpcReferenceBean rpcReferenceBean) throws Exception {

        ConnectClient clientPool = ConnectClient.getPool(address, connectClientImpl, rpcReferenceBean);

        try {
            clientPool.send(rpcRequest);
        } catch (Exception e) {
            throw e;
        }

    }

    /**
     * 以后负载均衡时 根据不同的address 获取不同的 connectClient
     *
     * @param address           请求地址
     * @param connectClientImpl 实现类
     * @param rpcReferenceBean  ReferenceBean
     * @return
     * @throws Exception
     */
    private static ConnectClient getPool(String address, Class<? extends ConnectClient> connectClientImpl,
                                         final RpcReferenceBean rpcReferenceBean) throws Exception {
        // init
        if (connectClientMap == null) {
            synchronized (ConnectClient.class) {
                if (connectClientMap == null) {
                    connectClientMap = new ConcurrentHashMap<String, ConnectClient>();
                    rpcReferenceBean.getInvokerFactory().addStopCallBack(new BaseCallback() {
                        @Override
                        public void run() throws Exception {
                            if (connectClientMap.size() > 0) {
                                for (String key : connectClientMap.keySet()) {
                                    ConnectClient clientPool = connectClientMap.get(key);
                                    clientPool.close();
                                }
                                connectClientMap.clear();
                            }
                        }
                    });
                }
            }
        }

        ConnectClient connectClient = connectClientMap.get(address);
        if (connectClient != null && connectClient.isValidate()) {
            return connectClient;
        }

        // lock
        Object clientLock = connectClientLockMap.get(address);
        if (clientLock == null) {
            connectClientLockMap.putIfAbsent(address, new Object());
            clientLock = connectClientLockMap.get(address);
        }

        // remove-create new client
        synchronized (clientLock) {

            connectClient = connectClientMap.get(address);
            if (connectClient != null && connectClient.isValidate()) {
                return connectClient;
            }

            // remove old
            if (connectClient != null) {
                connectClient.close();
                connectClientMap.remove(address);
            }

            // set pool
            ConnectClient connectClient_new = connectClientImpl.newInstance();
            try {
                connectClient_new.init(address, rpcReferenceBean.getSerializer(), rpcReferenceBean.getInvokerFactory());
                connectClientMap.put(address, connectClient_new);
            } catch (Exception e) {
                connectClient_new.close();
                throw e;
            }

            return connectClient_new;
        }

    }
}
