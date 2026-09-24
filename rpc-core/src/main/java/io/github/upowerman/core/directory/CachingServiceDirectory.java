package io.github.upowerman.core.directory;

import io.github.upowerman.core.registry.Registry;
import io.github.upowerman.core.registry.ServiceListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 具有全量快照缓存与降级能力的服务目录。
 *
 * <p>消费端服务调用只读取本地缓存，永不阻塞，不直接访问注册中心；
 * <p>当调用 {@link #subscribe(String)} 时向注册中心建立订阅，注册中心通过 {@link ServiceListener#onChange(List)} 推送全量快照；
 * <p>如果回调发生异常（例如数据异常或反序列化失败）或注册中心不可用，已缓存的服务列表被冻结保留（即降级）。
 */
public class CachingServiceDirectory implements ServiceDirectory {

    private static final Logger logger = LoggerFactory.getLogger(CachingServiceDirectory.class);

    private final Registry registry;
    private final ConcurrentHashMap<String, List<ServiceInstance>> cache = new ConcurrentHashMap<>();
    private final Set<String> subscribedServices = ConcurrentHashMap.newKeySet();

    public CachingServiceDirectory(Registry registry) {
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        this.registry = registry;
    }

    @Override
    public List<ServiceInstance> list(String service) {
        if (service == null) {
            return Collections.emptyList();
        }
        List<ServiceInstance> instances = cache.get(service);
        return instances != null ? instances : Collections.<ServiceInstance>emptyList();
    }

    @Override
    public void subscribe(final String service) {
        if (service == null) {
            return;
        }
        if (!subscribedServices.add(service)) {
            // 已订阅，幂等直接返回
            return;
        }
        try {
            registry.subscribe(service, new ServiceListener() {
                @Override
                public void onChange(List<ServiceInstance> instances) {
                    try {
                        if (instances == null) {
                            throw new IllegalArgumentException("instances snapshot must not be null");
                        }
                        List<ServiceInstance> copy = Collections.unmodifiableList(new ArrayList<>(instances));
                        cache.put(service, copy);
                    } catch (Throwable t) {
                        logger.warn("Failed to update cache for service '{}', retaining existing cache (degraded): {}",
                                service, t.getMessage(), t);
                    }
                }
            });
        } catch (Throwable t) {
            logger.warn("Failed to subscribe service '{}' from registry: {}", service, t.getMessage(), t);
        }
    }
}
