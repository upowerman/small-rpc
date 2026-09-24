package io.github.upowerman.core.registry.zookeeper;

import io.github.upowerman.core.directory.ServiceInstance;
import io.github.upowerman.core.registry.ServiceListener;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.RetryOneTime;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ZookeeperRegistryTest {

    private String namespace;
    private ZookeeperRegistry registry;

    @Before
    public void assumeZkAvailable() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 2181), 500);
        } catch (IOException e) {
            Assume.assumeTrue("本机 ZK 不可用，跳过集成测试: " + e.getMessage(), false);
        }
        namespace = "small-rpc-test-" + UUID.randomUUID().toString().substring(0, 8);
        registry = new ZookeeperRegistry();
    }

    @After
    public void tearDown() {
        if (registry != null) {
            registry.destroy();
        }
        // 清理当前测试的 namespace 根节点
        try {
            CuratorFramework client = CuratorFrameworkFactory.builder()
                    .connectString("127.0.0.1:2181")
                    .retryPolicy(new RetryOneTime(500))
                    .build();
            client.start();
            try {
                if (client.checkExists().forPath("/" + namespace) != null) {
                    client.delete().deletingChildrenIfNeeded().forPath("/" + namespace);
                }
            } finally {
                client.close();
            }
        } catch (Exception ignored) {
        }
    }

    @Test
    public void testRegisterAndSubscribeAndUnregisterLifecycle() throws Exception {
        Map<String, String> param = new HashMap<String, String>();
        param.put("zk.connect", "127.0.0.1:2181");
        param.put("zk.namespace", namespace);
        param.put("zk.session-timeout-ms", "5000");
        param.put("zk.connection-timeout-ms", "2000");
        registry.init(param);

        String service = "io.github.upowerman.HelloService";
        ServiceInstance inst1 = new ServiceInstance("127.0.0.1:8080");
        ServiceInstance inst2 = new ServiceInstance("127.0.0.1:8081");

        // 1. 先注册 inst1
        registry.register(service, inst1);

        final List<List<ServiceInstance>> snapshots = new CopyOnWriteArrayList<List<ServiceInstance>>();
        final CountDownLatch latchInitial = new CountDownLatch(1);
        final CountDownLatch latchSecond = new CountDownLatch(2);
        final CountDownLatch latchUnregister = new CountDownLatch(3);

        registry.subscribe(service, new ServiceListener() {
            @Override
            public void onChange(List<ServiceInstance> instances) {
                snapshots.add(instances);
                latchInitial.countDown();
                latchSecond.countDown();
                latchUnregister.countDown();
            }
        });

        // 初始订阅应立即收到全量快照，包含 inst1
        Assert.assertTrue("未在超时内收到初始快照", latchInitial.await(5, TimeUnit.SECONDS));
        List<ServiceInstance> snapshot1 = snapshots.get(0);
        Assert.assertEquals(1, snapshot1.size());
        Assert.assertEquals("127.0.0.1:8080", snapshot1.get(0).getAddress());

        // 2. 注册第二实例 inst2
        registry.register(service, inst2);
        Assert.assertTrue("未在超时内收到第二实例更新", latchSecond.await(5, TimeUnit.SECONDS));
        List<ServiceInstance> snapshot2 = snapshots.get(snapshots.size() - 1);
        Assert.assertEquals(2, snapshot2.size());
        List<String> addresses = new ArrayList<String>();
        for (ServiceInstance si : snapshot2) {
            addresses.add(si.getAddress());
        }
        Assert.assertTrue(addresses.contains("127.0.0.1:8080"));
        Assert.assertTrue(addresses.contains("127.0.0.1:8081"));

        // 3. 注销 inst1
        registry.unregister(service, inst1);
        Assert.assertTrue("未在超时内收到注销更新", latchUnregister.await(5, TimeUnit.SECONDS));
        List<ServiceInstance> snapshot3 = snapshots.get(snapshots.size() - 1);
        Assert.assertEquals(1, snapshot3.size());
        Assert.assertEquals("127.0.0.1:8081", snapshot3.get(0).getAddress());

        // 4. destroy 幂等
        registry.destroy();
        registry.destroy();
    }
}
