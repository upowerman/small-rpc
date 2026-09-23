package io.github.upowerman.core.directory;

import io.github.upowerman.registry.impl.LocalServiceRegistry;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PullServiceDirectoryTest {

    @Test
    public void listsInstancesFromRegistry() {
        LocalServiceRegistry registry = new LocalServiceRegistry();
        Map<String, String> param = new HashMap<>();
        param.put(LocalServiceRegistry.DIRECT_ADDRESS, "127.0.0.1:7080");
        registry.start(param);

        PullServiceDirectory directory = new PullServiceDirectory(registry, null);
        List<ServiceInstance> instances = directory.list("com.test.EchoService");

        assertEquals(1, instances.size());
        assertEquals("127.0.0.1:7080", instances.get(0).getAddress());
        registry.stop();
    }

    @Test
    public void emptyWhenRegistryHasNoAddress() {
        LocalServiceRegistry registry = new LocalServiceRegistry();
        registry.start(new HashMap<String, String>());

        PullServiceDirectory directory = new PullServiceDirectory(registry, "1.0");
        assertTrue(directory.list("com.test.EchoService").isEmpty());
        registry.stop();
    }
}