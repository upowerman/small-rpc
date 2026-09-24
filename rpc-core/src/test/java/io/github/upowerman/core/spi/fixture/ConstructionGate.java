package io.github.upowerman.core.spi.fixture;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 构造闸门：扩展构造器进入后放倒 entered、并阻塞到 open()。
 * 用于把"某个扩展正在被实例化"这一瞬间钉住，从而断言其他扩展的创建是否被阻塞。
 */
public final class ConstructionGate {

    private final CountDownLatch entered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    /** 在构造器内调用：标记已进入构造并等待放行（超时兜底，避免测试挂死） */
    public void enter() {
        entered.countDown();
        try {
            release.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean awaitEntered(long timeoutMillis) throws InterruptedException {
        return entered.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    public void open() {
        release.countDown();
    }
}
