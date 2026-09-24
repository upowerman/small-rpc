package io.github.upowerman.core.spi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 自适应扩展：value = 从 Invocation.attachments() 里取扩展名的参数键。
 * 只能标注在 SPI 接口上；getAdaptive() 返回按该键分发的动态代理。
 * 不做 Dubbo 式字节码生成——学习项目用 JDK 动态代理讲清原理即可。
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Adaptive {

    /** attachments 里的参数键 */
    String value();
}
