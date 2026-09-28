package io.github.upowerman.core.registry.redis;

import io.github.upowerman.core.directory.ServiceInstance;
import io.github.upowerman.core.registry.ServiceListener;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import redis.clients.jedis.Jedis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

public class RedisRegistryTest {

    private String keyPrefix;
    private RedisRegistry registry;

    @Before
    public void assumeRedisAvailable() {
        try (Jedis jedis = new Jedis("127.0.0.1", 6379, 1000)) {
            String ping = jedis.ping();
            if (!"PONG".equalsIgnoreCase(ping)) {
                Assume.assumeTrue("Redis ping returned: " + ping, false);
            }
        } catch (Exception e) {
            Assume.assumeTrue("本机 Redis 不可用，跳过集成测试: " + e.getMessage(), false);
        }
        keyPrefix = "small-rpc-test-" + UUID.randomUUID().toString().substring(0, 8);
        registry = new RedisRegistry();
    }

    @After
    public void tearDown() {
        if (registry != null) {
            registry.destroy();
        }
        try (Jedis jedis = new Jedis("127.0.0.1", 6379, 1000)) {
            Set<String> keys = jedis.keys(keyPrefix + "*");
            if (keys != null && !keys.isEmpty()) {
                jedis.del(keys.toArray(new String[0]));
            }
        } catch (Exception ignored) {
        }
    }

    @Test
    public void testLifecycleAndPollingPush() throws Exception {
        Map<String, String> param = new HashMap<String, String>();
        param.put("redis.host", "127.0.0.1");
        param.put("redis.port", "6379");
        param.put("redis.key-prefix", keyPrefix);
        param.put("redis.poll-interval-ms", "300"); // 快速轮询用于单测
        registry.init(param);

        String service = "io.github.upowerman.HelloService";
        ServiceInstance inst1 = new ServiceInstance("127.0.0.1:8080");
        registry.register(service, inst1);

        final List<List<ServiceInstance>> snapshots = new CopyOnWriteArrayList<List<ServiceInstance>>();
        ServiceListener listener = new ServiceListener() {
            @Override
            public void onChange(List<ServiceInstance> instances) {
                snapshots.add(instances);
            }
        };

        // 1. subscribe 立即收到 1 个
        registry.subscribe(service, listener);
        Assert.assertFalse("subscribe 应立即收到快照", snapshots.isEmpty());
        Assert.assertEquals(1, snapshots.get(0).size());
        Assert.assertEquals("127.0.0.1:8080", snapshots.get(0).get(0).getAddress());

        // 2. 注册第二实例 → 轮询周期内收到 2 个
        ServiceInstance inst2 = new ServiceInstance("127.0.0.1:8081");
        registry.register(service, inst2);

        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            List<ServiceInstance> cur = snapshots.get(snapshots.size() - 1);
            if (cur.size() == 2) {
                break;
            }
            Thread.sleep(100);
        }
        List<ServiceInstance> latest = snapshots.get(snapshots.size() - 1);
        Assert.assertEquals(2, latest.size());
        List<String> addrs = new ArrayList<String>();
        for (ServiceInstance si : latest) {
            addrs.add(si.getAddress());
        }
        Assert.assertTrue(addrs.contains("127.0.0.1:8080"));
        Assert.assertTrue(addrs.contains("127.0.0.1:8081"));

