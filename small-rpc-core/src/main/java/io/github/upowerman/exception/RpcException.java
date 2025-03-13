package io.github.upowerman.exception;

/**
 * 统一的异常类
 *
 * @author gaoyunfeng
 */
public class RpcException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RpcException(String msg) {
        super(msg);
    }

    public RpcException(String msg, Throwable cause) {
        super(msg, cause);
    }

    public RpcException(Throwable cause) {
        super(cause);
    }
}
