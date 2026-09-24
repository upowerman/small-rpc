package io.github.upowerman.core.registry.redis;

import io.github.upowerman.core.directory.ServiceInstance;
import io.github.upowerman.core.registry.Registry;
import io.github.upowerman.core.registry.ServiceListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis 注册中心实现（基于 Jedis 4.4.3）。
 *
 * <p>数据结构：{@code {key-prefix}:registry:{service}} -> Set (member = address)
 * <ul>
 *   <li>register/unregister：SADD / SREM；</li>
 *   <li>推送实现：订阅建立时立即 SMEMBERS 推送一次初始快照，随后启动单线程定时拉取任务按 {@code redis.poll-interval-ms} 比对；</li>
 *   <li>比对发现与上次快照有差异时才触发 {@link ServiceListener#onChange(List)}；</li>
 *   <li>同一 service 状态由该 service 的 task 实例锁同步保护（Review Focus 4）；</li>
 *   <li>unsubscribe 在最后一个 listener 移除时取消定时任务；</li>
 *   <li>destroy 释放连接池并立即停止调度器，不留存活线程。</li>
 * </ul>
 */
public class RedisRegistry implements Registry {

    private static final Logger logger = LoggerFactory.getLogger(RedisRegistry.class);

    private static final String DEFAULT_HOST = "localhost";
    private static final int DEFAULT_PORT = 6379;
    private static final int DEFAULT_DATABASE = 0;
    private static final int DEFAULT_TIMEOUT_MS = 2000;
    private static final String DEFAULT_KEY_PREFIX = "small-rpc";
    private static final long DEFAULT_POLL_INTERVAL_MS = 3000;

    private volatile JedisPool pool;
    private volatile ScheduledExecutorService scheduler;
    private volatile String keyPrefix = DEFAULT_KEY_PREFIX;
    private volatile long pollIntervalMs = DEFAULT_POLL_INTERVAL_MS;
    private final ConcurrentHashMap<String, ServicePollingTask> tasks = new ConcurrentHashMap<>();
    private volatile boolean destroyed = false;

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(1);

    @Override
    public synchronized void init(Map<String, String> param) {
        if (pool != null && !pool.isClosed()) {
            return;
        }

        String host = param != null && param.containsKey("redis.host") ? param.get("redis.host") : DEFAULT_HOST;
        int port = DEFAULT_PORT;
        if (param != null && param.containsKey("redis.port")) {
            try {
                port = Integer.parseInt(param.get("redis.port"));
            } catch (NumberFormatException ignored) {
            }
        }
        int database = DEFAULT_DATABASE;
        if (param != null && param.containsKey("redis.database")) {
            try {
                database = Integer.parseInt(param.get("redis.database"));
            } catch (NumberFormatException ignored) {
            }
        }
        int timeout = DEFAULT_TIMEOUT_MS;
        if (param != null && param.containsKey("redis.timeout-ms")) {
            try {
                timeout = Integer.parseInt(param.get("redis.timeout-ms"));
            } catch (NumberFormatException ignored) {
            }
        }
        String password = param != null && param.containsKey("redis.password") ? param.get("redis.password") : null;
        if (password != null && password.trim().isEmpty()) {
            password = null;
        }

        this.keyPrefix = param != null && param.containsKey("redis.key-prefix")
                ? param.get("redis.key-prefix") : DEFAULT_KEY_PREFIX;

        if (param != null && param.containsKey("redis.poll-interval-ms")) {
            try {
                this.pollIntervalMs = Long.parseLong(param.get("redis.poll-interval-ms"));
            } catch (NumberFormatException ignored) {
            }
        } else {
            this.pollIntervalMs = DEFAULT_POLL_INTERVAL_MS;
        }

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(20);
        poolConfig.setMaxIdle(10);
        poolConfig.setMinIdle(1);
        poolConfig.setTestOnBorrow(false);

        this.pool = new JedisPool(poolConfig, host, port, timeout, password, database);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "small-rpc-redis-poll-" + THREAD_COUNTER.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        });
        this.destroyed = false;
        logger.info("RedisRegistry initialized for host='{}:{}', db={}, prefix='{}', pollInterval={}ms",
                host, port, database, keyPrefix, pollIntervalMs);
    }

    @Override
    public synchronized void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;

        for (ServicePollingTask task : tasks.values()) {
            synchronized (task) {
                if (task.future != null) {
                    task.future.cancel(true);
                }
                task.listeners.clear();
            }
        }
        tasks.clear();

        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }

        if (pool != null) {
            try {
                pool.close();
            } catch (Throwable t) {
                logger.warn("Error closing JedisPool: {}", t.getMessage());
            }
            pool = null;
        }
        logger.info("RedisRegistry destroyed");
    }

    @Override
    public void register(String service, ServiceInstance instance) {
        checkStarted();
        String key = buildRegistryKey(service);
        try (Jedis jedis = pool.getResource()) {
            jedis.sadd(key, instance.getAddress());
            logger.info("Registered service '{}' instance '{}' to Redis", service, instance.getAddress());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to register instance to Redis: " + key, e);
        }
    }

    @Override
    public void unregister(String service, ServiceInstance instance) {
        if (pool == null || pool.isClosed()) {
            return;
        }
        String key = buildRegistryKey(service);
        try (Jedis jedis = pool.getResource()) {
            jedis.srem(key, instance.getAddress());
            logger.info("Unregistered service '{}' instance '{}' from Redis", service, instance.getAddress());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to unregister instance from Redis: " + key, e);
        }
    }

    @Override
    public void subscribe(String service, ServiceListener listener) {
        checkStarted();
        if (service == null || listener == null) {
            return;
        }

        ServicePollingTask task = tasks.get(service);
        if (task == null) {
            synchronized (tasks) {
                task = tasks.get(service);
                if (task == null) {
                    task = new ServicePollingTask(service, buildRegistryKey(service));
                    tasks.put(service, task);
                }
            }
        }

        synchronized (task) {
            task.listeners.add(listener);

            // 首次订阅时立即拉取一次并推快照
            Set<String> current = fetchCurrentMembers(task.redisKey);
            task.lastSnapshot = new HashSet<>(current);
            pushSnapshot(service, current, listener);

            // 启动定时轮询任务（若尚未启动）
            if (task.future == null || task.future.isCancelled()) {
                final ServicePollingTask finalTask = task;
                task.future = scheduler.scheduleWithFixedDelay(new Runnable() {
                    @Override
                    public void run() {
                        pollAndNotify(finalTask);
                    }
                }, pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);
            }
        }
    }

    @Override
    public void unsubscribe(String service, ServiceListener listener) {
        if (service == null || listener == null) {
            return;
        }
        ServicePollingTask task = tasks.get(service);
        if (task != null) {
            synchronized (task) {
                task.listeners.remove(listener);
                if (task.listeners.isEmpty()) {
                    if (task.future != null) {
                        task.future.cancel(false);
                        task.future = null;
                    }
                    tasks.remove(service);
                }
            }
        }
    }

    private void checkStarted() {
        if (pool == null || pool.isClosed()) {
            throw new IllegalStateException("RedisRegistry has not been initialized or already destroyed");
        }
    }

    private String buildRegistryKey(String service) {
        return keyPrefix + ":registry:" + service;
    }

    private Set<String> fetchCurrentMembers(String key) {
        try (Jedis jedis = pool.getResource()) {
            Set<String> members = jedis.smembers(key);
            return members != null ? members : Collections.<String>emptySet();
        } catch (Throwable t) {
            logger.warn("Failed to fetch smembers from Redis for key '{}': {}", key, t.getMessage());
            return Collections.emptySet();
        }
    }

    private void pollAndNotify(ServicePollingTask task) {
        synchronized (task) {
            if (task.listeners.isEmpty() || destroyed) {
                return;
            }
            Set<String> current = fetchCurrentMembers(task.redisKey);
            if (!current.equals(task.lastSnapshot)) {
                task.lastSnapshot = new HashSet<>(current);
                List<ServiceInstance> instances = new ArrayList<>(current.size());
                for (String addr : current) {
                    instances.add(new ServiceInstance(addr));
                }
                List<ServiceInstance> snapshot = Collections.unmodifiableList(instances);
                for (ServiceListener l : task.listeners) {
                    try {
                        l.onChange(snapshot);
                    } catch (Throwable t) {
                        logger.warn("Error notifying listener for service '{}': {}", task.service, t.getMessage(), t);
                    }
                }
            }
        }
    }

    private void pushSnapshot(String service, Set<String> addresses, ServiceListener listener) {
        List<ServiceInstance> instances = new ArrayList<>(addresses.size());
        for (String addr : addresses) {
            instances.add(new ServiceInstance(addr));
        }
        List<ServiceInstance> snapshot = Collections.unmodifiableList(instances);
        try {
            listener.onChange(snapshot);
        } catch (Throwable t) {
            logger.warn("Error pushing initial snapshot to listener for service '{}': {}", service, t.getMessage(), t);
        }
    }

    private static class ServicePollingTask {
        final String service;
        final String redisKey;
        final CopyOnWriteArrayList<ServiceListener> listeners = new CopyOnWriteArrayList<>();
        Set<String> lastSnapshot = Collections.emptySet();
        ScheduledFuture<?> future;

        ServicePollingTask(String service, String redisKey) {
            this.service = service;
            this.redisKey = redisKey;
        }
    }
}
