package io.github.upowerman.core.serialize;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/** fromSpi：生产登记的序列化实现全部装配进注册表（当前 = hessian/typeId 1）。 */
public class SerializerRegistrySpiTest {

    @Test
    public void fromSpiRegistersAllSpiSerializers() {
        SerializerRegistry registry = SerializerRegistry.fromSpi();
        Serializer hessian = registry.find((byte) 1);
        assertNotNull(hessian);
        assertEquals(1, hessian.typeId());
    }

    @Test
    public void fromSpiRoundTrips() {
        SerializerRegistry registry = SerializerRegistry.fromSpi();
        Serializer serializer = registry.find((byte) 1);
        String original = "spi-hello";
        byte[] bytes = serializer.serialize(original);
        assertEquals(original, serializer.deserialize(bytes, String.class));
    }
}
