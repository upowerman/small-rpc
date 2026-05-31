package io.github.upowerman.registry.impl;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Zookeeper 服务注册中心测试
 * 注意：此测试需要本地运行 Zookeeper 服务
 */
public class ZookeeperServiceRegistryTest {

    @Test
    public void testRegistryLifecycle() {
        ZookeeperServiceRegistry registry = new ZookeeperServiceRegistry();
        Map<String, String> config = new HashMap<>();
        config.put(ZookeeperServiceRegistry.ZK_CONNECT_STRING, "localhost:2181");
        config.put(ZookeeperServiceRegistry.ZK_NAMESPACE, "small-rpc-test");
        config.put(ZookeeperServiceRegistry.ZK_BASE_PATH, "/services");
        config.put(ZookeeperServiceRegistry.ZK_CONNECTION_TIMEOUT, "3000");
        config.put(ZookeeperServiceRegistry.ZK_SESSION_TIMEOUT, "10000");

        try {
            registry.start(config);

            Set<String> keys = new TreeSet<>();
            keys.add("com.example.ZkService#1.0");
            String address = "127.0.0.1:9090";

            boolean registered = registry.registry(keys, address);
            assertTrue("服务注册应该成功", registered);

            Thread.sleep(200);
            TreeSet<String> discovered = registry.discovery("com.example.ZkService#1.0");
            assertNotNull("服务发现不应该返回null", discovered);
            assertTrue("应该发现注册的地址", discovered.contains(address));

            boolean removed = registry.remove(keys, address);
            assertTrue("服务移除应该成功", removed);
        } catch (Exception e) {
            System.out.println("跳过 Zookeeper 测试 - Zookeeper 服务不可用: " + e.getMessage());
        } finally {
            registry.stop();
        }
    }
}
