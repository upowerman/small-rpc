package io.github.upowerman.core.serialize;

import org.junit.Test;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class SerializerRegistryTest {

    private static Serializer stub(final byte typeId, final String name) {
        return new Serializer() {
            @Override
            public byte typeId() {
                return typeId;
            }

            @Override
            public byte[] serialize(Object obj) {
                return name.getBytes();
            }

            @Override
            public Object deserialize(byte[] bytes, Class<?> clazz) {
                return name;
            }
        };
    }

    @Test
    public void findsRegisteredSerializerByTypeId() {
        Serializer hessian = new LegacyHessianSerializer();
        Serializer other = stub((byte) 7, "other");
        SerializerRegistry registry = new SerializerRegistry().register(hessian).register(other);
        assertSame(hessian, registry.find(LegacyHessianSerializer.TYPE_ID));
        assertSame(other, registry.find((byte) 7));
    }

    @Test
    public void unknownTypeIdReturnsNull() {
        SerializerRegistry registry = new SerializerRegistry().register(new LegacyHessianSerializer());
        assertNull(registry.find((byte) 99));
    }
}
