package io.github.upowerman.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 消费方注解：标注字段，装配时注入 2.0 链路代理 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface RpcReference {

    /** 负载均衡扩展名（SPI 名）；空 = 默认扩展 */
    String loadBalance() default "";

    /** 单次调用超时毫秒 */
    long timeout() default 3000;

    /** 直连地址 host:port；空 = 走注册中心 */
    String address() default "";
}
