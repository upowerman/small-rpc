package io.github.upowerman.core.registry.redis;

import io.github.upowerman.core.directory.ServiceInstance;
import io.github.upowerman.core.registry.InstancePayloads;
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
 *   <li>存活判定：Provider 注册时写入并周期性续期「存活租约键」（{@code {prefix}:registry-heartbeat:{service}:{address}}），
 *       Consumer 轮询时只把租约未过期的成员视作存活——优雅退出走 SREM 摘除，被动退出（kill -9/OOM）靠租约 TTL 过期剔除，不永久占坑；</li>
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
    private static final long DEFAULT_HEARTBEAT_MS = 1000;
    /** heartbeat 键 TTL：provider 被 kill -9/OOM 等被动退出时，超过此值未续期即被 consumer 视为 stale 剔除 */
    private static final long DEFAULT_HEARTBEAT_STALE_MS = 5000;
    private static final String HEARTBEAT_SEGMENT = ":registry-heartbeat:";

    private volatile JedisPool pool;
    private volatile ScheduledExecutorService scheduler;
    private volatile String keyPrefix = DEFAULT_KEY_PREFIX;
    private volatile long pollIntervalMs = DEFAULT_POLL_INTERVAL_MS;
    private volatile long heartbeatMs = DEFAULT_HEARTBEAT_MS;
    private volatile long heartbeatStaleMs = DEFAULT_HEARTBEAT_STALE_MS;
    /** 需周期续期的 provider heartbeat 键（provider 进程注册后登记，SIGKILL 后停止续期即过期） */
    private final Set<String> heartbeatKeys = ConcurrentHashMap.newKeySet();
    private volatile ScheduledExecutorService heartbeatScheduler;
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

        if (param != null && param.containsKey("redis.heartbeat-ms")) {
            try {
                this.heartbeatMs = Long.parseLong(param.get("redis.heartbeat-ms"));
            } catch (NumberFormatException ignored) {
            }
        } else {
            this.heartbeatMs = DEFAULT_HEARTBEAT_MS;
        }
        if (param != null && param.containsKey("redis.heartbeat-stale-ms")) {
            try {
                this.heartbeatStaleMs = Long.parseLong(param.get("redis.heartbeat-stale-ms"));
            } catch (NumberFormatException ignored) {
            }
        } else {
            this.heartbeatStaleMs = DEFAULT_HEARTBEAT_STALE_MS;
        }
        // N-3：stale 窗口必须严格大于心跳周期——TTL == 续期周期时，调度抖动可让键在续期前一刻过期，实例周期性闪断
        if (heartbeatStaleMs <= heartbeatMs) {
            throw new IllegalArgumentException("redis.heartbeat-stale-ms(" + heartbeatStaleMs
                    + ") must be strictly greater than redis.heartbeat-ms(" + heartbeatMs + ")");
        }
        // N-1：psetex 为 long 毫秒参数无 int 截断；仍防呆一个上界（租约窗口超过它已无运维意义）
        if (heartbeatStaleMs > 30L * 24 * 60 * 60 * 1000) {
            throw new IllegalArgumentException("redis.heartbeat-stale-ms(" + heartbeatStaleMs
                    + ") 过大（上限 30 天）");
        }

        JedisPoolConfig poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(20);
        poolConfig.setMaxIdle(10);
        poolConfig.setMinIdle(1);
        poolConfig.setTestOnBorrow(false);

        this.pool = new JedisPool(poolConfig, host, port, timeout, password, database);
        // M-2 fail-fast：连接不可达在启动期大声失败，而非首次 register/subscribe 才暴露（行为对齐 init 的 fail-fast 语义）
        try (Jedis jedis = pool.getResource()) {
            jedis.ping();
        } catch (Exception e) {
            pool.close();
            pool = null;
            throw new IllegalStateException("Redis 连接不可达 host='" + host + "', port=" + port
                    + "（init fail-fast）", e);
        }
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
        heartbeatKeys.clear();

        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                scheduler.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            scheduler = null;
        }
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
            try {
                heartbeatScheduler.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            heartbeatScheduler = null;
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
        String address = instance.getAddress();
        try (Jedis jedis = pool.getResource()) {
            jedis.sadd(key, address);
            // 实例元数据（weight/startTime/warmup）写并行 Hash：Set 成员保持纯地址，
            // 心跳键与差量比对逻辑不动；消费端轮询时按地址合并
            jedis.hset(buildMetaKey(service), address, InstancePayloads.encode(instance));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to register instance to Redis: " + key, e);
        }
        // I-2:写 heartbeat 租约键并登记续期。provider 被动退出（kill -9/OOM）后租约键 TTL 过期，
        // consumer 侧据此剔除 stale 实例，避免僵尸成员永久占坑（优雅退出走 SREM）。
        String heartbeatKey = buildHeartbeatKey(service, address);
        touchHeartbeat(heartbeatKey);
        heartbeatKeys.add(heartbeatKey);
        ensureHeartbeatScheduler();
        logger.info("Registered service '{}' instance '{}' to Redis (heartbeat active)", service, address);
    }

    @Override
    public void unregister(String service, ServiceInstance instance) {
        if (pool == null || pool.isClosed()) {
            return;
        }
        String key = buildRegistryKey(service);
        String address = instance.getAddress();
        try (Jedis jedis = pool.getResource()) {
            jedis.srem(key, address);
            jedis.hdel(buildMetaKey(service), address);
            jedis.del(buildHeartbeatKey(service, address));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to unregister instance from Redis: " + key, e);
        }
        heartbeatKeys.remove(buildHeartbeatKey(service, address));
        logger.info("Unregistered service '{}' instance '{}' from Redis (heartbeat removed)", service, address);
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

            // 首次订阅时立即拉取一次、剔除 stale 后推快照
            List<ServiceInstance> alive = filterAlive(service, task.redisKey, fetchCurrentMembers(task.redisKey));
            Set<String> current = new HashSet<>();
            for (ServiceInstance si : alive) {
                current.add(si.getAddress());
            }
            task.lastSnapshot = current;
            pushSnapshot(service, alive, listener);

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

    /** 例：{prefix}:registry-meta:{service} —— address → 实例元数据 payload */
    private String buildMetaKey(String service) {
        return keyPrefix + ":registry-meta:" + service;
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
            List<ServiceInstance> alive = filterAlive(task.service, task.redisKey, fetchCurrentMembers(task.redisKey));
            Set<String> current = new HashSet<>();
            for (ServiceInstance si : alive) {
                current.add(si.getAddress());
            }
            if (!current.equals(task.lastSnapshot)) {
                task.lastSnapshot = new HashSet<>(current);
                List<ServiceInstance> snapshot = Collections.unmodifiableList(alive);
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

    private void pushSnapshot(String service, List<ServiceInstance> instances, ServiceListener listener) {
        List<ServiceInstance> snapshot = Collections.unmodifiableList(instances);
        try {
            listener.onChange(snapshot);
        } catch (Throwable t) {
            logger.warn("Error pushing initial snapshot to listener for service '{}': {}", service, t.getMessage(), t);
        }
    }

    /** 例：{prefix}:registry-heartbeat:{service}:{address} —— 单个实例的存活租约键 */
    private String buildHeartbeatKey(String service, String address) {
        return keyPrefix + HEARTBEAT_SEGMENT + service + ":" + address;
    }

    /** 写（续）一次 heartbeat 租约键，TTL = stale 窗口 */
    private void touchHeartbeat(String heartbeatKey) {
        if (pool == null || pool.isClosed()) {
            return;
        }
        try (Jedis jedis = pool.getResource()) {
            // 必须用 psetex（毫秒）：setex 的 TTL 单位是秒，把毫秒值传进去会把 5s 租约变成 5000s（约 83 分钟），
            // SIGKILL 后实例远晚于 README 承诺的时间才被摘除（N-1 的真实根因，比 int 截断更实质）
            jedis.psetex(heartbeatKey, heartbeatStaleMs, "1");
        } catch (Exception e) {
            logger.warn("Failed to renew heartbeat key '{}': {}", heartbeatKey, e.getMessage());
        }
    }

    /** provider 心跳续期任务：周期刷新所有已登记实例的租约键 */
    private void refreshHeartbeats() {
        if (destroyed || heartbeatKeys.isEmpty() || pool == null || pool.isClosed()) {
            return;
        }
        for (String key : heartbeatKeys) {
            touchHeartbeat(key);
        }
    }

    private void ensureHeartbeatScheduler() {
        if (heartbeatScheduler != null) {
            return;
        }
        synchronized (this) {
            if (heartbeatScheduler == null) {
                heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "small-rpc-redis-heartbeat-" + THREAD_COUNTER.getAndIncrement());
                        t.setDaemon(true);
                        return t;
                    }
                });
                heartbeatScheduler.scheduleWithFixedDelay(new Runnable() {
                    @Override
                    public void run() {
                        refreshHeartbeats();
                    }
                }, heartbeatMs, heartbeatMs, TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * I-2：从成员集筛出"存活"实例——存在 heartbeat 租约键（未过期）才算存活。
     * provider 正常运行时持续续期；被 kill -9/OOM 被动退出后键过期即被剔除，避免僵尸成员永久占坑。
     * EXISTS 异常时降级返回全量（不误伤存活实例，宁可暂容 stale 也不清空缓存——降级优先于精确）。
     * 实例元数据从并行 meta Hash 合并；Hash 读失败或字段缺失按等权旧格式处理。
     */
    private List<ServiceInstance> filterAlive(String service, String redisKey, Set<String> members) {
        List<String> alive = new ArrayList<>();
        if (members.isEmpty()) {
            return new ArrayList<>();
        }
        try (Jedis jedis = pool.getResource()) {
            for (String addr : members) {
                if (Boolean.TRUE.equals(jedis.exists(buildHeartbeatKey(service, addr)))) {
                    alive.add(addr);
                }
            }
        } catch (Throwable t) {
            logger.warn("Failed to check heartbeat for key '{}', falling back to full member list: {}",
                    redisKey, t.getMessage());
            alive.addAll(members);
        }
        Map<String, String> meta = fetchMeta(service);
        List<ServiceInstance> instances = new ArrayList<>(alive.size());
        for (String addr : alive) {
            instances.add(InstancePayloads.decode(addr, meta.get(addr)));
        }
        return instances;
    }

    /** 读实例元数据 Hash；失败返回空表（按旧格式等权处理，不阻断推送） */
    private Map<String, String> fetchMeta(String service) {
        try (Jedis jedis = pool.getResource()) {
            Map<String, String> meta = jedis.hgetAll(buildMetaKey(service));
            return meta != null ? meta : Collections.<String, String>emptyMap();
        } catch (Throwable t) {
            logger.warn("Failed to fetch instance meta for service '{}': {}", service, t.getMessage());
            return Collections.emptyMap();
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
