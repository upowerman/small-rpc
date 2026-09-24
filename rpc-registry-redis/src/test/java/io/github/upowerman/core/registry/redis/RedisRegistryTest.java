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
        for (int i = 0; i < n; i++) {
            if (threads[i] != null && threads[i].getName().contains("small-rpc-redis-poll") && threads[i].isAlive()) {
                foundPollThread = true;
                break;
            }
        }
        Assert.assertFalse("destroy 后不应留存活跃的调度轮询线程", foundPollThread);
    }
}
