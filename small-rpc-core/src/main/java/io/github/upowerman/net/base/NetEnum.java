package io.github.upowerman.net.base;

import io.github.upowerman.net.netty.NettyClient;
import io.github.upowerman.net.netty.NettyServer;

/**
 * 定义支持RPC 调用方式的枚举 目前只是netty
 *
 * @author gaoyunfeng
 */

public enum NetEnum {
    /**
     * netty tcp server
     */
    NETTY(NettyServer.class, NettyClient.class);

    public final Class<? extends BaseServer> serverClass;
    public final Class<? extends BaseClient> clientClass;

    NetEnum(Class<? extends BaseServer> serverClass, Class<? extends BaseClient> clientClass) {
        this.serverClass = serverClass;
        this.clientClass = clientClass;
    }

    public static NetEnum autoMatch(String name, NetEnum defaultEnum) {
        for (NetEnum item : NetEnum.values()) {
            if (item.name().equals(name)) {
                return item;
            }
        }
        return defaultEnum;
    }
}
