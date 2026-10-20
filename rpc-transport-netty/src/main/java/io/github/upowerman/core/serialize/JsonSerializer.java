package io.github.upowerman.core.serialize;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.upowerman.exception.RpcException;

/**
 * JSON 序列化实现（C6，基于 Jackson，SPI 名 {@code json}，typeId=2）。
 * <p>
 * 协议层零改动：typeId 填入帧 codec 字段，服务端 SerializerRegistry 按 typeId 识别，
 * 响应帧沿用请求 codec——与 Hessian 实现共存。
 * <p>
 * 与 Hessian 的关键差异：JSON 不保留字段的具体运行时类型（如 {@code Object[]} 里的业务
 * DTO 会退化为 Map），因此开启 Jackson default typing（{@code As.PROPERTY} 内嵌 {@code @class}），
 * 让 {@code RpcRequestBody.arguments} 与 {@code RpcResponseBody.value} 里的非最终类型
 * 携带类型信息完成往返。代价：序列化体积略增，且
 * <b>default typing 不适用于不可信数据的反序列化</b>（已知的安全面）——学习框架定位下可接受，
 * 生产接不可信网络需自行换用带白名单的 TypeFactory。
 * <p>
 * 数值类型注意：{@code Map<String, Object>} attachments 里的 Long 经 JSON 往返可能变成
 * Integer，消费端按 Number 宽松解析（见 FailoverClusterInvoker / NettyConnection 的
 * resolveTimeout）。
 */
public class JsonSerializer implements Serializer {

    /** 线上协议 typeId：与 Hessian(1) 共存于同一字节空间，勿复用 */
    public static final byte TYPE_ID = (byte) 2;

    private final ObjectMapper mapper = new ObjectMapper();

    public JsonSerializer() {
        mapper.activateDefaultTyping(
                com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.NON_FINAL,
                com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY);
    }

    @Override
    public byte typeId() {
        return TYPE_ID;
    }

    @Override
    public byte[] serialize(Object obj) {
        try {
            return mapper.writeValueAsBytes(obj);
        } catch (Exception e) {
            throw new RpcException("json serialize failed", e);
        }
    }

    @Override
    public Object deserialize(byte[] bytes, Class<?> clazz) {
        try {
            return mapper.readValue(bytes, clazz);
        } catch (Exception e) {
            throw new RpcException("json deserialize failed", e);
        }
    }
}
