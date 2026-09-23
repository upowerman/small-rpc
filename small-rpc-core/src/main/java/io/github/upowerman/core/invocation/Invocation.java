package io.github.upowerman.core.invocation;

import java.util.Map;

/**
 * 一次调用的不变描述：服务、方法、参数、附加属性
 */
public interface Invocation {

    /** 接口全限定名 */
    String serviceName();

    String methodName();

    Class<?>[] parameterTypes();

    Object[] arguments();

    /** 横切信息：traceId、超时、目标地址等 */
    Map<String, Object> attachments();
}
