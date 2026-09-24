package io.github.upowerman.spring;

import io.github.upowerman.core.directory.ServiceInstance;
import io.github.upowerman.core.registry.Registry;
import io.github.upowerman.core.registry.ServiceListener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public class LocalServiceRegistryForTest implements Registry {

    private final TreeSet<String> directAddress;

    public LocalServiceRegistryForTest() {
        this("localhost:0");
    }

    public LocalServiceRegistryForTest(String... addresses) {
        this.directAddress = new TreeSet<String>(Arrays.asList(addresses));
    }

    @Override
    public void init(Map<String, String> param) {
    }

    @Override
    public void destroy() {
        directAddress.clear();
    }

    @Override
    public void register(String service, ServiceInstance instance) {
        if (instance != null && instance.getAddress() != null) {
            directAddress.add(instance.getAddress());
        }
    }

    @Override
    public void unregister(String service, ServiceInstance instance) {
        if (instance != null && instance.getAddress() != null) {
            directAddress.remove(instance.getAddress());
        }
    }

    @Override
    public void subscribe(String service, ServiceListener listener) {
        List<ServiceInstance> instances = new ArrayList<ServiceInstance>();
        for (String addr : directAddress) {
            instances.add(new ServiceInstance(addr));
        }
        listener.onChange(instances);
    }

    @Override
    public void unsubscribe(String service, ServiceListener listener) {
    }
}
