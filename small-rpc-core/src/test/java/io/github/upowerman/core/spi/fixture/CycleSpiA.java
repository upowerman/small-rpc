package io.github.upowerman.core.spi.fixture;

import io.github.upowerman.core.spi.SpiInject;

public class CycleSpiA implements CycleSpi {

    @SpiInject
    public CycleSpi other;

    public String name() {
        return "cycle-a";
    }
}
