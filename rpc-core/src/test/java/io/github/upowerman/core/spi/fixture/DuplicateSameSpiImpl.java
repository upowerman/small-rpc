package io.github.upowerman.core.spi.fixture;

public class DuplicateSameSpiImpl implements DuplicateSameSpi {
    @Override
    public String name() {
        return "same";
    }
}
