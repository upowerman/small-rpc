package io.github.upowerman.registry.impl;

import io.github.upowerman.registry.BaseServiceRegistry;
import org.apache.curator.RetryPolicy;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * Zookeeper 服务注册中心实现
 *
 * @author gaoyunfeng
 */
public class ZookeeperServiceRegistry extends BaseServiceRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ZookeeperServiceRegistry.class);

    public static final String ZK_CONNECT_STRING = "ZK_CONNECT_STRING";
    public static final String ZK_NAMESPACE = "ZK_NAMESPACE";
    public static final String ZK_BASE_PATH = "ZK_BASE_PATH";
    public static final String ZK_SESSION_TIMEOUT = "ZK_SESSION_TIMEOUT";
    public static final String ZK_CONNECTION_TIMEOUT = "ZK_CONNECTION_TIMEOUT";

    private static final String DEFAULT_CONNECT_STRING = "localhost:2181";
    private static final String DEFAULT_NAMESPACE = "small-rpc";
    private static final String DEFAULT_BASE_PATH = "/services";
    private static final int DEFAULT_SESSION_TIMEOUT = 60_000;
    private static final int DEFAULT_CONNECTION_TIMEOUT = 15_000;

    private CuratorFramework client;
    private String basePath;
    private final Set<String> registeredNodePaths = Collections.synchronizedSet(new HashSet<String>());

    @Override
    public void start(Map<String, String> param) {
        Map<String, String> config = (param == null) ? Collections.emptyMap() : param;
        String connectString = config.getOrDefault(ZK_CONNECT_STRING, DEFAULT_CONNECT_STRING);
        String namespace = config.getOrDefault(ZK_NAMESPACE, DEFAULT_NAMESPACE);
        this.basePath = normalizePath(config.getOrDefault(ZK_BASE_PATH, DEFAULT_BASE_PATH));
        int sessionTimeout = parseInt(config.get(ZK_SESSION_TIMEOUT), DEFAULT_SESSION_TIMEOUT);
        int connectionTimeout = parseInt(config.get(ZK_CONNECTION_TIMEOUT), DEFAULT_CONNECTION_TIMEOUT);

        try {
            RetryPolicy retryPolicy = new ExponentialBackoffRetry(1000, 3);
            client = CuratorFrameworkFactory.builder()
                    .connectString(connectString)
                    .namespace(namespace)
                    .sessionTimeoutMs(sessionTimeout)
                    .connectionTimeoutMs(connectionTimeout)
                    .retryPolicy(retryPolicy)
                    .build();
            client.start();

            boolean connected = client.blockUntilConnected(connectionTimeout, TimeUnit.MILLISECONDS);
            if (!connected) {
                throw new RuntimeException("连接 Zookeeper 超时");
            }
            ensurePath(basePath);

            logger.info("ZookeeperServiceRegistry 启动成功: connectString={}, namespace={}, basePath={}",
                    connectString, namespace, basePath);
        } catch (Exception e) {
            closeQuietly();
            logger.error("ZookeeperServiceRegistry 启动失败", e);
            throw new RuntimeException("Zookeeper 服务注册中心启动失败", e);
        }
    }

    @Override
    public void stop() {
        try {
            synchronized (registeredNodePaths) {
                for (String nodePath : registeredNodePaths) {
                    try {
                        if (client != null && client.checkExists().forPath(nodePath) != null) {
                            client.delete().forPath(nodePath);
                        }
                    } catch (Exception e) {
                        logger.warn("删除 zookeeper 注册节点失败: {}", nodePath, e);
                    }
                }
                registeredNodePaths.clear();
            }
        } finally {
            closeQuietly();
        }
    }

    @Override
    public boolean registry(Set<String> keys, String value) {
        if (keys == null || keys.isEmpty() || value == null || value.trim().isEmpty()) {
            logger.warn("注册参数无效: keys={}, value={}", keys, value);
            return false;
        }
        if (client == null) {
            logger.error("ZookeeperServiceRegistry 未启动，无法注册服务");
            return false;
        }

        try {
            for (String serviceKey : keys) {
                String servicePath = buildServicePath(serviceKey);
                ensurePath(servicePath);

                String nodePath = servicePath + "/" + encodeAddress(value);
                if (client.checkExists().forPath(nodePath) != null) {
                    client.delete().forPath(nodePath);
                }

                client.create()
                        .withMode(CreateMode.EPHEMERAL)
                        .forPath(nodePath, value.getBytes(StandardCharsets.UTF_8));
                registeredNodePaths.add(nodePath);
                logger.info("服务注册成功: {} -> {}", serviceKey, value);
            }
            return true;
        } catch (Exception e) {
            logger.error("服务注册失败: keys={}, value={}", keys, value, e);
            return false;
        }
    }

    @Override
    public boolean remove(Set<String> keys, String value) {
        if (keys == null || keys.isEmpty() || value == null || value.trim().isEmpty()) {
            return false;
        }
        if (client == null) {
            return false;
        }

        try {
            for (String serviceKey : keys) {
                String nodePath = buildServicePath(serviceKey) + "/" + encodeAddress(value);
                if (client.checkExists().forPath(nodePath) != null) {
                    client.delete().forPath(nodePath);
                }
                registeredNodePaths.remove(nodePath);
                logger.info("服务移除成功: {} -> {}", serviceKey, value);
            }
            return true;
        } catch (Exception e) {
            logger.error("服务移除失败: keys={}, value={}", keys, value, e);
            return false;
        }
    }

    @Override
    public Map<String, TreeSet<String>> discovery(Set<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return null;
        }
        Map<String, TreeSet<String>> result = new HashMap<>();
        for (String serviceKey : keys) {
            TreeSet<String> addresses = discovery(serviceKey);
            if (addresses != null && !addresses.isEmpty()) {
                result.put(serviceKey, addresses);
            }
        }
        return result;
    }

    @Override
    public TreeSet<String> discovery(String key) {
        if (key == null || key.trim().isEmpty() || client == null) {
            return null;
        }

        String servicePath = buildServicePath(key);
        try {
            if (client.checkExists().forPath(servicePath) == null) {
                return null;
            }

            Set<String> children = new HashSet<>(client.getChildren().forPath(servicePath));
            if (children.isEmpty()) {
                return null;
            }

            TreeSet<String> addresses = new TreeSet<>();
            for (String child : children) {
                String nodePath = servicePath + "/" + child;
                String address = decodeAddress(child);
                byte[] data = client.getData().forPath(nodePath);
                if (data != null && data.length > 0) {
                    address = new String(data, StandardCharsets.UTF_8);
                }
                if (address != null && !address.trim().isEmpty()) {
                    addresses.add(address);
                }
            }

            logger.debug("发现服务: {} -> {}", key, addresses);
            return addresses.isEmpty() ? null : addresses;
        } catch (Exception e) {
            logger.error("服务发现失败: key={}", key, e);
            return null;
        }
    }

    private void ensurePath(String path) throws Exception {
        if (client.checkExists().forPath(path) == null) {
            client.create().creatingParentsIfNeeded().forPath(path);
        }
    }

    private String buildServicePath(String serviceKey) {
        return basePath + "/" + serviceKey;
    }

    private String normalizePath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return DEFAULT_BASE_PATH;
        }
        String normalized = path.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private int parseInt(String value, int defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String encodeAddress(String address) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(address.getBytes(StandardCharsets.UTF_8));
    }

    private String decodeAddress(String encodedAddress) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(encodedAddress);
            return new String(decoded, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return encodedAddress;
        }
    }

    private void closeQuietly() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                logger.warn("关闭 Zookeeper 客户端失败", e);
            } finally {
                client = null;
            }
        }
    }
}
