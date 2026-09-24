package io.github.upowerman.core.invocation;

import java.util.Map;

/**
 * Invocation 默认实现
 */
public class GenericInvocation implements Invocation {

    private final String serviceName;
    private final String methodName;
    private final Class<?>[] parameterTypes;
    private final Object[] arguments;
    private final Map<String, Object> attachments;

    public GenericInvocation(String serviceName, String methodName,
                             Class<?>[] parameterTypes, Object[] arguments,
                             Map<String, Object> attachments) {
        this.serviceName = serviceName;
        this.methodName = methodName;
        this.parameterTypes = parameterTypes;
        this.arguments = arguments;
        this.attachments = attachments;
    }

    @Override
    public String serviceName() {
        return serviceName;
    }

    @Override
    public String methodName() {
        return methodName;
    }

    @Override
    public Class<?>[] parameterTypes() {
        return parameterTypes;
    }

    @Override
    public Object[] arguments() {
        return arguments;
    }

    @Override
    public Map<String, Object> attachments() {
        return attachments;
    }
}