        // 3. unregister → 轮询周期内收到 1 个
        registry.unregister(service, inst1);
        deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            List<ServiceInstance> cur = snapshots.get(snapshots.size() - 1);
            if (cur.size() == 1 && "127.0.0.1:8081".equals(cur.get(0).getAddress())) {
                break;
            }
            Thread.sleep(100);
        }
        List<ServiceInstance> afterUnreg = snapshots.get(snapshots.size() - 1);
        Assert.assertEquals(1, afterUnreg.size());
        Assert.assertEquals("127.0.0.1:8081", afterUnreg.get(0).getAddress());

        // 4. unsubscribe 后不再推送
        registry.unsubscribe(service, listener);
        int countBefore = snapshots.size();
        registry.register(service, new ServiceInstance("127.0.0.1:8082"));
        Thread.sleep(800); // 超过 2 个轮询周期
        Assert.assertEquals("unsubscribe 后不应再收到推送", countBefore, snapshots.size());

        // 5. destroy 幂等且不留存活线程
        registry.destroy();
        registry.destroy();

        Thread[] threads = new Thread[Thread.activeCount() + 10];
        int n = Thread.enumerate(threads);
        boolean foundPollThread = false;
        boolean foundHeartbeatThread = false;
        for (int i = 0; i < n; i++) {
            if (threads[i] != null && threads[i].isAlive()) {
                String name = threads[i].getName();
                if (name.contains("small-rpc-redis-poll")) {
                    foundPollThread = true;
                }
                if (name.contains("small-rpc-redis-heartbeat")) {
                    foundHeartbeatThread = true;
                }
            }
        }
        Assert.assertFalse("destroy 后不应留存活跃的 poll 线程", foundPollThread);
        Assert.assertFalse("destroy 后不应留存活跃的 heartbeat 线程", foundHeartbeatThread);
    }

    /**
     * I-2 回归：provider 被 kill -9 等被动退出后，heartbeat 租约键过期即被 consumer 剔除（不永久失败）。
     * 手动模拟 provider（不经 register，避免测试进程启动心跳线程干扰）：两个实例均有租约 → 快照 2 个；
     * 删除租约键模拟 provider 心跳停 → 下一轮 poll 后快照变空。
     */
    @Test
    public void testStaleProviderExcludedAfterHeartbeatExpiry() throws Exception {
        Map<String, String> param = new HashMap<String, String>();
        param.put("redis.host", "127.0.0.1");
        param.put("redis.port", "6379");
        param.put("redis.key-prefix", keyPrefix);
        param.put("redis.poll-interval-ms", "200");
        registry.init(param);

        String service = "io.github.upowerman.HelloService";
        String addr1 = "127.0.0.1:8080";
        String addr2 = "127.0.0.1:8081";
        String regKey = keyPrefix + ":registry:" + service;
        String hb1 = keyPrefix + ":registry-heartbeat:" + service + ":" + addr1;
        String hb2 = keyPrefix + ":registry-heartbeat:" + service + ":" + addr2;

        // 手动模拟 provider：两个实例都写存活租约
        try (Jedis sim = new Jedis("127.0.0.1", 6379, 1000)) {
            sim.sadd(regKey, addr1, addr2);
            sim.setex(hb1, 60, "1");
            sim.setex(hb2, 60, "1");
        }

        final List<List<ServiceInstance>> snapshots = new CopyOnWriteArrayList<List<ServiceInstance>>();
        ServiceListener listener = new ServiceListener() {
            @Override
            public void onChange(List<ServiceInstance> instances) {
                snapshots.add(instances);
            }
        };
        registry.subscribe(service, listener);

        // 初始快照：两个实例都有存活租约 → 2 个
        Assert.assertTrue("subscribe 应立即收到快照", !snapshots.isEmpty());
        Assert.assertEquals(2, snapshots.get(0).size());

        // 模拟 provider 被 kill -9：删除所有租约键（provider 心跳线程已死，不会续期）
        try (Jedis sim = new Jedis("127.0.0.1", 6379, 1000)) {
            sim.del(hb1, hb2);
        }

        // 等下一轮 poll（200ms）发现全部 stale → 快照变空（不永久失败）
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            List<ServiceInstance> cur = snapshots.get(snapshots.size() - 1);
            if (cur.isEmpty()) {
                break;
            }
            Thread.sleep(100);
        }
        Assert.assertTrue("provider 被动退出后 stale 实例应被剔除（不永久占坑）",
                snapshots.get(snapshots.size() - 1).isEmpty());
    }

    /**
     * N-4 回归：Redis 连接不可达时 init 应 fail-fast（ping 探测），不静默吞掉、不留半初始化资源
     */
    @Test
    public void testInitFailsFastWhenRedisUnreachable() {
        RedisRegistry r = new RedisRegistry();
        Map<String, String> param = new HashMap<String, String>();
        param.put("redis.host", "127.0.0.1");
        param.put("redis.port", "1");
        try {
            r.init(param);
            Assert.fail("Redis 不可达时 init 应 fail-fast 抛 IllegalStateException");
        } catch (IllegalStateException expected) {
            // 预期：连接不可达启动期大声失败
        }
        r.destroy();
    }

    /**
     * N-4 回归：stale 窗口小于心跳周期时 init 应大声拒绝（否则 provider 自己续期前租约已过期，永远被判死）
     */
    @Test
    public void testInitRejectsStaleWindowSmallerThanHeartbeat() {
        RedisRegistry r = new RedisRegistry();
        Map<String, String> param = new HashMap<String, String>();
        param.put("redis.host", "127.0.0.1");
        param.put("redis.port", "6379");
        param.put("redis.heartbeat-ms", "1000");
        param.put("redis.heartbeat-stale-ms", "100");
        try {
            r.init(param);
            Assert.fail("stale 窗口 < 心跳周期时 init 应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 预期：非法配置启动期大声失败
        }
        r.destroy();

        // N-3 等号边界：stale == heartbeat 同样拒绝（调度抖动下键会先于续期过期，实例周期性闪断）
        RedisRegistry r2 = new RedisRegistry();
        Map<String, String> equalParam = new HashMap<String, String>();
        equalParam.put("redis.host", "127.0.0.1");
        equalParam.put("redis.port", "6379");
        equalParam.put("redis.heartbeat-ms", "1000");
        equalParam.put("redis.heartbeat-stale-ms", "1000");
        try {
            r2.init(equalParam);
            Assert.fail("stale == heartbeat 时 init 应抛 IllegalArgumentException（须严格大于）");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
        r2.destroy();
    }

    /**
     * N-1 配套：租约「自然 TTL 过期」路径——不手动 DEL，靠 TTL 到期后被剔除。
     * 钉住毫秒语义：stale-ms=300 必须在秒级内过期；若实现错用 setex（秒单位），300 秒内不会剔除，本测试失败。
     */
    @Test
    public void testHeartbeatTtlExpiresNaturally() throws Exception {
        Map<String, String> param = new HashMap<String, String>();
        param.put("redis.host", "127.0.0.1");
        param.put("redis.port", "6379");
        param.put("redis.key-prefix", keyPrefix);
        param.put("redis.poll-interval-ms", "200");
        param.put("redis.heartbeat-ms", "100"); // 须 < stale（N-3 严格大于校验）；测试不经 register，心跳任务空转不干扰
        param.put("redis.heartbeat-stale-ms", "300");
        registry.init(param);

        String service = "io.github.upowerman.HelloService";
        String addr1 = "127.0.0.1:8080";
        String regKey = keyPrefix + ":registry:" + service;
        String hb1 = keyPrefix + ":registry-heartbeat:" + service + ":" + addr1;

        // 模拟 provider：写 300ms 租约后立即死亡（不再续期）
        try (Jedis sim = new Jedis("127.0.0.1", 6379, 1000)) {
            sim.sadd(regKey, addr1);
            sim.psetex(hb1, 300, "1");
        }

        final List<List<ServiceInstance>> snapshots = new CopyOnWriteArrayList<List<ServiceInstance>>();
        ServiceListener listener = new ServiceListener() {
            @Override
            public void onChange(List<ServiceInstance> instances) {
                snapshots.add(instances);
            }
        };
        registry.subscribe(service, listener);
        Assert.assertTrue("subscribe 应立即收到快照", !snapshots.isEmpty());

        // 300ms 租约自然过期 + 一轮 poll（200ms）→ 快照变空
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            List<ServiceInstance> cur = snapshots.get(snapshots.size() - 1);
            if (cur.isEmpty()) {
                break;
            }
            Thread.sleep(100);
        }
        Assert.assertTrue("租约自然过期后 stale 实例应被剔除（毫秒 TTL 语义）",
                snapshots.get(snapshots.size() - 1).isEmpty());
    }
}
