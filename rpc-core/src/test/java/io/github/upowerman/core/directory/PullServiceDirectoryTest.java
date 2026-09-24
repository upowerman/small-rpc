package io.github.upowerman.core.directory;

import io.github.upowerman.core.registry.BaseServiceRegistry;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PullServiceDirectoryTest {

    private static class StubRegistry implements BaseServiceRegistry {
        private final TreeSet<String> addresses;

        StubRegistry(String... addrs) {
            this.addresses = new TreeSet<String>();
            Collections.addAll(this.addresses, addrs);
        }

        @Override
        public void start(Map<String, String> param) {
        }

        @Override
        public void stop() {
            addresses.clear();
        }

        @Override
        public boolean registry(Set<String> keys, String value) {
            return false;
        }

        @Override
        public boolean remove(Set<String> keys, String value) {
            return false;
        }

        @Override
        public Map<String, TreeSet<String>> discovery(Set<String> keys) {
            return null;
        }

        @Override
        public TreeSet<String> discovery(String key) {
            return addresses;
        }
    }

    @Test
    public void listsInstancesFromRegistry() {
        BaseServiceRegistry registry = new StubRegistry("127.0.0.1:7080");

        PullServiceDirectory directory = new PullServiceDirectory(registry, null);
        List<ServiceInstance> instances = directory.list("com.test.EchoService");

        assertEquals(1, instances.size());
        assertEquals("127.0.0.1:7080", instances.get(0).getAddress());
        registry.stop();
    }

    @Test
    public void emptyWhenRegistryHasNoAddress() {
        BaseServiceRegistry registry = new StubRegistry();

        PullServiceDirectory directory = new PullServiceDirectory(registry, "1.0");
        assertTrue(directory.list("com.test.EchoService").isEmpty());
        registry.stop();
    }
}