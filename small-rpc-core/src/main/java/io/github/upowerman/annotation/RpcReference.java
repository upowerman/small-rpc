package io.github.upowerman.annotation;


import io.github.upowerman.invoker.route.LoadBalance;
import io.github.upowerman.net.base.NetEnum;
import io.github.upowerman.serialize.SerializeEnum;

import java.lang.annotation.*;

/**
 * 引入调用发接口注解
 *
 * @author gaoyunfeng
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface RpcReference {

    /**
     * 网络类型
     *
     * @return
     */
    NetEnum netType() default NetEnum.NETTY;


    /**
     * 序列化方式
     *
     * @return
     */
    SerializeEnum serializer() default SerializeEnum.HESSIAN;

    /**
     * 负载均衡方式
     *
     * @return
     */
    LoadBalance loadBalance() default LoadBalance.ROUND;

    /**
     * Class<?> iface;
     *
     * @return
     */
    String version() default "";

    /**
     * 默认超时时间为1秒
     *
     * @return
     */
    long timeout() default 1000;

    /**
     * 地址
     *
     * @return
     */
    String address() default "";

}
