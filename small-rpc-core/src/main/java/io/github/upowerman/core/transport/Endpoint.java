package io.github.upowerman.core.transport;

/**
 * 通信端点。P0 用 "host:port" 字符串；P1 协议化时按需扩展 ip/port 字段。
 */
public final class Endpoint {

    private final String address;

    private Endpoint(String address) {
        this.address = address;
    }

    public static Endpoint of(String address) {
        return new Endpoint(address);
    }

    public String address() {
        return address;
    }

    @Override
    public String toString() {
        return address;
    }
}