package io.github.upowerman.registry.impl;

import io.github.upowerman.registry.BaseServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Redis 服务注册中心实现
 * 使用 Redis 进行服务注册与发现
 *
 * @author gaoyunfeng
 */
public class RedisServiceRegistry extends BaseServiceRegistry {

    private static final Logger logger = LoggerFactory.getLogger(RedisServiceRegistry.class);

    /**
     * Redis 连接配置参数
     */
    public static final String REDIS_HOST = "REDIS_HOST";
    public static final String REDIS_PORT = "REDIS_PORT";
    public static final String REDIS_PASSWORD = "REDIS_PASSWORD";
    public static final String REDIS_DATABASE = "REDIS_DATABASE";
    public static final String REDIS_TIMEOUT = "REDIS_TIMEOUT";

    /**
     * Redis key 前缀
     */
    private static final String SERVICE_KEY_PREFIX = "rpc:services:";
    private static final String HEALTH_KEY_PREFIX = "rpc:health:";

    /**
     * 服务健康检查间隔（秒）
     */
    private static final int HEALTH_CHECK_INTERVAL = 30;
    /**
     * 服务 TTL（秒）
     */
    private static final int SERVICE_TTL = 60;

    private JedisPool jedisPool;
    private ScheduledExecutorService scheduledExecutor;
    private String currentServiceAddress;
    private Set<String> registeredServiceKeys;

