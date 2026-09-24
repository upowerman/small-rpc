package io.github.upowerman.core.spi.fixture;

public class BoomSpiBad implements BoomSpi {

    static {
        explode();
    }

    /** 静态初始化失败：javac 不允许静态块直接以 throw 结束，故经方法调用抛出 */
    private static void explode() {
        throw new RuntimeException("static init boom");
    }

    public String name() { return "boom"; }
}
