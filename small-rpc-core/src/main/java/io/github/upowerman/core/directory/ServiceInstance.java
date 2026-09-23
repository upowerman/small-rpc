package io.github.upowerman.core.directory;

/**
 * 服务实例：P0 只有地址，weight/startTime 为后续负载均衡与活跃数统计预留
 */
public final class ServiceInstance {

    private final String address;
    private final int weight;
    private final long startTime;

    public ServiceInstance(String address) {
        this(address, 1, System.currentTimeMillis());
    }

    public ServiceInstance(String address, int weight, long startTime) {
        this.address = address;
        this.weight = weight;
        this.startTime = startTime;
    }

    public String getAddress() {
        return address;
    }

    public int getWeight() {
        return weight;
    }

    public long getStartTime() {
        return startTime;
    }

    @Override
    public String toString() {
        return "ServiceInstance{" + address + ", weight=" + weight + "}";
    }
}