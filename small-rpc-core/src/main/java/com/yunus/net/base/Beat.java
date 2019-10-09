package com.yunus.net.base;

/**
 * @author gaoyunfeng
 */
public final class Beat {
    /**
     * 心跳间隔
     */
    public static final int BEAT_INTERVAL = 30;
    public static final String BEAT_ID = "BEAT_PING_PONG";

    public static RpcRequest BEAT_PING;

    static {
        BEAT_PING = new RpcRequest() {
        };
        BEAT_PING.setRequestId(BEAT_ID);
    }
}
