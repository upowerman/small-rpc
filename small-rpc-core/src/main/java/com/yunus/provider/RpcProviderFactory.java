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

    /**
     * 网络类型
     */
    private NetEnum netType;
    /**
     * 序列化方式
     */
    private BaseSerializer serializer;

    private int corePoolSize;
    private int maxPoolSize;

    private String ip;
    private int port;

    /**
     * 对应的注册方式 本地  zk  等其他注册中心
     */
    private Class<? extends BaseServiceRegistry> serviceRegistryClass;
    private Map<String, String> serviceRegistryParam;

    /**
     * init local rpc service map
     */
    private Map<String, Object> serviceData = new HashMap<String, Object>();

    /**
     * 目前只提供netty 方式
     */
    private BaseServer server;
    private BaseServiceRegistry serviceRegistry;
    private String serviceAddress;


    public RpcProviderFactory() {
    }

    public void initConfig(NetEnum netType,
                           BaseSerializer serializer,
                           int corePoolSize,
                           int maxPoolSize,
                           String ip,
                           int port,
                           Class<? extends BaseServiceRegistry> serviceRegistryClass,
                           Map<String, String> serviceRegistryParam) {

        // init
        this.netType = netType;
        this.serializer = serializer;
        this.corePoolSize = corePoolSize;
        this.maxPoolSize = maxPoolSize;
        this.ip = ip;
        this.port = port;
        this.serviceRegistryClass = serviceRegistryClass;
        this.serviceRegistryParam = serviceRegistryParam;

        if (this.netType == null) {
            throw new RpcException("rpc provider netType missing.");
        }
        if (this.serializer == null) {
            throw new RpcException("rpc provider serializer missing.");
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
            throw new RpcException("rpc provider port[" + this.port + "] is used.");
        }
        if (this.serviceRegistryClass != null) {
            if (this.serviceRegistryParam == null) {
                throw new RpcException("rpc provider serviceRegistryParam is missing.");
            }
        }

    }


    public void start() throws Exception {
        serviceAddress = IpUtil.getIpPort(this.ip, port);
        server = netType.serverClass.newInstance();
        // 设置开始 回调函数
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
        server.setStopedCallback(new BaseCallback() {
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
        server.stop();
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
    }

    /**
     * invoke service
     *
     * @param rpcRequest
     * @return
     */
    public RpcResponse invokeService(RpcRequest rpcRequest) {

        RpcResponse rpcResponse = new RpcResponse();
        rpcResponse.setRequestId(rpcRequest.getRequestId());

        String serviceKey = makeServiceKey(rpcRequest.getClassName(), rpcRequest.getVersion());
        Object serviceBean = serviceData.get(serviceKey);

        if (serviceBean == null) {
            rpcResponse.setErrorMsg("The serviceKey[" + serviceKey + "] not found.");
            return rpcResponse;
        }

        if (System.currentTimeMillis() - rpcRequest.getCreateMillisTime() > 3 * 60 * 1000) {
            rpcResponse.setErrorMsg("The timestamp difference between admin and executor exceeds the limit.");
            return rpcResponse;
        }
        try {
            Class<?> serviceClass = serviceBean.getClass();
            String methodName = rpcRequest.getMethodName();
            Class<?>[] parameterTypes = rpcRequest.getParameterTypes();
            Object[] parameters = rpcRequest.getParameters();

            Method method = serviceClass.getMethod(methodName, parameterTypes);
            method.setAccessible(true);
            Object result = method.invoke(serviceBean, parameters);

            rpcResponse.setResult(result);
        } catch (Throwable t) {
            logger.error("rpc provider invokeService error.", t);
            rpcResponse.setErrorMsg(ThrowableUtil.toString(t));
        }

        return rpcResponse;
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

    public Map<String, Object> getServiceData() {
        return serviceData;
    }
}
