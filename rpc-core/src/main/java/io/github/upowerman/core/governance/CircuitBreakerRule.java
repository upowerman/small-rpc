package io.github.upowerman.core.governance;

/**
 * 熔断规则（不可变，构建后随调用传入 SPI——SPI 按名加载无注入通道，
 * 对应 Dubbo 以 URL 携配置进扩展的动机）。阈值为 0 表示该跳闸条件停用。
 */
public final class CircuitBreakerRule {

    private final double errorRateThreshold;
    private final double slowCallRateThreshold;
    private final long slowRtMillis;
    private final int minCalls;
    private final long windowMillis;
    private final long openMillis;
    private final int halfOpenProbes;

    public CircuitBreakerRule(double errorRateThreshold, double slowCallRateThreshold,
                              long slowRtMillis, int minCalls, long windowMillis,
                              long openMillis, int halfOpenProbes) {
        if (errorRateThreshold < 0 || errorRateThreshold > 1
                || slowCallRateThreshold < 0 || slowCallRateThreshold > 1) {
            throw new IllegalArgumentException("rate threshold must be in [0,1] (0 = disabled): "
                    + errorRateThreshold + "/" + slowCallRateThreshold);
        }
        if (slowRtMillis < 0) {
            throw new IllegalArgumentException("slow-rt-ms must be >= 0: " + slowRtMillis);
        }
        if (minCalls < 0) {
            throw new IllegalArgumentException("min-calls must be >= 0: " + minCalls);
        }
        if (windowMillis <= 0) {
            throw new IllegalArgumentException("window-ms must be > 0: " + windowMillis);
        }
        if (openMillis <= 0) {
            throw new IllegalArgumentException("open-ms must be > 0: " + openMillis);
        }
        if (halfOpenProbes < 1) {
            throw new IllegalArgumentException("half-open-probes must be >= 1: " + halfOpenProbes);
        }
        this.errorRateThreshold = errorRateThreshold;
        this.slowCallRateThreshold = slowCallRateThreshold;
        this.slowRtMillis = slowRtMillis;
        this.minCalls = minCalls;
        this.windowMillis = windowMillis;
        this.openMillis = openMillis;
        this.halfOpenProbes = halfOpenProbes;
    }

    public double errorRateThreshold() { return errorRateThreshold; }
    public double slowCallRateThreshold() { return slowCallRateThreshold; }
    public long slowRtMillis() { return slowRtMillis; }
    public int minCalls() { return minCalls; }
    public long windowMillis() { return windowMillis; }
    public long openMillis() { return openMillis; }
    public int halfOpenProbes() { return halfOpenProbes; }
}
