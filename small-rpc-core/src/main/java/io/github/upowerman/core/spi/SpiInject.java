package io.github.upowerman.core.spi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 扩展实现内的字段注入：实例化后按字段类型注入该 SPI 的默认扩展。
 * 仅支持接口类型字段；基础类型/无登记的接口在装配时大声失败。
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SpiInject {
}
