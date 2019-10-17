package com.yunus.annotation;

import java.lang.annotation.*;

/**
 * 提供rpc调用注解
 *
 * @author gaoyunfeng
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface RpcService {

    /**
     * @return
     */
    String version() default "";

}
