package io.github.upowerman.core.protocol;

import java.io.Serializable;

/**
 * 协议 Body：调用结果。成败由**帧 header 的 status 字段**判定，不由 value 是否为空判定——
 * void 方法成功返回时 value 亦为 null。失败时错误以
 * errorClassName + errorMessage 描述（理由见下）。
 * <p>
 * <b>不传输 Throwable 对象</b>：委托的 1.x Hessian 反序列化忽略 clazz 参数，
 * 在网络上传输任意 Throwable 图等于开放任意反序列化面；重试/熔断决策完全由帧
 * header 的 status 驱动，异常类型不承载语义。客户端据这两个字段重建 RpcException。
 */
public class RpcResponseBody implements Serializable {

    private static final long serialVersionUID = 1L;

    private Object value;
    private String errorClassName;
    private String errorMessage;

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }

    public String getErrorClassName() {
        return errorClassName;
    }

    public void setErrorClassName(String errorClassName) {
        this.errorClassName = errorClassName;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
