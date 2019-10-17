package com.yunus.invoker.generic;

/**
 * @author gaoyunfeng
 */
public interface RpcGenericService {
    /**
     * @param iface          接口名称
     * @param version        version
     * @param method         调用方法
     * @param parameterTypes 方法参数类型
     * @param args           参数
     * @return
     */
    Object invoke(String iface, String version, String method, String[] parameterTypes, Object[] args);
}
