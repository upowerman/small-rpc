package io.github.upowerman.core.serialize;

import org.junit.Test;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SerializerRegistryTest {

    /** 两个 typeId 相同、实现类不同的序列化器（重复登记用例） */
    public static class TypeIdOneStub implements Serializer {
        @Override
        public byte typeId() {
            return 1;
        }

        @Override
        public byte[] serialize(Object obj) {
            return new byte[0];
        }

        @Override
        public Object deserialize(byte[] bytes, Class<?> clazz) {
            return null;
        }
    }

    public static class TypeIdOneOtherStub implements Serializer {
        @Override
        public byte typeId() {
            return 1;
        }

        @Override
        public byte[] serialize(Object obj) {
            return new byte[0];
        }

        @Override
        public Object deserialize(byte[] bytes, Class<?> clazz) {
            return null;
        }
    }

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

    @Test
    public void duplicateTypeIdFailsLoudly() {
        SerializerRegistry registry = new SerializerRegistry().register(new TypeIdOneStub());
        try {
            registry.register(new TypeIdOneOtherStub());
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("1"));
            assertTrue(e.getMessage(), e.getMessage().contains(TypeIdOneStub.class.getName()));
            assertTrue(e.getMessage(), e.getMessage().contains(TypeIdOneOtherStub.class.getName()));
        }
        // 冲突登记不改变已有映射
        assertTrue(registry.find((byte) 1) instanceof TypeIdOneStub);
    }
}
