package io.github.upowerman.core.serialize;

import com.caucho.hessian.io.HessianInput;
import com.caucho.hessian.io.HessianOutput;
import io.github.upowerman.exception.RpcException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Hessian 序列化（typeId=1，协议帧 codec 字段）。
 * 基于 HessianInput/HessianOutput 直写，不再委托 1.x。
 * 反序列化沿用既定契约：忽略 clazz 参数，类型防线在调用方强制转型
 * （ServerHandler/ResponseHandler 的 (RpcRequestBody) 转型）。
 */
public class HessianSerializer implements Serializer {

    /** Hessian 的算法 ID（约定值，进协议帧 codec 字段） */
    public static final byte TYPE_ID = 1;

    @Override
    public byte typeId() {
        return TYPE_ID;
    }

    @Override
    public byte[] serialize(Object obj) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        HessianOutput output = new HessianOutput(buffer);
        try {
            output.writeObject(obj);
            output.flush();
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new RpcException("hessian serialize failed", e);
        }
    }

    @Override
    public Object deserialize(byte[] bytes, Class<?> clazz) {
        HessianInput input = new HessianInput(new ByteArrayInputStream(bytes));
        try {
            return input.readObject();
        } catch (IOException e) {
            throw new RpcException("hessian deserialize failed", e);
        }
    }
}
