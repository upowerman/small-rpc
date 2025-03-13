package io.github.upowerman.annotation;

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
     * @return version
     */
    String version() default "";

}
