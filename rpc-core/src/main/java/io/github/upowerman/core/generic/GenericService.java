package io.github.upowerman.core.generic;

import java.util.concurrent.CompletableFuture;

/**
 * 泛化调用门面（C7）：不依赖业务接口 jar 的代理，直接以
 * {@code 服务名 + 方法名 + 参数类型名数组 + 参数} 发起远程调用。
 * <p>
 * 典型场景：网关 / 控制台 / 脚本化运维——只关心地址与方法签名字符串，不需要消费端
 * 引入接口依赖。2.x 边界：参数类型按名称在<b>消费端 classpath</b> 解析
 * （{@link Class#forName}，基本类型支持缩写名）；参数值仍需两端可序列化。
 * 真·零依赖泛化（类型名字符串直通协议）属 3.0 协议演进。
 */
public interface GenericService {

    /**
     * 同步泛化调用。
     *
     * @param serviceName        服务名（接口全限定名）
     * @param methodName         方法名
     * @param parameterTypeNames 参数类型全限定名（或 int/long 等基本类型缩写），无参传空数组
     * @param args               参数值，与类型名一一对应
     * @return 远程方法返回值（失败抛 {@link io.github.upowerman.exception.RpcException}）
     */
    Object $invoke(String serviceName, String methodName, String[] parameterTypeNames, Object[] args);

    /** 异步泛化调用：成功结算业务值，失败以 {@link io.github.upowerman.exception.RpcException} 异常结算 */
    CompletableFuture<Object> $invokeAsync(String serviceName, String methodName,
                                           String[] parameterTypeNames, Object[] args);
}
