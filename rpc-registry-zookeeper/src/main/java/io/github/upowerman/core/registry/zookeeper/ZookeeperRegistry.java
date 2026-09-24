package io.github.upowerman.core.registry.zookeeper;

import io.github.upowerman.core.directory.ServiceInstance;
import io.github.upowerman.core.registry.Registry;
import io.github.upowerman.core.registry.ServiceListener;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.imps.CuratorFrameworkState;
import org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheListener;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * ZooKeeper 注册中心实现（基于 Curator 5.6.0）。
 *
 * <p>节点布局：{@code /{namespace}/{service}/instances/{address}}
 * <ul>
 *   <li>{@code {service}} 与 {@code instances} 为持久节点；</li>
 *   <li>{@code {address}} 为临时节点（EPHEMERAL），服务下线或掉线后自动摘除；</li>
 *   <li>使用 {@link PathChildrenCache} 监听 {@code instances} 子节点变更，真推送；</li>
 *   <li>所有推送回调在 Curator 线程内严格 try/catch，防止异常污染 Curator 连接线程。</li>
 * </ul>
 */
public class ZookeeperRegistry implements Registry {

    private static final Logger logger = LoggerFactory.getLogger(ZookeeperRegistry.class);

    private static final String DEFAULT_CONNECT = "localhost:2181";
    private static final String DEFAULT_NAMESPACE = "small-rpc";
    private static final int DEFAULT_SESSION_TIMEOUT_MS = 10000;
    private static final int DEFAULT_CONNECTION_TIMEOUT_MS = 3000;

    private volatile CuratorFramework client;
    private final ConcurrentHashMap<String, ServiceSubscription> subscriptions = new ConcurrentHashMap<>();
    private volatile boolean destroyed = false;

