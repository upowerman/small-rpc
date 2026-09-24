package io.github.upowerman.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 提供方注解：标注实现类，实现类须实现恰好要暴露的业务接口。
 * <p>
 * 版本维度现状（D-10）：2.0 无版本路由语义——1.x 的 {@code version} 属性已在 2.0 移除，
 * 注册的服务键为接口全限定名，不带版本；P3 如恢复按版本路由，将重新引入该属性。
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface RpcService {
}
