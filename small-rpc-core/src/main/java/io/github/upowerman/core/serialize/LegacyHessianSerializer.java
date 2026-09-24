package io.github.upowerman.core.serialize;

/**
 * 适配 1.x HessianSerializer。P2 拆模块时本类搬入 rpc-transport-netty
 * 并直接基于 HessianInput/HessianOutput 重写，不再走 delegate。
 * <p>
 * 注意：委托的 1.x Hessian 反序列化忽略 {@code clazz} 参数，类型防线在调用方的强制转型
 * （见 ServerHandler/ResponseHandler）。P2 拆模块时本类基于 HessianInput/HessianOutput
 * 重写并搬入 rpc-transport-netty。
 */
public class LegacyHessianSerializer implements Serializer {

    /** Hessian 的算法 ID（约定值，P1 起进协议帧） */
    public static final byte TYPE_ID = 1;

    private final io.github.upowerman.serialize.HessianSerializer delegate =
            new io.github.upowerman.serialize.HessianSerializer();

    @Override
    public byte typeId() {
        return TYPE_ID;
    }

    @Override
    public byte[] serialize(Object obj) {
        return delegate.serialize(obj);
    }

    @Override
    public Object deserialize(byte[] bytes, Class<?> clazz) {
        return delegate.deserialize(bytes, clazz);
    }
}