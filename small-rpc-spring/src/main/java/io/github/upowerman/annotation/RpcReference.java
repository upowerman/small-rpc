package io.github.upowerman.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 消费方注解：标注字段，装配时注入 2.0 链路代理。
 * <p>
 * 版本维度现状（D-10）：2.0 无版本路由语义——1.x 的 {@code version} 属性已在 2.0 移除，
 * 服务键为接口全限定名，消费端不携带版本；P3 如恢复按版本路由，将重新引入该属性。
 */
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
