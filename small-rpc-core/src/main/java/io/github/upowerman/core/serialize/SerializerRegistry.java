package io.github.upowerman.core.serialize;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * codec(typeId) → Serializer 注册表：响应帧沿用请求帧的 codec，
 * 服务端据此查找反序列化器——多序列化共存的前提。
 */
public class SerializerRegistry {

    private final Map<Byte, Serializer> serializers = new ConcurrentHashMap<Byte, Serializer>();

    public SerializerRegistry register(Serializer serializer) {
        serializers.put(serializer.typeId(), serializer);
        return this;
    }

    public Serializer find(byte typeId) {
        return serializers.get(typeId);
    }
}
