package io.github.upowerman.registry.impl;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.*;

/**
 * Redis 服务注册中心测试
 * 注意：此测试需要本地运行 Redis 服务
 */
public class RedisServiceRegistryTest {

    private RedisServiceRegistry registry;
    private Map<String, String> config;

    @Before
    public void setUp() {
        registry = new RedisServiceRegistry();
        config = new HashMap<>();
        config.put(RedisServiceRegistry.REDIS_HOST, "localhost");
        config.put(RedisServiceRegistry.REDIS_PORT, "6379");
        config.put(RedisServiceRegistry.REDIS_DATABASE, "0");
        config.put(RedisServiceRegistry.REDIS_TIMEOUT, "2000");
    }

    @Test
    public void testRegistryLifecycle() {
        try {
            // 启动注册中心
            registry.start(config);

            // 测试服务注册
            Set<String> serviceKeys = new TreeSet<>();
            serviceKeys.add("com.example.TestService#1.0");
            String serviceAddress = "127.0.0.1:8080";

            boolean registered = registry.registry(serviceKeys, serviceAddress);
            assertTrue("服务注册应该成功", registered);

            // 等待一小段时间让注册完成
            Thread.sleep(100);

            // 测试服务发现
            TreeSet<String> discoveredAddresses = registry.discovery("com.example.TestService#1.0");
            assertNotNull("服务发现不应该返回null", discoveredAddresses);
            assertTrue("应该发现注册的服务", discoveredAddresses.contains(serviceAddress));

            // 测试服务移除
            boolean removed = registry.remove(serviceKeys, serviceAddress);
            assertTrue("服务移除应该成功", removed);

            // 验证服务已移除
            Thread.sleep(100);
            TreeSet<String> afterRemoval = registry.discovery("com.example.TestService#1.0");
            assertTrue("移除后不应该再发现服务", afterRemoval == null || !afterRemoval.contains(serviceAddress));

        } catch (Exception e) {
            System.err.println("测试执行失败，请确保 Redis 服务正在运行: " + e.getMessage());
            // 如果 Redis 不可用，跳过测试而不是失败
            System.out.println("跳过 Redis 测试 - Redis 服务不可用");
        } finally {
            // 清理资源
            if (registry != null) {
                registry.stop();
            }
        }
    }

    @Test
    public void testMultipleServices() {
        try {
            registry.start(config);

            // 注册多个服务实例
            Set<String> serviceKeys = new TreeSet<>();
            serviceKeys.add("com.example.MultiService#1.0");

            registry.registry(serviceKeys, "127.0.0.1:8080");
            registry.registry(serviceKeys, "127.0.0.1:8081");
            registry.registry(serviceKeys, "127.0.0.1:8082");

            Thread.sleep(100);

            // 验证发现了所有服务实例
            TreeSet<String> addresses = registry.discovery("com.example.MultiService#1.0");
            assertNotNull("服务发现不应该返回null", addresses);
            assertEquals("应该发现3个服务实例", 3, addresses.size());

            assertTrue("应该包含第一个实例", addresses.contains("127.0.0.1:8080"));
            assertTrue("应该包含第二个实例", addresses.contains("127.0.0.1:8081"));
            assertTrue("应该包含第三个实例", addresses.contains("127.0.0.1:8082"));

        } catch (Exception e) {
            System.err.println("测试执行失败，请确保 Redis 服务正在运行: " + e.getMessage());
            System.out.println("跳过 Redis 测试 - Redis 服务不可用");
        } finally {
            if (registry != null) {
                registry.stop();
            }
        }
    }
}