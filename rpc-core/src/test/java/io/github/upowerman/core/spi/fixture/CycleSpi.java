package io.github.upowerman.core.spi.fixture;

import io.github.upowerman.core.spi.Spi;

/** 默认扩展 cycle-a；A 与 B 互相注入，用于验证环检测。 */
@Spi("cycle-a")
public interface CycleSpi {
    String name();
}
