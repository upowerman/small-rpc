package io.github.upowerman.core.directory;

import io.github.upowerman.provider.RpcProviderFactory;
import io.github.upowerman.registry.BaseServiceRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * P0 适配器：包装 1.x BaseServiceRegistry 的 discovery 拉取，
 * 把 1.x 的 serviceKey(address 集合) 转成 List&lt;ServiceInstance&gt;
 */
public class PullServiceDirectory implements ServiceDirectory {

    private final BaseServiceRegistry registry;
    private final String version;

    public PullServiceDirectory(BaseServiceRegistry registry, String version) {
        this.registry = registry;
        this.version = version;
    }

    @Override
    public List<ServiceInstance> list(String service) {
        if (registry == null) {
            return Collections.emptyList();
        }
        String serviceKey = RpcProviderFactory.makeServiceKey(service, version);
        TreeSet<String> addresses = registry.discovery(serviceKey);
        if (addresses == null || addresses.isEmpty()) {
            return Collections.emptyList();
        }
        List<ServiceInstance> instances = new ArrayList<ServiceInstance>(addresses.size());
        for (String address : addresses) {
            instances.add(new ServiceInstance(address));
        }
        return instances;
    }

    @Override
    public void subscribe(String service) {
        // P0 无推送能力，P3 由各注册中心实现变更通知
    }
}