package io.github.upowerman.core.spi.fixture;

/** M6 夹具：同一实现经两个 URL（target/classes + 本地 jar）登记时必须视为重复而非冲突。 */
public interface DuplicateSameSpi {
    String name();
}
