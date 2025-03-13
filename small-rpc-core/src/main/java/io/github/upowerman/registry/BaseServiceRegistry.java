package io.github.upowerman.registry;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 注册服务抽象类
 *
 * @author gaoyunfeng
 */
public abstract class BaseServiceRegistry {

    /**
     * 启动函数
     *
     * @param param 启动参数
     */
    public abstract void start(Map<String, String> param);

    /**
     * 停止注册
     */
    public abstract void stop();


    /**
     * 注册服务
     *
     * @param keys  服务类key
     * @param value 服务地址
     * @return
     */
    public abstract boolean registry(Set<String> keys, String value);


    /**
     * 移除注册的服务
     *
     * @param keys  服务类key
     * @param value 服务地址
     * @return
     */
    public abstract boolean remove(Set<String> keys, String value);

    /**
     * 发现服务
     *
     * @param keys 服务类key
     * @return
     */
    public abstract Map<String, TreeSet<String>> discovery(Set<String> keys);

    /**
     * 发现单个服务类
     *
     * @param key 服务类key
     * @return service 服务地址
     */
    public abstract TreeSet<String> discovery(String key);
}
