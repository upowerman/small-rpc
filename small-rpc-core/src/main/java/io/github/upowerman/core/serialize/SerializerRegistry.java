package io.github.upowerman.core.serialize;

import io.github.upowerman.core.spi.SpiLoader;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * codec(typeId) → Serializer 注册表：响应帧沿用请求帧的 codec，
 * 服务端据此查找反序列化器——多序列化共存的前提。
 */
public class SerializerRegistry {

    private final Map<Byte, Serializer> serializers = new ConcurrentHashMap<Byte, Serializer>();

    /** 装配所有经 SPI 登记的序列化实现；rpc-core 不认识任何具体序列化器 */
    public static SerializerRegistry fromSpi() {
        SerializerRegistry registry = new SerializerRegistry();
        SpiLoader<Serializer> loader = SpiLoader.of(Serializer.class);
        for (String name : loader.getSupportedExtensions()) {
            registry.register(loader.getExtension(name));
        }
        return registry;
    }

    public SerializerRegistry register(Serializer serializer) {
        serializers.put(serializer.typeId(), serializer);
        return this;
    }

    public Serializer find(byte typeId) {
        return serializers.get(typeId);
    }
}

