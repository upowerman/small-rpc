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

    private BaseServiceRegistry serviceRegistry;
    private String serviceAddress;
    /**
     * 服务类数据
     */
    private Map<String, Object> serviceData = new HashMap<String, Object>();

    private BaseServer server;


    public RpcProviderFactory() {
    }

    public void initConfig() {
        checkConfig();
    }

    public void initConfig(NetEnum netType, BaseSerializer serializer) {
        this.netType = netType;
        this.serializer = serializer;
        checkConfig();
    }

    /**
     * 验证配置
     */
    private void checkConfig() {
        if (this.netType == null) {
            throw new RpcException("请配置网络类型--->netType");
        }
        if (this.serializer == null) {
            throw new RpcException("请配置序列化方式--->serializer");
        }
        // 设置默认 corePoolSize maxPoolSize
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
            throw new RpcException("端口号： port[" + this.port + "] 被占用");
        }
        if (this.serviceRegistryClass != null) {
            if (this.serviceRegistryParam == null) {
                throw new RpcException("请配置服务注册参数--->serviceRegistryParam");
            }
        }
    }


    public void start() throws Exception {
        serviceAddress = IpUtil.getIpPort(this.ip, port);
        server = netType.serverClass.newInstance();
        // 设置开始 回调函数
        server.setStartCallback(new BaseCallback() {
            @Override
            public void run() throws Exception {
                // 开始注册
                if (serviceRegistryClass != null) {
                    serviceRegistry = serviceRegistryClass.newInstance();
                    serviceRegistry.start(serviceRegistryParam);
                    if (serviceData.size() > 0) {
                        // 把服务类注册到注册中心
                        serviceRegistry.registry(serviceData.keySet(), serviceAddress);
                    }
                }
            }
        });
        server.setStopCallback(new BaseCallback() {
            @Override
            public void run() {
                // 停止服务时，移除注册中心中的服务
                if (serviceRegistry != null) {
                    if (serviceData.size() > 0) {
                        serviceRegistry.remove(serviceData.keySet(), serviceAddress);
                    }
                    serviceRegistry.stop();
                    serviceRegistry = null;
                }
            }
        });
        // 启动服务
        server.start(this);
    }

    public void stop() throws Exception {
        server.stop();
    }


    /**
     * 生成serviceData 服务类key
     *
     * @param iface   接口名
     * @param version 版本号
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
     * 注册服务类
     *
     * @param iface       接口名称
     * @param version     版本号
     * @param serviceBean 服务类bean
     */
    public void addService(String iface, String version, Object serviceBean) {
        String serviceKey = makeServiceKey(iface, version);
        serviceData.put(serviceKey, serviceBean);
    }

    /**
     * 调用服务
     *
     * @param rpcRequest 请求对象
     * @return
     */
    public RpcResponse invokeService(RpcRequest rpcRequest) {

        RpcResponse rpcResponse = new RpcResponse();
        rpcResponse.setRequestId(rpcRequest.getRequestId());

        String serviceKey = makeServiceKey(rpcRequest.getClassName(), rpcRequest.getVersion());
        Object serviceBean = serviceData.get(serviceKey);

        if (serviceBean == null) {
            rpcResponse.setErrorMsg("服务类[" + serviceKey + "] 没有发现");
            return rpcResponse;
        }

        // 判断时候超过间隔时间
        if (System.currentTimeMillis() - rpcRequest.getCreateMillisTime() > 3 * 60 * 1000) {
            rpcResponse.setErrorMsg("发送请求到执行超过限制时间");
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
            logger.error("服务调用出错", t);
            rpcResponse.setErrorMsg(ThrowableUtil.toString(t));
        }

        return rpcResponse;
    }

    public NetEnum getNetType() {
        return netType;
    }

    public void setNetType(NetEnum netType) {
        this.netType = netType;
    }

    public BaseSerializer getSerializer() {
        return serializer;
    }

    public void setSerializer(BaseSerializer serializer) {
        this.serializer = serializer;
    }

    public int getCorePoolSize() {
        return corePoolSize;
    }

    public void setCorePoolSize(int corePoolSize) {
        this.corePoolSize = corePoolSize;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public void setMaxPoolSize(int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public void setServiceRegistryClass(Class<? extends BaseServiceRegistry> serviceRegistryClass) {
        this.serviceRegistryClass = serviceRegistryClass;
    }

    public void setServiceRegistryParam(Map<String, String> serviceRegistryParam) {
        this.serviceRegistryParam = serviceRegistryParam;
    }

}
