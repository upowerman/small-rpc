package io.github.upowerman.core.spi.fixture;

import io.github.upowerman.core.spi.Spi;

@Spi("a")
public interface DemoSpi {
    String name();
}
