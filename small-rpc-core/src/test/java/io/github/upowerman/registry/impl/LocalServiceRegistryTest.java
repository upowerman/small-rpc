package io.github.upowerman.registry.impl;

import org.junit.Assert;
import org.junit.Test;

public class LocalServiceRegistryTest {

    @Test
    public void shouldHandleNullParamWhenStart() {
        LocalServiceRegistry registry = new LocalServiceRegistry();
        registry.start(null);

        Assert.assertNotNull(registry.discovery("any-service"));
        Assert.assertTrue(registry.discovery("any-service").isEmpty());
    }
}