    @Override
    public void start(Map<String, String> param) {
        try {
            // 解析配置参数
            String host = param.getOrDefault(REDIS_HOST, "localhost");
            int port = Integer.parseInt(param.getOrDefault(REDIS_PORT, "6379"));
            String password = param.get(REDIS_PASSWORD);
            int database = Integer.parseInt(param.getOrDefault(REDIS_DATABASE, "0"));
            int timeout = Integer.parseInt(param.getOrDefault(REDIS_TIMEOUT, "2000"));

            // 创建 Jedis 连接池配置
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(10);
            poolConfig.setMaxIdle(5);
            poolConfig.setMinIdle(1);
            poolConfig.setTestOnBorrow(true);
            poolConfig.setTestOnReturn(true);
            poolConfig.setTestWhileIdle(true);

            // 创建连接池
            if (password != null && !password.trim().isEmpty()) {
                jedisPool = new JedisPool(poolConfig, host, port, timeout, password, database);
            } else {
                jedisPool = new JedisPool(poolConfig, host, port, timeout, null, database);
            }

            // 测试连接
            try (Jedis jedis = jedisPool.getResource()) {
                jedis.ping();
                logger.info("Redis 连接成功: {}:{}", host, port);
            }

            // 初始化定时任务执行器
            scheduledExecutor = Executors.newScheduledThreadPool(1, r -> {
                Thread thread = new Thread(r, "redis-registry-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

            registeredServiceKeys = new TreeSet<>();

            logger.info("RedisServiceRegistry 启动成功");
        } catch (Exception e) {
            logger.error("RedisServiceRegistry 启动失败", e);
            throw new RuntimeException("Redis 服务注册中心启动失败", e);
        }
    }

    @Override
    public void stop() {
        try {
            // 停止心跳任务
            if (scheduledExecutor != null && !scheduledExecutor.isShutdown()) {
                scheduledExecutor.shutdown();
                try {
                    if (!scheduledExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        scheduledExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    scheduledExecutor.shutdownNow();
                }
            }

            // 清理注册的服务
            if (currentServiceAddress != null && registeredServiceKeys != null) {
                remove(registeredServiceKeys, currentServiceAddress);
            }

            // 关闭连接池
            if (jedisPool != null && !jedisPool.isClosed()) {
                jedisPool.close();
            }

            logger.info("RedisServiceRegistry 停止成功");
        } catch (Exception e) {
            logger.error("RedisServiceRegistry 停止失败", e);
        }
    }

    @Override
    public boolean registry(Set<String> keys, String value) {
        if (keys == null || keys.isEmpty() || value == null) {
            logger.warn("注册参数无效: keys={}, value={}", keys, value);
            return false;
        }

        currentServiceAddress = value;
        registeredServiceKeys = new TreeSet<>(keys);

        try (Jedis jedis = jedisPool.getResource()) {
            for (String serviceKey : keys) {
                // 将服务地址添加到 Redis Set 中
                String redisKey = SERVICE_KEY_PREFIX + serviceKey;
                jedis.sadd(redisKey, value);

                // 设置健康检查键并启动心跳
                String healthKey = HEALTH_KEY_PREFIX + serviceKey + ":" + value;
                jedis.setex(healthKey, SERVICE_TTL, String.valueOf(System.currentTimeMillis()));

                logger.info("服务注册成功: {} -> {}", serviceKey, value);
            }

            // 启动心跳任务
            startHeartbeat(keys, value);

            return true;
        } catch (Exception e) {
            logger.error("服务注册失败: keys={}, value={}", keys, value, e);
            return false;
        }
    }

    @Override
    public boolean remove(Set<String> keys, String value) {
        if (keys == null || keys.isEmpty() || value == null) {
            return false;
        }

        try (Jedis jedis = jedisPool.getResource()) {
            for (String serviceKey : keys) {
                // 从 Redis Set 中移除服务地址
                String redisKey = SERVICE_KEY_PREFIX + serviceKey;
                jedis.srem(redisKey, value);

                // 删除健康检查键
                String healthKey = HEALTH_KEY_PREFIX + serviceKey + ":" + value;
                jedis.del(healthKey);

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

        try (Jedis jedis = jedisPool.getResource()) {
            for (String serviceKey : keys) {
                TreeSet<String> addresses = discovery(serviceKey);
                if (addresses != null && !addresses.isEmpty()) {
                    result.put(serviceKey, addresses);
                }
            }
        } catch (Exception e) {
            logger.error("服务发现失败: keys={}", keys, e);
        }

        return result;
    }

    @Override
    public TreeSet<String> discovery(String key) {
        if (key == null || key.trim().isEmpty()) {
            return null;
        }

        try (Jedis jedis = jedisPool.getResource()) {
            String redisKey = SERVICE_KEY_PREFIX + key;
            Set<String> addresses = jedis.smembers(redisKey);

            if (addresses == null || addresses.isEmpty()) {
                logger.debug("未发现服务: {}", key);
                return null;
            }

            // 过滤健康的服务实例
            TreeSet<String> healthyAddresses = new TreeSet<>();
            for (String address : addresses) {
                String healthKey = HEALTH_KEY_PREFIX + key + ":" + address;
                if (jedis.exists(healthKey)) {
                    healthyAddresses.add(address);
                } else {
                    // 清理不健康的服务实例
                    jedis.srem(redisKey, address);
                    logger.info("清理不健康的服务实例: {} -> {}", key, address);
                }
            }

            logger.debug("发现服务: {} -> {}", key, healthyAddresses);
            return healthyAddresses.isEmpty() ? null : healthyAddresses;

        } catch (Exception e) {
            logger.error("服务发现失败: key={}", key, e);
            return null;
        }
    }

    /**
     * 启动心跳任务，定期更新服务健康状态
     */
    private void startHeartbeat(Set<String> keys, String value) {
        if (scheduledExecutor == null || scheduledExecutor.isShutdown()) {
            return;
        }

        scheduledExecutor.scheduleWithFixedDelay(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                for (String serviceKey : keys) {
                    String healthKey = HEALTH_KEY_PREFIX + serviceKey + ":" + value;
                    jedis.setex(healthKey, SERVICE_TTL, String.valueOf(System.currentTimeMillis()));
                }
                logger.debug("心跳更新成功: {} -> {}", keys, value);
            } catch (Exception e) {
                logger.error("心跳更新失败: keys={}, value={}", keys, value, e);
            }
        }, HEALTH_CHECK_INTERVAL, HEALTH_CHECK_INTERVAL, TimeUnit.SECONDS);
    }
}