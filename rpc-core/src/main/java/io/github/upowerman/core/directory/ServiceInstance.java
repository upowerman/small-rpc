package io.github.upowerman.core.directory;

/**
 * 服务实例。weight / startTime / warmupMillis 由 Provider 配置并随注册数据传播到消费端：
 * <ul>
 *   <li>{@code weight}：静态权重，加权负载均衡按比例分流量；1 = 等权（缺省）。</li>
 *   <li>{@code startTime}：Provider 进程启动时间戳（毫秒）。0 = 未知（旧格式注册数据），
 *       预热逻辑视为已完全预热，不衰减流量。</li>
 *   <li>{@code warmupMillis}：预热窗口。0 = 关闭预热；&gt;0 时启动后窗口内流量线性爬坡。</li>
 * </ul>
 */
public final class ServiceInstance {

    private final String address;
    private final int weight;
    private final long startTime;
    private final long warmupMillis;

    public ServiceInstance(String address) {
        this(address, 1, 0L, 0L);
    }

    public ServiceInstance(String address, int weight, long startTime) {
        this(address, weight, startTime, 0L);
    }

    public ServiceInstance(String address, int weight, long startTime, long warmupMillis) {
        this.address = address;
        this.weight = weight;
        this.startTime = startTime;
        this.warmupMillis = warmupMillis;
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

    public long getWarmupMillis() {
        return warmupMillis;
    }

    @Override
    public String toString() {
        return "ServiceInstance{" + address + ", weight=" + weight
                + ", warmupMillis=" + warmupMillis + "}";
    }
}
