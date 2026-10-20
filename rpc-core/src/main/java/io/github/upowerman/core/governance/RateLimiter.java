package io.github.upowerman.core.governance;

import io.github.upowerman.core.spi.Spi;

/** 客户端速率限流 SPI：tryAcquire 非阻塞，拿不到令牌立即返回 false */
@Spi("tokenbucket")
public interface RateLimiter {

    boolean tryAcquire(String resource, RateLimitRule rule);
}
