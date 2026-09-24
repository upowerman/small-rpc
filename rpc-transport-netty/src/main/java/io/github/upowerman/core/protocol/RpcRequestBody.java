package io.github.upowerman.core.protocol;

import java.io.Serializable;
import java.util.Map;

/**
 * 协议 Body：一次调用的完整描述。把"协议格式"与"调用描述"分开——
 * 未来引入 methodId 只改 Body，不动帧结构。
 * parameterTypes 用 String[]（跨语言友好），服务端 Class.forName 还原。
 * 无参构造 + setter：Hessian 需要可实例化的 POJO。
 */
public class RpcRequestBody implements Serializable {

    private static final long serialVersionUID = 1L;

    private String serviceName;
    private String methodName;
    private String[] parameterTypes;
    private Object[] arguments;
    private Map<String, Object> attachments;

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public String[] getParameterTypes() {
        return parameterTypes;
    }

    public void setParameterTypes(String[] parameterTypes) {
        this.parameterTypes = parameterTypes;
    }

    public Object[] getArguments() {
        return arguments;
    }

    public void setArguments(Object[] arguments) {
        this.arguments = arguments;
    }

    public Map<String, Object> getAttachments() {
        return attachments;
    }

    public void setAttachments(Map<String, Object> attachments) {
        this.attachments = attachments;
    }
}
