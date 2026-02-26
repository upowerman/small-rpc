package io.github.upowerman.serialize;

import io.github.upowerman.exception.RpcException;

/**
 * 序列化枚举类
 *
 * @author gaoyunfeng
 */
public enum SerializeEnum {

    /**
     * hessian序列化方式
     */
    HESSIAN(HessianSerializer.class);

    private final Class<? extends BaseSerializer> serializerClass;

    private SerializeEnum(Class<? extends BaseSerializer> serializerClass) {
        this.serializerClass = serializerClass;
    }

    public BaseSerializer getSerializer() {
        try {
            return serializerClass.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RpcException(e);
        }
    }

    public static SerializeEnum match(String name, SerializeEnum defaultSerializer) {
        for (SerializeEnum item : SerializeEnum.values()) {
            if (item.name().equals(name)) {
                return item;
            }
        }
        return defaultSerializer;
    }
}
