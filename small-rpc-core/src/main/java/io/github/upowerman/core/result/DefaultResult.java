package io.github.upowerman.core.result;

/**
 * Result 默认实现
 */
public final class DefaultResult implements Result {

    private final Status status;
    private final Object value;
    private final Throwable exception;

    private DefaultResult(Status status, Object value, Throwable exception) {
        this.status = status;
        this.value = value;
        this.exception = exception;
    }

    public static DefaultResult success(Object value) {
        return new DefaultResult(Status.SUCCESS, value, null);
    }

    public static DefaultResult failure(Status status) {
        return new DefaultResult(status, null, null);
    }

    public static DefaultResult failure(Status status, Throwable exception) {
        return new DefaultResult(status, null, exception);
    }

    @Override
    public Status status() {
        return status;
    }

    @Override
    public Object value() {
        return value;
    }

    @Override
    public Throwable exception() {
        return exception;
    }
}