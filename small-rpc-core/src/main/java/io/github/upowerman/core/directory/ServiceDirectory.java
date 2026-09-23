package io.github.upowerman.core.directory;

import java.util.List;

/**
 * 消费者的本地服务视图，调用热路径只经过它，不直接访问注册中心。
 * P0 由适配器每次拉取（等价 1.x 行为）；P3 接入订阅推送 + 缓存降级。
 */
public interface ServiceDirectory {

    List<ServiceInstance> list(String service);

    /** 订阅服务变更。P0 适配器为空实现。 */
    void subscribe(String service);
}