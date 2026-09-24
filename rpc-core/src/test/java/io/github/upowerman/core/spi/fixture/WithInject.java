package io.github.upowerman.core.spi.fixture;

import io.github.upowerman.core.spi.SpiInject;

public class WithInject implements DemoSpi {

    @SpiInject
    public DemoSpi dependency;

    public String name() {
        return "with";
    }
}
