package io.github.upowerman.core.registry;

import io.github.upowerman.core.spi.Spi;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 注册服务接口
 *
 * @author gaoyunfeng
 */
@Spi("local")
public interface BaseServiceRegistry {

    /**
     * 启动函数
     *
     * @param param 启动参数
     */
    void start(Map<String, String> param);

    /**
     * 停止注册
     */
    void stop();

    /**
     * 注册服务
     *
     * @param keys  服务类key
     * @param value 服务地址
     * @return
     */
    boolean registry(Set<String> keys, String value);

    /**
     * 移除注册的服务
     *
     * @param keys  服务类key
     * @param value 服务地址
     * @return
     */
    boolean remove(Set<String> keys, String value);

    /**
     * 发现服务
     *
     * @param keys 服务类key
     * @return service 服务地址列表
     */
    Map<String, TreeSet<String>> discovery(Set<String> keys);

    /**
     * 发现单个服务类
     *
     * @param key 服务类key
     * @return service 服务地址
     */
    TreeSet<String> discovery(String key);
}
