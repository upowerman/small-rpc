package io.github.upowerman.core.serialize;

import org.junit.Test;

import java.io.Serializable;

import static org.junit.Assert.assertEquals;

public class HessianSerializerTest {

    public static class Payload implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String msg;

        public Payload(String msg) {
            this.msg = msg;
        }

        public String getMsg() {
            return msg;
        }
    }

    @Test
    public void roundTrip() {
        HessianSerializer serializer = new HessianSerializer();
        byte[] bytes = serializer.serialize(new Payload("hello"));
        Payload decoded = (Payload) serializer.deserialize(bytes, Payload.class);
        assertEquals("hello", decoded.getMsg());
    }

    @Test
    public void typeIdIsOne() {
        assertEquals(1, new HessianSerializer().typeId());
    }
}