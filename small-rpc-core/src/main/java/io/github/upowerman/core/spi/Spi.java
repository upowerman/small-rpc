package io.github.upowerman.core.spi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * SPI 接口标注：value = 默认扩展名（可空）。实现类不靠本注解，靠
 * META-INF/small-rpc/{接口全限定名} 登记文件（行格式 name=FQCN，'#' 注释）。
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Spi {

    /** 默认扩展名，空串表示无默认 */
    String value() default "";
}
