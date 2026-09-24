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
        Serializer s1 = stub((byte) 1, "s1");
        Serializer s2 = stub((byte) 7, "s2");
        SerializerRegistry registry = new SerializerRegistry().register(s1).register(s2);
        assertSame(s1, registry.find((byte) 1));
        assertSame(s2, registry.find((byte) 7));
    }

    @Test
    public void unknownTypeIdReturnsNull() {
        SerializerRegistry registry = new SerializerRegistry().register(stub((byte) 1, "s1"));
        assertNull(registry.find((byte) 99));
    }
}
