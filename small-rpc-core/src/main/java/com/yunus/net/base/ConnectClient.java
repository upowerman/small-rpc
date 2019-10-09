package com.yunus.net.base;

import com.yunus.invoker.RpcInvokerFactory;
import com.yunus.invoker.reference.RpcReferenceBean;
import com.yunus.serialize.BaseSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public abstract class ConnectClient {
    protected static transient Logger logger = LoggerFactory.getLogger(ConnectClient.class);


    public abstract void init(String address, final BaseSerializer serializer, final RpcInvokerFactory RpcInvokerFactory) throws Exception;

    public abstract void close();

    public abstract boolean isValidate();

    public abstract void send(RpcRequest request) throws Exception;


    public static void asyncSend(RpcRequest rpcRequest, String address,
                                 Class<? extends ConnectClient> connectClientImpl,
                                 final RpcReferenceBean RpcReferenceBean) throws Exception {

        // client pool	[tips03 : may save 35ms/100invoke if move it to constructor, but it is necessary. cause by ConcurrentHashMap.get]
        ConnectClient clientPool = ConnectClient.getPool(address, connectClientImpl, RpcReferenceBean);

        try {
            // do invoke
            clientPool.send(rpcRequest);
        } catch (Exception e) {
            throw e;
        }

    }

    private static volatile ConcurrentMap<String, ConnectClient> connectClientMap;
    private static volatile ConcurrentMap<String, Object> connectClientLockMap = new ConcurrentHashMap<>();

    private static ConnectClient getPool(String address, Class<? extends ConnectClient> connectClientImpl,
                                         final RpcReferenceBean rpcReferenceBean) throws Exception {

        if (connectClientMap == null) {
            synchronized (ConnectClient.class) {
                if (connectClientMap == null) {
                    // init
                    connectClientMap = new ConcurrentHashMap<String, ConnectClient>();
                    // stop callback
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
                connectClient_new.init(address, RpcReferenceBean.getSerializer(), RpcReferenceBean.getInvokerFactory());
                connectClientMap.put(address, connectClient_new);
            } catch (Exception e) {
                connectClient_new.close();
                throw e;
            }

            return connectClient_new;
        }

    }
}
