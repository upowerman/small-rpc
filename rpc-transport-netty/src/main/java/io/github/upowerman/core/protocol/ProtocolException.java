package io.github.upowerman.core.protocol;

/**
 * 协议错误：magic/ver 非法、bodyLen 超上限。网络层收到后应关闭连接（流已不可信）。
 */
public class ProtocolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProtocolException(String msg) {
        super(msg);
    }
}
