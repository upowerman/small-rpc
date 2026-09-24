package io.github.upowerman.core.spi.fixture;

/** 构造不阻塞的扩展实现，与 {@link SlowSpiImpl} 登记在同一接口下。 */
public class FastSpiImpl implements SlowSpi {

    @Override
    public String name() {
        return "fast";
    }
}
