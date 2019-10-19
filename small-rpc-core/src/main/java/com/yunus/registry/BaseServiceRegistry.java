package com.yunus.registry;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 以后增加注册中心
 *
 * @author gaoyunfeng
 */
public abstract class BaseServiceRegistry {

    /**
     * 开始
     *
     * @param param
     */
    public abstract void start(Map<String, String> param);

    /**
     * 停止
     */
    public abstract void stop();


    /**
     * registry service, for mult
     *
     * @param keys  service key
     * @param value service value/ip:port
     * @return
     */
    public abstract boolean registry(Set<String> keys, String value);


    /**
     * remove service, for mult
     *
     * @param keys
     * @param value
     * @return
     */
    public abstract boolean remove(Set<String> keys, String value);

    /**
     * discovery services, for mult
     *
     * @param keys
     * @return
     */
    public abstract Map<String, TreeSet<String>> discovery(Set<String> keys);

    /**
     * discovery service, for one
     *
     * @param key service key
     * @return service value/ip:port
     */
    public abstract TreeSet<String> discovery(String key);
}
