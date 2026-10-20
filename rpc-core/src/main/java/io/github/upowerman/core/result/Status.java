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
    NETWORK_ERROR,
    // —— 以下三个为消费端本地结算状态：只在客户端产生、绝不序列化上网络帧 ——
    /** 熔断打开，快速失败（消费端本地） */
    CIRCUIT_OPEN,
    /** 客户端限流拒绝（速率或并发，消费端本地） */
    RATE_LIMITED,
    /** 客户端在途请求超上限（消费端本地） */
    CLIENT_BUSY
}
