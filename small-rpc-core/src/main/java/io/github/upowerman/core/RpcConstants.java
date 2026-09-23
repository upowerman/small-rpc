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

    private RpcConstants() {
    }
}
