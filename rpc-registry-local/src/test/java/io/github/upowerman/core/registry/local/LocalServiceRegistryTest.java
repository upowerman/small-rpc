package io.github.upowerman.core.registry.local;

import io.github.upowerman.core.directory.ServiceInstance;
import io.github.upowerman.core.registry.ServiceListener;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LocalServiceRegistry 单元测试。
 */
public class LocalServiceRegistryTest {

    private LocalServiceRegistry registry;

    @Before
    public void setUp() {
        registry = new LocalServiceRegistry();
    }

    /**
     * 1. init 读 DIRECT_ADDRESS，非空时任意 service 的订阅快照都包含它（样例兼容语义）
     */
    @Test
    public void testInitWithDirectAddressProvidesAddressToAnyService() {
        Map<String, String> param = new HashMap<String, String>();
        param.put(LocalServiceRegistry.DIRECT_ADDRESS, "127.0.0.1:7081");
        registry.init(param);

        final List<List<ServiceInstance>> received = new ArrayList<List<ServiceInstance>>();
        registry.subscribe("io.github.upowerman.AnyService", new ServiceListener() {
            @Override
            public void onChange(List<ServiceInstance> instances) {
                received.add(instances);
            }
        });

        Assert.assertEquals(1, received.size());
        List<ServiceInstance> snapshot = received.get(0);
        Assert.assertEquals(1, snapshot.size());
        Assert.assertEquals("127.0.0.1:7081", snapshot.get(0).getAddress());
    }

    /**
     * 2. register 后 subscribe 立即收到全量实例
     */
    @Test
    public void testRegisterThenSubscribeReceivesFullSnapshotImmediately() {
        registry.init(Collections.<String, String>emptyMap());
        String service = "io.github.upowerman.DemoService";

        registry.register(service, new ServiceInstance("127.0.0.1:8080"));
        registry.register(service, new ServiceInstance("127.0.0.1:8081"));

        final List<List<ServiceInstance>> received = new ArrayList<List<ServiceInstance>>();
        registry.subscribe(service, new ServiceListener() {
            @Override
            public void onChange(List<ServiceInstance> instances) {
                received.add(instances);
            }
        });

        Assert.assertEquals(1, received.size());
        List<ServiceInstance> snapshot = received.get(0);
        Assert.assertEquals(2, snapshot.size());
        List<String> addresses = new ArrayList<String>();
        for (ServiceInstance inst : snapshot) {
            addresses.add(inst.getAddress());
        }
        Assert.assertTrue(addresses.contains("127.0.0.1:8080"));
        Assert.assertTrue(addresses.contains("127.0.0.1:8081"));
    }

    /**
     * 3. register / unregister 触发已订阅 listener 的推送
     */
    @Test
    public void testRegisterAndUnregisterTriggerPushToSubscribedListener() {
        registry.init(Collections.<String, String>emptyMap());
        String service = "io.github.upowerman.DemoService";

        final List<List<ServiceInstance>> received = new ArrayList<List<ServiceInstance>>();
        registry.subscribe(service, new ServiceListener() {
            @Override
            public void onChange(List<ServiceInstance> instances) {
                received.add(instances);
            }
        });

        // 初始订阅收到空快照
        Assert.assertEquals(1, received.size());
        Assert.assertTrue(received.get(0).isEmpty());

        // register 触发推送
        ServiceInstance inst1 = new ServiceInstance("127.0.0.1:8080");
        registry.register(service, inst1);
        Assert.assertEquals(2, received.size());
        Assert.assertEquals(1, received.get(1).size());
        Assert.assertEquals("127.0.0.1:8080", received.get(1).get(0).getAddress());

        // unregister 触发推送
        registry.unregister(service, inst1);
        Assert.assertEquals(3, received.size());
        Assert.assertTrue(received.get(2).isEmpty());
    }

    /**
     * 4. DIRECT_ADDRESS 非空时，即使 register 了其他实例，快照依然包含 DIRECT_ADDRESS
     */
    @Test
    public void testDirectAddressCoexistsWithRegisteredInstances() {
        Map<String, String> param = new HashMap<String, String>();
        param.put(LocalServiceRegistry.DIRECT_ADDRESS, "127.0.0.1:7081");
        registry.init(param);

        String service = "io.github.upowerman.DemoService";
        final List<List<ServiceInstance>> received = new ArrayList<List<ServiceInstance>>();
        registry.subscribe(service, new ServiceListener() {
            @Override
            public void onChange(List<ServiceInstance> instances) {
                received.add(instances);
            }
        });

        registry.register(service, new ServiceInstance("127.0.0.1:8080"));
        List<ServiceInstance> latest = received.get(received.size() - 1);
        Assert.assertEquals(2, latest.size());
        List<String> addresses = new ArrayList<String>();
        for (ServiceInstance inst : latest) {
            addresses.add(inst.getAddress());
        }
        Assert.assertTrue(addresses.contains("127.0.0.1:7081"));
        Assert.assertTrue(addresses.contains("127.0.0.1:8080"));
    }

    /**
     * 5. unsubscribe 后不再收到推送
     */
    @Test
    public void testUnsubscribeStopsReceivingPush() {
        registry.init(Collections.<String, String>emptyMap());
        String service = "io.github.upowerman.DemoService";

        final List<List<ServiceInstance>> received = new ArrayList<List<ServiceInstance>>();
        ServiceListener listener = new ServiceListener() {
            @Override
            public void onChange(List<ServiceInstance> instances) {
                received.add(instances);
            }
        };

        registry.subscribe(service, listener);
        Assert.assertEquals(1, received.size());

        registry.unsubscribe(service, listener);

        // 后续变更不应推给已取消订阅的 listener
        registry.register(service, new ServiceInstance("127.0.0.1:8080"));
        Assert.assertEquals(1, received.size());
    }

    /**
     * 6. destroy 清空且幂等（二次调用不抛）
     */
    @Test
    public void testDestroyClearsStateAndIsIdempotent() {
        Map<String, String> param = new HashMap<String, String>();
        param.put(LocalServiceRegistry.DIRECT_ADDRESS, "127.0.0.1:7081");
        registry.init(param);

        String service = "io.github.upowerman.DemoService";
        registry.register(service, new ServiceInstance("127.0.0.1:8080"));

        registry.destroy();
        registry.destroy(); // 幂等验证，不抛异常

        final List<List<ServiceInstance>> received = new ArrayList<List<ServiceInstance>>();
        registry.subscribe(service, new ServiceListener() {
            @Override
            public void onChange(List<ServiceInstance> instances) {
                received.add(instances);
            }
        });
        Assert.assertEquals(1, received.size());
        Assert.assertTrue(received.get(0).isEmpty());
    }
}
