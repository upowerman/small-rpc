package io.github.upowerman.core.protocol;

/**
 * 协议帧：20 字节定长 Header + 变长 Body。
 * <pre>
 *  0      2     3     4      5      6      7      8             16       20
 * +------+-----+-----+------+------+------+------+--------------+--------+
 * | magic| ver | type| codec|status| rsvd | rsvd | requestId(8) |bodyLen |
 * +------+-----+-----+------+------+------+------+--------------+--------+
 * </pre>
 * 心跳为一等公民（type=HEARTBEAT，无 body），取代 1.x 借道业务请求的 Beat。
 * status 只承载服务端可判定的状态（见 ProtocolStatus）；
 * TIMEOUT/NETWORK_ERROR 是调用方本地状态，不进协议帧。
 */
public final class Frame {

    public static final short MAGIC = (short) 0x5352;   // 'S''R'
    public static final byte VERSION = 1;
    public static final int HEADER_LENGTH = 20;
    public static final int MAX_BODY_LENGTH = 8 * 1024 * 1024;

    public static final byte TYPE_REQUEST = 1;
    public static final byte TYPE_RESPONSE = 2;
    public static final byte TYPE_HEARTBEAT = 3;

    private final byte type;
    private final byte codec;
    private final byte status;
    private final long requestId;
    private final byte[] body;

    /**
     * 包内可见：只有 FrameCodec 解码时需要还原任意 type/status 的帧。
     * 对外不暴露构造器，只能走 request/response/heartbeat 三个工厂方法。
     */
    Frame(byte type, byte codec, byte status, long requestId, byte[] body) {
        this.type = type;
        this.codec = codec;
        this.status = status;
        this.requestId = requestId;
        this.body = body == null ? new byte[0] : body;
    }

    public static Frame request(byte codec, long requestId, byte[] body) {
        return new Frame(TYPE_REQUEST, codec, (byte) 0, requestId, body);
    }

    public static Frame response(byte codec, byte status, long requestId, byte[] body) {
        return new Frame(TYPE_RESPONSE, codec, status, requestId, body);
    }

    public static Frame heartbeat(long requestId) {
        return new Frame(TYPE_HEARTBEAT, (byte) 0, (byte) 0, requestId, new byte[0]);
    }

    public byte type() {
        return type;
    }

    public byte codec() {
        return codec;
    }

    public byte status() {
        return status;
    }

    public long requestId() {
        return requestId;
    }

    /** 返回内部数组引用，调用方不得修改其内容 */
    public byte[] body() {
        return body;
    }

    @Override
    public String toString() {
        return "Frame{type=" + type + ", codec=" + codec + ", status=" + status
                + ", requestId=" + requestId + ", bodyLen=" + body.length + "}";
    }
}
