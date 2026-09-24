package io.github.upowerman.core.transport;

/**
 * 通信端点。沿用 "host:port" 字符串形式，host/port 拆分在 transport 实现内部完成。
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