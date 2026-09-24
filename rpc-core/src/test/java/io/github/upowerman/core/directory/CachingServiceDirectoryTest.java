package io.github.upowerman.core.directory;

import io.github.upowerman.core.registry.Registry;
import io.github.upowerman.core.registry.ServiceListener;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CachingServiceDirectory 单元测试。
 */
public class CachingServiceDirectoryTest {

    private MockRegistry mockRegistry;
    private CachingServiceDirectory directory;

    @Before
    public void setUp() {
        mockRegistry = new MockRegistry();
        directory = new CachingServiceDirectory(mockRegistry);
    }

    /**
     * 1. 未订阅 → list 返回空列表（不是 null）
     */
    @Test
    public void testUnsubscribedReturnsEmptyList() {
        List<ServiceInstance> instances = directory.list("io.github.upowerman.DemoService");
        Assert.assertNotNull(instances);
        Assert.assertTrue(instances.isEmpty());
    }

    /**
     * 2. subscribe 后 listener 收到推送 → list 返回该快照
     */
    @Test
    public void testSubscribeAndPushReturnsSnapshot() {
        String service = "io.github.upowerman.DemoService";
        directory.subscribe(service);

        ServiceInstance inst1 = new ServiceInstance("127.0.0.1:8080");
        ServiceInstance inst2 = new ServiceInstance("127.0.0.1:8081");
        mockRegistry.trigger(service, Arrays.asList(inst1, inst2));

        List<ServiceInstance> instances = directory.list(service);
        Assert.assertEquals(2, instances.size());
        Assert.assertEquals("127.0.0.1:8080", instances.get(0).getAddress());
        Assert.assertEquals("127.0.0.1:8081", instances.get(1).getAddress());
    }

    /**
     * 3. 降级：先推 A，再让 listener 回调抛异常（如传 null 导致 NPE 被 catch 并降级）→ list 仍返回 A
     */
    @Test
    public void testDegradeRetainsOldCacheWhenOnChangeFails() {
        String service = "io.github.upowerman.DemoService";
        directory.subscribe(service);

        ServiceInstance instA = new ServiceInstance("127.0.0.1:8080");
        mockRegistry.trigger(service, Collections.singletonList(instA));

        List<ServiceInstance> listBefore = directory.list(service);
        Assert.assertEquals(1, listBefore.size());
        Assert.assertEquals("127.0.0.1:8080", listBefore.get(0).getAddress());

        // 传 null 导致内部处理抛出异常，触发降级
        mockRegistry.trigger(service, null);

        // 验证缓存未被清空或污染，依然保留旧值 A
        List<ServiceInstance> listAfter = directory.list(service);
        Assert.assertEquals(1, listAfter.size());
        Assert.assertEquals("127.0.0.1:8080", listAfter.get(0).getAddress());
    }

    /**
     * 4. subscribe 幂等：同一 service 调两次 → Registry 桩只收到一次订阅
     */
    @Test
    public void testSubscribeIsIdempotent() {
        String service = "io.github.upowerman.DemoService";
        directory.subscribe(service);
        directory.subscribe(service);

        Assert.assertEquals(1, mockRegistry.subscribeCount);
    }

    /**
     * 5. Registry 的 subscribe 抛异常 → subscribe 调用本身不抛（记 warn），list 返回空
     */
    @Test
    public void testSubscribeExceptionDoesNotThrowAndReturnsEmpty() {
        String service = "io.github.upowerman.DemoService";
        mockRegistry.throwOnSubscribe = true;

        try {
            directory.subscribe(service);
        } catch (Exception e) {
            Assert.fail("subscribe 不应抛出异常: " + e.getMessage());
        }

        List<ServiceInstance> instances = directory.list(service);
        Assert.assertNotNull(instances);
        Assert.assertTrue(instances.isEmpty());
    }

    /**
     * 6. 推送空列表 → 缓存被清空（合法变更，与"降级"区分）
     */
    @Test
    public void testPushEmptyListClearsCache() {
        String service = "io.github.upowerman.DemoService";
        directory.subscribe(service);

        ServiceInstance instA = new ServiceInstance("127.0.0.1:8080");
        mockRegistry.trigger(service, Collections.singletonList(instA));
        Assert.assertEquals(1, directory.list(service).size());

        // 推送合法空列表
        mockRegistry.trigger(service, Collections.<ServiceInstance>emptyList());
        List<ServiceInstance> instances = directory.list(service);
        Assert.assertNotNull(instances);
        Assert.assertTrue(instances.isEmpty());
    }

    private static class MockRegistry implements Registry {
        Map<String, ServiceListener> listeners = new HashMap<>();
        int subscribeCount = 0;
        boolean throwOnSubscribe = false;

        @Override
        public void init(Map<String, String> param) {}

        @Override
        public void destroy() {}

        @Override
        public void register(String service, ServiceInstance instance) {}

        @Override
        public void unregister(String service, ServiceInstance instance) {}

        @Override
        public void subscribe(String service, ServiceListener listener) {
            if (throwOnSubscribe) {
                throw new RuntimeException("Registry connect failed");
            }
            subscribeCount++;
            listeners.put(service, listener);
        }

        @Override
        public void unsubscribe(String service, ServiceListener listener) {
            listeners.remove(service);
        }

        void trigger(String service, List<ServiceInstance> instances) {
            ServiceListener listener = listeners.get(service);
            if (listener != null) {
                listener.onChange(instances);
            }
        }
    }
}
