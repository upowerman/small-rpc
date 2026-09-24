package io.github.upowerman.core.spi.fixture;

/** C-2 并发夹具：扩展构造可被测试闸门钉住，用于观察"实例化进行中"的状态。 */
public interface SlowSpi {
    String name();
}
