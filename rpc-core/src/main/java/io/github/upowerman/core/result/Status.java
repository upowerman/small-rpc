package io.github.upowerman.core.result;

/**
 * 调用结果状态码，集群容错层据此决策重试/熔断
 */
public enum Status {
    /** 成功 */
    SUCCESS,
    /** 调用超时 */
    TIMEOUT,
    /** 服务没有可用实例 */
    SERVICE_NOT_FOUND,
    /** 方法不存在 */
    METHOD_NOT_FOUND,
    /** 序列化/反序列化失败 */
    SERIALIZATION_ERROR,
    /** 服务端执行出错 */
    SERVER_ERROR,
    /** 网络错误 */
    NETWORK_ERROR
}