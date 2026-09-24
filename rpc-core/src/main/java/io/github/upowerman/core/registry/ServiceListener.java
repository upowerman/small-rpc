package io.github.upowerman.core.registry;

import io.github.upowerman.core.directory.ServiceInstance;

import java.util.List;

/**
 * 服务实例变更监听器。
 *
 * <p>全量快照语义：每次回调传入该服务当前完整的可用实例列表。
 */
public interface ServiceListener {

    /**
     * 当服务实例列表发生变更（或首次订阅建立）时调用。
     *
     * @param instances 该服务当前全量可用实例快照
     */
    void onChange(List<ServiceInstance> instances);
}
