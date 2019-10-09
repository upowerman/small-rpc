package com.yunus.provider;

import com.yunus.exception.RpcException;
import com.yunus.net.base.*;
import com.yunus.registry.BaseServiceRegistry;
import com.yunus.serialize.BaseSerializer;
import com.yunus.util.IpUtil;
import com.yunus.util.NetUtil;
import com.yunus.util.ThrowableUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * @author gaoyunfeng
 */
public class RpcProviderFactory {
    private static final Logger logger = LoggerFactory.getLogger(RpcProviderFactory.class);

    private NetEnum netType;
    private BaseSerializer serializer;

    private int corePoolSize;
    private int maxPoolSize;

    private String ip;
    private int port;
    private String accessToken;

    private Class<? extends BaseServiceRegistry> serviceRegistryClass;
    private Map<String, String> serviceRegistryParam;


    public RpcProviderFactory() {
    }

    public void initConfig(NetEnum netType,
                           BaseSerializer serializer,
                           int corePoolSize,
                           int maxPoolSize,
                           String ip,
                           int port,
                           String accessToken,
                           Class<? extends BaseServiceRegistry> serviceRegistryClass,
                           Map<String, String> serviceRegistryParam) {

        // init
        this.netType = netType;
        this.serializer = serializer;
        this.corePoolSize = corePoolSize;
        this.maxPoolSize = maxPoolSize;
        this.ip = ip;
        this.port = port;
        this.accessToken = accessToken;
        this.serviceRegistryClass = serviceRegistryClass;
        this.serviceRegistryParam = serviceRegistryParam;

        // valid
        if (this.netType == null) {
            throw new RpcException("xxl-rpc provider netType missing.");
        }
        if (this.serializer == null) {
            throw new RpcException("xxl-rpc provider serializer missing.");
        }
        if (!(this.corePoolSize >= 0 && this.maxPoolSize > 0 && this.maxPoolSize >= this.corePoolSize)) {
            this.corePoolSize = 60;
            this.maxPoolSize = 300;
        }
        if (this.ip == null) {
            this.ip = IpUtil.getIp();
        }
        if (this.port <= 0) {
            this.port = 7080;
        }
        if (NetUtil.isPortUsed(this.port)) {
            throw new RpcException("xxl-rpc provider port[" + this.port + "] is used.");
        }
        if (this.serviceRegistryClass != null) {
            if (this.serviceRegistryParam == null) {
                throw new RpcException("xxl-rpc provider serviceRegistryParam is missing.");
            }
        }

    }

    public BaseSerializer getSerializer() {
        return serializer;
    }

    public int getPort() {
        return port;
    }

    public int getCorePoolSize() {
        return corePoolSize;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    // ---------------------- start / stop ----------------------

    private BaseServer server;
    private BaseServiceRegistry serviceRegistry;
    private String serviceAddress;

    public void start() throws Exception {
        // start server
        serviceAddress = IpUtil.getIpPort(this.ip, port);
        server = netType.serverClass.newInstance();
        server.setStartedCallback(new BaseCallback() {
            @Override
            public void run() throws Exception {
                // 开始注册
                if (serviceRegistryClass != null) {
                    serviceRegistry = serviceRegistryClass.newInstance();
                    serviceRegistry.start(serviceRegistryParam);
                    if (serviceData.size() > 0) {
                        serviceRegistry.registry(serviceData.keySet(), serviceAddress);
                    }
                }
            }
        });
        server.setStopedCallback(new BaseCallback() {        // serviceRegistry stoped
            @Override
            public void run() {
                // stop registry
                if (serviceRegistry != null) {
                    if (serviceData.size() > 0) {
                        serviceRegistry.remove(serviceData.keySet(), serviceAddress);
                    }
                    serviceRegistry.stop();
                    serviceRegistry = null;
                }
            }
        });
        server.start(this);
    }

    public void stop() throws Exception {
        // stop server
        server.stop();
    }


    // ---------------------- server invoke ----------------------

    /**
     * init local rpc service map
     */
    private Map<String, Object> serviceData = new HashMap<String, Object>();

    public Map<String, Object> getServiceData() {
        return serviceData;
    }

    /**
     * make service key
     *
     * @param iface
     * @param version
     * @return
     */
    public static String makeServiceKey(String iface, String version) {
        String serviceKey = iface;
        if (version != null && version.trim().length() > 0) {
            serviceKey += "#".concat(version);
        }
        return serviceKey;
    }

    /**
     * add service
     *
     * @param iface
     * @param version
     * @param serviceBean
     */
    public void addService(String iface, String version, Object serviceBean) {
        String serviceKey = makeServiceKey(iface, version);
        serviceData.put(serviceKey, serviceBean);

        logger.info(">>>>>>>>>>> xxl-rpc, provider factory add service success. serviceKey = {}, serviceBean = {}", serviceKey, serviceBean.getClass());
    }

    /**
     * invoke service
     *
     * @param rpcRequest
     * @return
     */
    public RpcResponse invokeService(RpcRequest rpcRequest) {

        //  make response
        RpcResponse rpcResponse = new RpcResponse();
        rpcResponse.setRequestId(rpcRequest.getRequestId());

        // match service bean
        String serviceKey = makeServiceKey(rpcRequest.getClassName(), rpcRequest.getVersion());
        Object serviceBean = serviceData.get(serviceKey);

        // valid
        if (serviceBean == null) {
            rpcResponse.setErrorMsg("The serviceKey[" + serviceKey + "] not found.");
            return rpcResponse;
        }

        if (System.currentTimeMillis() - rpcRequest.getCreateMillisTime() > 3 * 60 * 1000) {
            rpcResponse.setErrorMsg("The timestamp difference between admin and executor exceeds the limit.");
            return rpcResponse;
        }
        if (accessToken != null && accessToken.trim().length() > 0 && !accessToken.trim().equals(rpcRequest.getAccessToken())) {
            rpcResponse.setErrorMsg("The access token[" + rpcRequest.getAccessToken() + "] is wrong.");
            return rpcResponse;
        }

        try {
            // invoke
            Class<?> serviceClass = serviceBean.getClass();
            String methodName = rpcRequest.getMethodName();
            Class<?>[] parameterTypes = rpcRequest.getParameterTypes();
            Object[] parameters = rpcRequest.getParameters();

            Method method = serviceClass.getMethod(methodName, parameterTypes);
            method.setAccessible(true);
            Object result = method.invoke(serviceBean, parameters);

            rpcResponse.setResult(result);
        } catch (Throwable t) {
            // catch error
            logger.error("xxl-rpc provider invokeService error.", t);
            rpcResponse.setErrorMsg(ThrowableUtil.toString(t));
        }

        return rpcResponse;
    }
}