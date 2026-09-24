package io.github.upowerman.core.serialize;

import io.github.upowerman.core.spi.Spi;

/**
 * 2.0 序列化 SPI。rpc-core 只认识这个接口；
 * P1 协议化后 typeId 填进帧的 codec 字段，多序列化实现共存。
 */
@Spi("hessian")
public interface Serializer {

    /** 序列化算法 ID，对应协议帧 codec 字段 */
    byte typeId();

    byte[] serialize(Object obj);

    Object deserialize(byte[] bytes, Class<?> clazz);
}