    @Override
    public synchronized void init(Map<String, String> param) {
        if (client != null && client.getState() == CuratorFrameworkState.STARTED) {
            return;
        }

        String connect = param != null && param.containsKey("zk.connect") ? param.get("zk.connect") : DEFAULT_CONNECT;
        String namespace = param != null && param.containsKey("zk.namespace") ? param.get("zk.namespace") : DEFAULT_NAMESPACE;

        int sessionTimeout = DEFAULT_SESSION_TIMEOUT_MS;
        if (param != null && param.containsKey("zk.session-timeout-ms")) {
            try {
                sessionTimeout = Integer.parseInt(param.get("zk.session-timeout-ms"));
            } catch (NumberFormatException ignored) {
            }
        }

        int connectionTimeout = DEFAULT_CONNECTION_TIMEOUT_MS;
        if (param != null && param.containsKey("zk.connection-timeout-ms")) {
            try {
                connectionTimeout = Integer.parseInt(param.get("zk.connection-timeout-ms"));
            } catch (NumberFormatException ignored) {
            }
        }

        client = CuratorFrameworkFactory.builder()
                .connectString(connect)
                .namespace(namespace)
                .sessionTimeoutMs(sessionTimeout)
                .connectionTimeoutMs(connectionTimeout)
                .retryPolicy(new ExponentialBackoffRetry(1000, 3))
                .build();
        client.start();
        try {
            client.blockUntilConnected(connectionTimeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        destroyed = false;
        logger.info("ZookeeperRegistry initialized with connect='{}', namespace='{}'", connect, namespace);
    }

    @Override
    public synchronized void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;

        for (ServiceSubscription sub : subscriptions.values()) {
            try {
                sub.cache.close();
            } catch (Throwable t) {
                logger.warn("Error closing PathChildrenCache: {}", t.getMessage());
            }
        }
        subscriptions.clear();

        if (client != null) {
            try {
                client.close();
            } catch (Throwable t) {
                logger.warn("Error closing CuratorFramework: {}", t.getMessage());
            }
            client = null;
        }
        logger.info("ZookeeperRegistry destroyed");
    }

    @Override
    public void register(String service, ServiceInstance instance) {
        checkStarted();
        String path = buildInstancePath(service, instance.getAddress());
        try {
            client.create()
                    .creatingParentsIfNeeded()
                    .withMode(CreateMode.EPHEMERAL)
                    .forPath(path);
            logger.info("Registered service '{}' instance '{}' to ZooKeeper", service, instance.getAddress());
        } catch (KeeperException.NodeExistsException e) {
            // 节点已存在，幂等处理
            logger.debug("Instance node already exists: {}", path);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to register instance to ZooKeeper: " + path, e);
        }
    }

    @Override
    public void unregister(String service, ServiceInstance instance) {
        if (client == null || client.getState() != CuratorFrameworkState.STARTED) {
            return;
        }
        String path = buildInstancePath(service, instance.getAddress());
        try {
            client.delete()
                    .guaranteed()
                    .forPath(path);
            logger.info("Unregistered service '{}' instance '{}' from ZooKeeper", service, instance.getAddress());
        } catch (KeeperException.NoNodeException e) {
            // 节点已不存在，幂等处理
            logger.debug("Instance node already deleted: {}", path);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to unregister instance from ZooKeeper: " + path, e);
        }
    }

    @Override
    public void subscribe(final String service, final ServiceListener listener) {
        checkStarted();
        if (service == null || listener == null) {
            return;
        }

        ServiceSubscription subscription = subscriptions.get(service);
        if (subscription == null) {
            synchronized (subscriptions) {
                subscription = subscriptions.get(service);
                if (subscription == null) {
                    String instancesPath = buildInstancesPath(service);
                    final PathChildrenCache cache = new PathChildrenCache(client, instancesPath, true);
                    final ServiceSubscription newSub = new ServiceSubscription(cache);

                    cache.getListenable().addListener(new PathChildrenCacheListener() {
                        @Override
                        public void childEvent(CuratorFramework client, PathChildrenCacheEvent event) {
                            switch (event.getType()) {
                                case CHILD_ADDED:
                                case CHILD_REMOVED:
                                case CHILD_UPDATED:
                                case INITIALIZED:
                                    notifyListeners(service, newSub);
                                    break;
                                default:
                                    break;
                            }
                        }
                    });

                    try {
                        cache.start(PathChildrenCache.StartMode.BUILD_INITIAL_CACHE);
                    } catch (Exception e) {
                        try {
                            cache.close();
                        } catch (IOException ignored) {
                        }
                        throw new IllegalStateException("Failed to start PathChildrenCache for service: " + service, e);
                    }

                    subscriptions.put(service, newSub);
                    subscription = newSub;
                }
            }
        }

        subscription.listeners.add(listener);
        // 订阅建立时立即推当前全量快照
        pushSnapshot(service, subscription.cache, listener);
    }

    @Override
    public void unsubscribe(String service, ServiceListener listener) {
        if (service == null || listener == null) {
            return;
        }
        ServiceSubscription subscription = subscriptions.get(service);
        if (subscription != null) {
            subscription.listeners.remove(listener);
            if (subscription.listeners.isEmpty()) {
                synchronized (subscriptions) {
                    if (subscription.listeners.isEmpty()) {
                        subscriptions.remove(service);
                        try {
                            subscription.cache.close();
                        } catch (IOException e) {
                            logger.warn("Error closing PathChildrenCache for service '{}': {}", service, e.getMessage());
                        }
                    }
                }
            }
        }
    }

    private void checkStarted() {
        if (client == null || client.getState() != CuratorFrameworkState.STARTED) {
            throw new IllegalStateException("ZookeeperRegistry has not been initialized or already destroyed");
        }
    }

    private String buildInstancesPath(String service) {
        return "/" + service + "/instances";
    }

    private String buildInstancePath(String service, String address) {
        return "/" + service + "/instances/" + address;
    }

    private List<ServiceInstance> extractInstances(PathChildrenCache cache) {
        List<ChildData> currentData = cache.getCurrentData();
        if (currentData == null || currentData.isEmpty()) {
            return Collections.emptyList();
        }
        List<ServiceInstance> instances = new ArrayList<>(currentData.size());
        for (ChildData cd : currentData) {
            String path = cd.getPath();
            int idx = path.lastIndexOf('/');
            String address = idx >= 0 ? path.substring(idx + 1) : path;
            instances.add(new ServiceInstance(address));
        }
        return Collections.unmodifiableList(instances);
    }

    private void notifyListeners(String service, ServiceSubscription subscription) {
        List<ServiceInstance> snapshot = extractInstances(subscription.cache);
        for (ServiceListener l : subscription.listeners) {
            try {
                l.onChange(snapshot);
            } catch (Throwable t) {
                logger.warn("Error notifying listener for service '{}': {}", service, t.getMessage(), t);
            }
        }
    }

    private void pushSnapshot(String service, PathChildrenCache cache, ServiceListener listener) {
        List<ServiceInstance> snapshot = extractInstances(cache);
        try {
            listener.onChange(snapshot);
        } catch (Throwable t) {
            logger.warn("Error pushing initial snapshot to listener for service '{}': {}", service, t.getMessage(), t);
        }
    }

    private static class ServiceSubscription {
        final PathChildrenCache cache;
        final CopyOnWriteArrayList<ServiceListener> listeners = new CopyOnWriteArrayList<>();

        ServiceSubscription(PathChildrenCache cache) {
            this.cache = cache;
        }
    }
}
