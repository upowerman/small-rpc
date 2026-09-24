package io.github.upowerman.core.spi.fixture;

/** 构造器可被 {@link ConstructionGate} 阻塞的扩展实现；gate 为 null 时不阻塞。 */
public class SlowSpiImpl implements SlowSpi {

    /** 测试注入的构造闸门 */
    public static volatile ConstructionGate gate;

    public SlowSpiImpl() {
        ConstructionGate current = gate;
        if (current != null) {
            current.enter();
        }
    }

    @Override
    public String name() {
        return "slow";
    }
}
