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

    /**
     * 登记序列化器。typeId 是线上协议的一部分，重复登记意味着帧无法确定反序列化器——
     * 与 SPI「重名大声失败」同一原则，这里不静默覆盖。
     */
    public SerializerRegistry register(Serializer serializer) {
        Serializer existing = serializers.putIfAbsent(serializer.typeId(), serializer);
        if (existing != null) {
            throw new IllegalStateException("duplicate serializer typeId " + serializer.typeId()
                    + ": " + existing.getClass().getName() + " vs "
                    + serializer.getClass().getName());
        }
        return this;
    }

    public Serializer find(byte typeId) {
        return serializers.get(typeId);
    }
}

