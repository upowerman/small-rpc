package com.yunus.annotation;


import java.lang.annotation.*;

/**
 * @author gaoyunfeng
 */
@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface RpcReference {

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
