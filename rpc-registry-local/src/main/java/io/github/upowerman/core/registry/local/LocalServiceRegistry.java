package io.github.upowerman.core.registry.local;

import io.github.upowerman.core.directory.ServiceInstance;
import io.github.upowerman.core.registry.Registry;
import io.github.upowerman.core.registry.ServiceListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 本地直连注册中心（2.0 形态）。
 *
 * <p>实现 {@link Registry} 接口：
 * <ul>
 *   <li>内存维护 {@code service -> Set<address>} 与 {@code service -> List<ServiceListener>}；</li>
 *   <li>register/unregister 改动内存后同步遍历通知对应服务的全部 listener；</li>
 *   <li>subscribe 登记 listener 并立即向其推送一次当前全量快照；</li>
 *   <li>兼容 {@link #DIRECT_ADDRESS} 语义：若配置了 DIRECT_ADDRESS，则任何服务的订阅快照都包含该地址；</li>
 *   <li>destroy 清空内存状态（幂等）。</li>
 * </ul>
 */
public class LocalServiceRegistry implements Registry {

    private static final Logger logger = LoggerFactory.getLogger(LocalServiceRegistry.class);

    /**
     * 指定直连 rpc 地址的配置 key
     */
    public static final String DIRECT_ADDRESS = "DIRECT_ADDRESS";

    private final ConcurrentHashMap<String, Map<String, ServiceInstance>> serviceInstances = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<ServiceListener>> serviceListeners = new ConcurrentHashMap<>();
    private volatile String directAddress = null;

    @Override
    public void init(Map<String, String> param) {
        if (param != null && !param.isEmpty()) {
            String addr = param.get(DIRECT_ADDRESS);
            if (addr != null && !addr.trim().isEmpty()) {
                this.directAddress = addr.trim();
                return;
            }
        }
        this.directAddress = null;
    }

    @Override
    public void destroy() {
        serviceInstances.clear();
        serviceListeners.clear();
        directAddress = null;
    }

    @Override
    public void register(String service, ServiceInstance instance) {
        if (service == null || instance == null || instance.getAddress() == null) {
            return;
        }
        Map<String, ServiceInstance> instances = serviceInstances.computeIfAbsent(service,
                k -> new ConcurrentHashMap<>());
        // 存完整实例：weight/startTime/warmup 随订阅快照原样到达消费端
        instances.put(instance.getAddress(), instance);
        notifyListeners(service);
    }

    @Override
    public void unregister(String service, ServiceInstance instance) {
        if (service == null || instance == null || instance.getAddress() == null) {
            return;
        }
        Map<String, ServiceInstance> instances = serviceInstances.get(service);
        if (instances != null) {
            instances.remove(instance.getAddress());
        }
        notifyListeners(service);
    }

    @Override
    public void subscribe(String service, ServiceListener listener) {
        if (service == null || listener == null) {
            return;
        }
        List<ServiceListener> listeners = serviceListeners.computeIfAbsent(service, k -> new CopyOnWriteArrayList<>());
        listeners.add(listener);
        pushSnapshot(service, listener);
    }

    @Override
    public void unsubscribe(String service, ServiceListener listener) {
        if (service == null || listener == null) {
            return;
        }
        List<ServiceListener> listeners = serviceListeners.get(service);
        if (listeners != null) {
            listeners.remove(listener);
        }
    }

    private List<ServiceInstance> buildSnapshot(String service) {
        Map<String, ServiceInstance> registered = serviceInstances.get(service);
        LinkedHashMap<String, ServiceInstance> combined = new LinkedHashMap<>();
        String da = directAddress;
        if (da != null && !da.isEmpty()) {
            combined.put(da, new ServiceInstance(da));
        }
        if (registered != null) {
            combined.putAll(registered);
        }
        return Collections.unmodifiableList(new ArrayList<>(combined.values()));
    }

    private void notifyListeners(String service) {
        List<ServiceListener> listeners = serviceListeners.get(service);
        if (listeners == null || listeners.isEmpty()) {
            return;
        }
        List<ServiceInstance> snapshot = buildSnapshot(service);
        for (ServiceListener listener : listeners) {
            try {
                listener.onChange(snapshot);
            } catch (Throwable t) {
                logger.warn("Error notifying listener for service '{}': {}", service, t.getMessage(), t);
            }
        }
    }

    private void pushSnapshot(String service, ServiceListener listener) {
        List<ServiceInstance> snapshot = buildSnapshot(service);
        try {
            listener.onChange(snapshot);
        } catch (Throwable t) {
            logger.warn("Error pushing initial snapshot to listener for service '{}': {}", service, t.getMessage(), t);
        }
    }
}
