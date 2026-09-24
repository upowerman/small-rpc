package io.github.upowerman.core.protocol;

import io.github.upowerman.core.result.Status;

/**
 * 协议帧 status 字段 ↔ {@link Status} 双向映射。
 * 只承载服务端可判定的状态；TIMEOUT/NETWORK_ERROR 是调用方本地状态，不进协议帧
 * （超时由客户端在 PendingRequests 层结算，网络错误由客户端本地生成）。
 */
public final class ProtocolStatus {

    public static final byte SUCCESS = 0;
    public static final byte SERVICE_NOT_FOUND = 1;
    public static final byte METHOD_NOT_FOUND = 2;
    public static final byte SERIALIZATION_ERROR = 3;
    public static final byte SERVER_ERROR = 4;

    private ProtocolStatus() {
    }

    public static byte toCode(Status status) {
        switch (status) {
            case SUCCESS:
                return SUCCESS;
            case SERVICE_NOT_FOUND:
                return SERVICE_NOT_FOUND;
            case METHOD_NOT_FOUND:
                return METHOD_NOT_FOUND;
            case SERIALIZATION_ERROR:
                return SERIALIZATION_ERROR;
            case SERVER_ERROR:
                return SERVER_ERROR;
            default:
                throw new IllegalArgumentException("status not representable in protocol frame: " + status);
        }
    }

    public static Status fromCode(byte code) {
        switch (code) {
            case SUCCESS:
                return Status.SUCCESS;
            case SERVICE_NOT_FOUND:
                return Status.SERVICE_NOT_FOUND;
            case METHOD_NOT_FOUND:
                return Status.METHOD_NOT_FOUND;
            case SERIALIZATION_ERROR:
                return Status.SERIALIZATION_ERROR;
            case SERVER_ERROR:
                return Status.SERVER_ERROR;
            default:
                throw new ProtocolException("unknown status code: " + code);
        }
    }
}
