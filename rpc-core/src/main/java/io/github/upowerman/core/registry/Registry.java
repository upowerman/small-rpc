package io.github.upowerman.core.registry;

import io.github.upowerman.core.directory.ServiceInstance;
import io.github.upowerman.core.spi.Spi;

import java.util.Map;

/**
 * 注册中心接口。
 *
 * <p>实现是<b>进程级 SPI 单例</b>（通过 {@link io.github.upowerman.core.spi.SpiLoader} 加载）。
 * <ul>
 *   <li>{@link #init(Map)}：初始化并建立资源/连接，支持幂等调用（重复以相同配置初始化不产生副作用），失败大声抛出异常（fail-fast）；</li>
 *   <li>{@link #destroy()}：释放连接与资源，必须是幂等的（多次调用不抛出异常）；</li>
 *   <li>{@link #register(String, ServiceInstance)}：向注册中心注册服务实例；</li>
 *   <li>{@link #unregister(String, ServiceInstance)}：从注册中心注销服务实例；</li>
 *   <li>{@link #subscribe(String, ServiceListener)}：订阅服务实例变更，订阅建立时必须立即向传入的 listener 推送当前全量实例快照；</li>
 *   <li>{@link #unsubscribe(String, ServiceListener)}：取消对服务实例变更的订阅；</li>
 *   <li>推送线程中 listener 回调抛出异常不得影响注册中心自身状态及对其他 listener 的推送。</li>
 * </ul>
 */
@Spi("local")
public interface Registry {

    /**
     * 初始化注册中心。
     *
     * @param param 配置参数
     */
    void init(Map<String, String> param);

    /**
     * 销毁注册中心并释放连接资源（幂等）。
     */
    void destroy();

    /**
     * 注册服务实例。
     *
     * @param service  服务名（通常为接口全限定名）
     * @param instance 实例信息
     */
    void register(String service, ServiceInstance instance);

    /**
     * 注销服务实例。
     *
     * @param service  服务名
     * @param instance 实例信息
     */
    void unregister(String service, ServiceInstance instance);

    /**
     * 订阅服务变更。订阅成功后应立即触发一次 {@link ServiceListener#onChange(java.util.List)} 推送当前全量快照。
     *
     * @param service  服务名
     * @param listener 监听器
     */
    void subscribe(String service, ServiceListener listener);

    /**
     * 取消订阅服务变更。
     *
     * @param service  服务名
     * @param listener 待移除的监听器
     */
    void unsubscribe(String service, ServiceListener listener);
}
