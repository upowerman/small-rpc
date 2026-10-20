package io.github.upowerman.core.governance;

import io.github.upowerman.core.spi.Spi;

/**
 * 熔断器 SPI：tryPass 判定放行；onComplete 回填一次调用（含全部重试）的终态。
 * timeoutBudgetMillis 供慢阈值推导（rule.slowRtMillis=0 时取预算×0.8，预算&lt;=0 则慢调用条件停用）。
 */
@Spi("default")
public interface CircuitBreaker {

    /** CLOSED/HALF_OPEN 放行（HALF_OPEN 需占用探测名额）；OPEN 拒绝 */
    boolean tryPass(String resource, CircuitBreakerRule rule);

    /** 调用终态回填：一次调用恰一次 */
    void onComplete(String resource, CircuitBreakerRule rule, boolean success,
                    long rtMillis, long timeoutBudgetMillis);
}
