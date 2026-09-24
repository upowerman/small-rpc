package io.github.upowerman.spring;

import io.github.upowerman.registry.impl.LocalServiceRegistry;

import java.util.HashMap;
import java.util.Map;

public class LocalServiceRegistryForTest extends LocalServiceRegistry {
    public LocalServiceRegistryForTest() {
        Map<String, String> param = new HashMap<String, String>();
        param.put(LocalServiceRegistry.DIRECT_ADDRESS, "localhost:0");
        start(param);
    }
}
