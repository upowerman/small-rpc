package io.github.upowerman.spring.boot;

import org.junit.Test;
import org.springframework.aop.SpringProxy;
import org.springframework.aop.framework.Advised;
import org.springframework.core.DecoratingProxy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * provider 侧接口解析：原实现 getInterfaces()[0] 在 JDK 代理 / 子类 / 多接口场景
 * 会静默注册错接口或炸启动。解析规则：CGLIB 解包 → 全部接口（含继承）→ 去 Spring 代理基础设施接口
 * → 必须恰好一个业务接口。
 */
public class ServiceInterfaceResolutionTest {

    public interface BizA {
        String a();
    }

    public interface BizB {
        String b();
    }

    public static class PlainImpl implements BizA {
        @Override
        public String a() {
            return "a";
        }
    }

    /** 手写子类：getInterfaces() 不含父类实现的接口 */
    public static class SubclassOfPlain extends PlainImpl {
    }

    public static class NoInterfaceImpl {
    }

    public static class TwoInterfacesImpl implements BizA, BizB {
        @Override
        public String a() {
            return "a";
        }

        @Override
        public String b() {
            return "b";
        }
    }

    @Test
    public void plainImplementationResolvesItsInterface() {
        assertEquals(BizA.class, Rpc2ProviderAutoConfiguration.resolveServiceInterface(new PlainImpl()));
    }

    @Test
    public void subclassResolvesInheritedInterface() {
        assertEquals(BizA.class,
                Rpc2ProviderAutoConfiguration.resolveServiceInterface(new SubclassOfPlain()));
    }

    @Test
    public void jdkDynamicProxyResolvesBusinessInterfaceOnly() {
        // spring.aop.proxy-target-class=false 时的真实形态：业务接口 + Spring 代理基础设施接口
        Object proxy = Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{BizA.class, SpringProxy.class, Advised.class, DecoratingProxy.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object p, Method method, Object[] args) {
                        return null;
                    }
                });

        assertEquals(BizA.class, Rpc2ProviderAutoConfiguration.resolveServiceInterface(proxy));
    }

    @Test
    public void beanWithoutBusinessInterfaceFailsLoudly() {
        try {
            Rpc2ProviderAutoConfiguration.resolveServiceInterface(new NoInterfaceImpl());
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("未找到业务接口"));
            assertTrue(e.getMessage(), e.getMessage().contains(NoInterfaceImpl.class.getName()));
        }
    }

    @Test
    public void multipleBusinessInterfacesFailLoudlyWithCandidates() {
        try {
            Rpc2ProviderAutoConfiguration.resolveServiceInterface(new TwoInterfacesImpl());
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(BizA.class.getName()));
            assertTrue(e.getMessage(), e.getMessage().contains(BizB.class.getName()));
        }
    }
}
