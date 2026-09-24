package io.github.upowerman.spring;

import io.github.upowerman.core.registry.BaseServiceRegistry;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class LocalServiceRegistryForTest implements BaseServiceRegistry {

    private final TreeSet<String> directAddress;

    public LocalServiceRegistryForTest() {
        this("localhost:0");
    }

    public LocalServiceRegistryForTest(String... addresses) {
        this.directAddress = new TreeSet<String>(Arrays.asList(addresses));
    }

    @Override
    public void start(Map<String, String> param) {
    }

    @Override
    public void stop() {
        directAddress.clear();
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
        Map<String, TreeSet<String>> result = new HashMap<String, TreeSet<String>>();
        for (String key : keys) {
            result.put(key, directAddress);
        }
        return result;
    }

    @Override
    public TreeSet<String> discovery(String key) {
        return directAddress;
    }
}
