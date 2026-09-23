package io.github.upowerman.core.result;

/**
 * 调用结果：返回值 + 状态码 + 异常，取代 1.x 的 errorMsg 字符串
 */
public interface Result {

    Status status();

    Object value();

    Throwable exception();
}