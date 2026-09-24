package io.github.upowerman.spring;

import io.github.upowerman.core.registry.BaseServiceRegistry;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public class LocalServiceRegistryForTest implements BaseServiceRegistry {

    private final TreeSet<String> directAddress = new TreeSet<String>(Collections.singletonList("localhost:0"));

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
