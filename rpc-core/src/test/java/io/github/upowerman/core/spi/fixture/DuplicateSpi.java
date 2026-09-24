package io.github.upowerman.core.spi.fixture;

/** M6 夹具：同名登记到不同实现类时必须大声失败。 */
public interface DuplicateSpi {
    String name();
}
