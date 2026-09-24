package io.github.upowerman.core;

/**
 * RPC 框架级常量
 */
public final class RpcConstants {

    /** attachments 中存放目标实例地址的 key，由 ClusterInvoker 写入、远程 Invoker 读取 */
    public static final String ATTACH_ADDRESS = "rpc.address";

    /** attachments 中存放单次调用超时毫秒数的 key */
    public static final String ATTACH_TIMEOUT = "rpc.timeout";

    /** attachments 中存放 traceId 的 key */
    public static final String ATTACH_TRACE_ID = "traceId";

    /** 客户端写空闲 N 秒即发心跳帧；服务端读空闲 3 倍该值关连接 */
    public static final int HEARTBEAT_INTERVAL_SECONDS = 30;

    /** 服务端读空闲超时（秒），超过则视为死连接关闭 */
    public static final int SERVER_IDLE_SECONDS = HEARTBEAT_INTERVAL_SECONDS * 3;

    private RpcConstants() {
    }
}
