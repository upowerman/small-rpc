package io.github.upowerman.core.directory;

import io.github.upowerman.core.registry.BaseServiceRegistry;

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
    /** 2.0 无版本路由语义：消费端装配恒传 null，makeServiceKey 的 #version 分支为 P3 预留 */
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
        String serviceKey = makeServiceKey(service, version);
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

    private static String makeServiceKey(String iface, String version) {
        String serviceKey = iface;
        if (version != null && version.trim().length() > 0) {
            serviceKey += "#" + version;
        }
        return serviceKey;
    }

    @Override
    public void subscribe(String service) {
        // P0 无推送能力，P3 由各注册中心实现变更通知
    }
}