# small-rpc 2.0 P2 实施计划（拆多模块 + 自研 SPI + Spring 搬家）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 feature/rpc2 上完成 spec §5：拆出多模块 Maven（rpc-core 只依赖 slf4j）、自研类 Dubbo SPI（按名查找 + IoC + Adaptive）、Spring 集成搬家，样例改纯 2.0。

**Architecture:** 先加后删——SPI 框架与 Spring/starter（新增逻辑）先在现有单模块结构上落地并全绿；随后一次性拆模块（T5，纯机械：移动/删除/改 pom），此时 1.x 已无人依赖，删除安全。SPI 代码放在 `core.spi` 包，拆模块时随包自然迁入 rpc-core；META-INF 登记文件随实现类迁入各自模块。

**Tech Stack:** Java 8 / JUnit 4.13.2 / Netty 4.1.108.Final / Hessian 4.0.66 / Spring Boot 2.7.18 / Maven 多模块

**Spec:** `docs/superpowers/specs/2026-09-24-small-rpc-2-phase2-design.md`（含已裁定的四个范围决策）

## Global Constraints

- Java 8 语法（无 `var`/`List.of`/`orTimeout`/`Stream.toList()`/`delayedExecutor`）
- JUnit 4.13.2（`@Test`/`Assert`，不用 JUnit5）
- **绝不运行 `mvn verify`**（GPG 插件本机不可用，P0/P1 既定禁令）；构建/测试命令见各步
- 拆模块前单模块命令：`mvn -f small-rpc-core/pom.xml test -q`；样例构建先 `mvn -f small-rpc-core/pom.xml install -DskipTests -Dgpg.skip=true -Dmaven.javadoc.skip=true` 再 `mvn -f small-rpc-simple/pom.xml test`
- rpc-core（拆分后）**只依赖 slf4j-api + junit(test)**——不出现 Netty/Spring/Hessian/ZK/Redis
- 临时端口只用 `new ServerSocket(0)` 探测（ephemeral），绝不硬编码业务端口做测试
- `git add` 只用明确路径，绝不用 `-A`/`.`；工作区未跟踪的 `mise.toml` 不是任何任务的产物，绝不要 add
- 分支 feature/rpc2：不合并、不 push
- 所有新代码注释风格随既有代码（中文 javadoc、作者可省略）

## Review Focus

1. **SPI 登记文件与类路径漂移**——META-INF 行里的 FQCN 改包名/搬模块后忘记同步 → 运行时 `ClassNotFound`/`no spi extension`；每个登记文件在 T5 随模块搬家时有核对步骤
2. **SpiLoader 类加载器选择**——只用 TCCL 会丢跨类加载器场景（Spring Boot devtools/打包后），用 `Thread.currentThread().getContextClassLoader()` 与 `SpiLoader.class.getClassLoader()` 双源 `getResources` 去重合并
3. **@SpiInject 循环依赖**——A 注入 B、B 注入 A 必须大声失败（IllegalStateException），不允许栈溢出或死锁
4. **拆模块后 1.x 残留**——`io.github.upowerman.{net,invoker,provider,serialize(1.x),registry(旧包)}` 与 `core/adapter/` 必须零残留，验证 grep 步骤在 T5
5. **样例双链路删除后的等价性**——1.x /hello 链路删除后，同一路径必须由 2.0 链路承接（curl 200 断言，T6）

---

### Task 1: SPI 框架——@Spi 注解 + SpiLoader 按名查找 + META-INF 登记

**Files:**
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/spi/Spi.java`
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/spi/SpiLoader.java`
- Create: `small-rpc-core/src/main/resources/META-INF/small-rpc/io.github.upowerman.core.loadbalance.LoadBalancer`
- Create: `small-rpc-core/src/main/resources/META-INF/small-rpc/io.github.upowerman.core.serialize.Serializer`
- Create: `small-rpc-core/src/main/resources/META-INF/small-rpc/io.github.upowerman.core.registry.BaseServiceRegistry`
- Modify: `small-rpc-core/src/main/java/io/github/upowerman/core/loadbalance/LoadBalancer.java`（加 `@Spi("random")`）
- Modify: `small-rpc-core/src/main/java/io/github/upowerman/core/serialize/Serializer.java`（加 `@Spi("hessian")`）
- Modify: `small-rpc-core/src/main/java/io/github/upowerman/core/registry/BaseServiceRegistry.java`——本任务**不改**（此时仍在 1.x 包 `io.github.upowerman.registry`，见下）；登记文件路径以**当前实际包名**为准：`META-INF/small-rpc/io.github.upowerman.registry.BaseServiceRegistry`
- Test: `small-rpc-core/src/test/java/io/github/upowerman/core/spi/SpiLoaderTest.java` + test fixtures

**Interfaces:**
- Consumes: 现有 `LoadBalancer`（random/roundrobin 两实现）、`Serializer`（LegacyHessianSerializer）、`BaseServiceRegistry`（LocalServiceRegistry）
- Produces（后续任务依赖的确切签名）:
  - `SpiLoader.of(Class<S> type) → SpiLoader<S>`
  - `SpiLoader<S>.getExtension(String name) → S`（null/空名 → 默认；未知名 → IllegalStateException，消息含 supported 列表）
  - `SpiLoader<S>.getDefaultExtension() → S`（读接口上的 `@Spi` value）
  - `SpiLoader<S>.getSupportedExtensions() → Set<String>`
  - `@Spi("名字")`（`ElementType.TYPE`，RUNTIME）——标注在 **SPI 接口**上 = 默认扩展名
  - 本任务不含 @SpiInject 与 Adaptive（Task 2）

- [ ] **Step 1: 写失败测试**

`small-rpc-core/src/test/java/io/github/upowerman/core/spi/SpiLoaderTest.java`：

```java
package io.github.upowerman.core.spi;

import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * SpiLoader 按名查找：登记解析、单例、默认扩展、大声失败。
 * fixtures 用 test 资源里独立登记的 DemoSpi，不与生产 META-INF 混用。
 */
public class SpiLoaderTest {

    @Test
    public void getExtensionReturnsRegisteredImplByName() {
        DemoSpi a = SpiLoader.of(DemoSpi.class).getExtension("a");
        assertTrue(a instanceof DemoSpiA);
    }

    @Test
    public void getExtensionIsSingletonPerName() {
        SpiLoader<DemoSpi> loader = SpiLoader.of(DemoSpi.class);
        assertSame(loader.getExtension("a"), loader.getExtension("a"));
    }

    @Test
    public void getSupportedExtensionsListsAllNames() {
        Set<String> names = SpiLoader.of(DemoSpi.class).getSupportedExtensions();
        assertEquals(new java.util.LinkedHashSet<String>(java.util.Arrays.asList("a", "b")), names);
    }

    @Test
    public void getDefaultExtensionReadsSpiAnnotationOnInterface() {
        assertTrue(SpiLoader.of(DemoSpi.class).getDefaultExtension() instanceof DemoSpiA);
    }

    @Test
    public void nullOrEmptyNameFallsBackToDefault() {
        assertTrue(SpiLoader.of(DemoSpi.class).getExtension(null) instanceof DemoSpiA);
        assertTrue(SpiLoader.of(DemoSpi.class).getExtension("") instanceof DemoSpiA);
    }

    @Test
    public void unknownNameFailsLoudlyWithSupportedList() {
        try {
            SpiLoader.of(DemoSpi.class).getExtension("nope");
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("nope"));
            assertTrue(e.getMessage().contains("a, b"));
        }
    }

    @Test
    public void interfaceWithoutSpiAnnotationHasNoDefault() {
        try {
            SpiLoader.of(DemoSpiNoDefault.class).getDefaultExtension();
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("@Spi"));
        }
    }
}
```

Test fixtures（同目录 `fixture/`）：

```java
// fixture/DemoSpi.java
package io.github.upowerman.core.spi.fixture;
import io.github.upowerman.core.spi.Spi;

@Spi("a")
public interface DemoSpi {
    String name();
}

// fixture/DemoSpiA.java
package io.github.upowerman.core.spi.fixture;
public class DemoSpiA implements DemoSpi {
    public String name() { return "a"; }
}

// fixture/DemoSpiB.java
package io.github.upowerman.core.spi.fixture;
public class DemoSpiB implements DemoSpi {
    public String name() { return "b"; }
}

// fixture/DemoSpiNoDefault.java —— 无 @Spi 的接口（默认扩展缺失场景）
package io.github.upowerman.core.spi.fixture;
public interface DemoSpiNoDefault {
    String name();
}

// fixture/DemoSpiNoDefaultImpl.java
package io.github.upowerman.core.spi.fixture;
public class DemoSpiNoDefaultImpl implements DemoSpiNoDefault {
    public String name() { return "x"; }
}
```

Test 登记文件 `small-rpc-core/src/test/resources/META-INF/small-rpc/io.github.upowerman.core.spi.fixture.DemoSpi`：

```
# name=FQCN，'#' 开头为注释
a=io.github.upowerman.core.spi.fixture.DemoSpiA
b=io.github.upowerman.core.spi.fixture.DemoSpiB
```

Test 登记文件 `small-rpc-core/src/test/resources/META-INF/small-rpc/io.github.upowerman.core.spi.fixture.DemoSpiNoDefault`：

```
x=io.github.upowerman.core.spi.fixture.DemoSpiNoDefaultImpl
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -f small-rpc-core/pom.xml test -q -Dtest=SpiLoaderTest`
Expected: 编译失败（`Spi`/`SpiLoader`/fixtures 中 `Spi` 不存在）

- [ ] **Step 3: 实现 @Spi 与 SpiLoader**

`Spi.java`：

```java
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
```

`SpiLoader.java`：

```java
package io.github.upowerman.core.spi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自研 SPI 装载器（类 Dubbo，不用 JDK ServiceLoader）。
 * 登记文件：classpath 下 META-INF/small-rpc/{接口全限定名}，行格式 name=FQCN。
 * 双类加载器枚举（TCCL + 本类 CL）合并去重，兼容 Spring Boot 打包与 devtools。
 * 扩展实例懒加载单例；任何异常（缺文件/重名/类型不符）都大声失败，不静默吞。
 */
public final class SpiLoader<S> {

    private static final Logger logger = LoggerFactory.getLogger(SpiLoader.class);

    private static final String PREFIX = "META-INF/small-rpc/";

    private static final ConcurrentHashMap<Class<?>, SpiLoader<?>> LOADERS =
            new ConcurrentHashMap<Class<?>, SpiLoader<?>>();

    private final Class<S> type;
    private final Map<String, Class<S>> implClasses = new LinkedHashMap<String, Class<S>>();
    private final ConcurrentHashMap<String, S> singletons = new ConcurrentHashMap<String, S>();

    private SpiLoader(Class<S> type) {
        this.type = type;
        loadRegistrationFiles();
    }

    @SuppressWarnings("unchecked")
    public static <S> SpiLoader<S> of(Class<S> type) {
        if (type == null || !type.isInterface()) {
            throw new IllegalArgumentException("spi type must be an interface: " + type);
        }
        SpiLoader<?> existing = LOADERS.get(type);
        if (existing == null) {
            SpiLoader<?> created = new SpiLoader<Object>((Class<Object>) type);
            existing = LOADERS.putIfAbsent(type, created);
            if (existing == null) {
                existing = created;
            }
        }
        return (SpiLoader<S>) existing;
    }

    /** 按名取扩展（懒加载单例）；null/空名 = 默认扩展 */
    public S getExtension(String name) {
        if (name == null || name.isEmpty()) {
            return getDefaultExtension();
        }
        S instance = singletons.get(name);
        if (instance == null) {
            instance = createSingleton(name);
        }
        return instance;
    }

    /** 接口 @Spi value 指定的默认扩展 */
    public S getDefaultExtension() {
        Spi spi = type.getAnnotation(Spi.class);
        if (spi == null || spi.value().isEmpty()) {
            throw new IllegalStateException("no default @Spi name on interface " + type.getName());
        }
        return getExtension(spi.value());
    }

    public Set<String> getSupportedExtensions() {
        return new LinkedHashSet<String>(implClasses.keySet());
    }

    private S createSingleton(String name) {
        synchronized (this) {
            S instance = singletons.get(name);
            if (instance != null) {
                return instance;
            }
            Class<S> impl = implClasses.get(name);
            if (impl == null) {
                throw new IllegalStateException("no spi extension named '" + name + "' for "
                        + type.getName() + ", supported: " + joinNames());
            }
            instance = newInstance(impl);
            singletons.put(name, instance);
            logger.debug("spi extension instantiated: {} = {}", name, impl.getName());
            return instance;
        }
    }

    private S newInstance(Class<S> impl) {
        try {
            return impl.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot instantiate spi extension " + impl.getName()
                    + " (需要公共无参构造器)", e);
        }
    }

    private void loadRegistrationFiles() {
        String fileName = PREFIX + type.getName();
        try {
            Set<String> seenUrls = new HashSet<String>();
            ClassLoader[] loaders = new ClassLoader[]{
                    Thread.currentThread().getContextClassLoader(), SpiLoader.class.getClassLoader()};
            for (ClassLoader loader : loaders) {
                if (loader == null) {
                    continue;
                }
                Enumeration<URL> urls = loader.getResources(fileName);
                while (urls.hasMoreElements()) {
                    URL url = urls.nextElement();
                    if (seenUrls.add(url.toString())) {
                        parseFile(url);
                    }
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read spi registration file " + fileName, e);
        }
        if (implClasses.isEmpty()) {
            throw new IllegalStateException("no spi impl registered for " + type.getName()
                    + " (missing " + fileName + "?)");
        }
    }

    private void parseFile(URL url) {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(url.openStream(), StandardCharsets.UTF_8));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    int eq = trimmed.indexOf('=');
                    if (eq <= 0 || eq == trimmed.length() - 1) {
                        throw new IllegalStateException("bad spi line in " + url + ": " + line);
                    }
                    String name = trimmed.substring(0, eq).trim();
                    String fqcn = trimmed.substring(eq + 1).trim();
                    if (implClasses.containsKey(name)) {
                        throw new IllegalStateException("duplicate spi name '" + name + "' for "
                                + type.getName() + " in " + url);
                    }
                    Class<?> clazz = Class.forName(fqcn, true, SpiLoader.class.getClassLoader());
                    if (!type.isAssignableFrom(clazz)) {
                        throw new IllegalStateException("spi impl " + fqcn + " does not implement "
                                + type.getName() + " (in " + url + ")");
                    }
                    implClasses.put(name, (Class<S>) clazz.asSubclass(type));
                }
            } finally {
                reader.close();
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read spi registration file " + url, e);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("spi impl class not found (登记文件与类路径漂移?): " + e.getMessage(), e);
        }
    }

    private String joinNames() {
        StringBuilder sb = new StringBuilder();
        for (String name : implClasses.keySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(name);
        }
        return sb.toString();
    }
}
```

（注：`LinkedHashSet` 需 import `java.util.LinkedHashSet`。）

生产登记文件三个：

`small-rpc-core/src/main/resources/META-INF/small-rpc/io.github.upowerman.core.loadbalance.LoadBalancer`：

```
random=io.github.upowerman.core.loadbalance.RandomLoadBalancer
roundrobin=io.github.upowerman.core.loadbalance.RoundRobinLoadBalancer
```

`small-rpc-core/src/main/resources/META-INF/small-rpc/io.github.upowerman.core.serialize.Serializer`：

```
hessian=io.github.upowerman.core.serialize.LegacyHessianSerializer
```

`small-rpc-core/src/main/resources/META-INF/small-rpc/io.github.upowerman.registry.BaseServiceRegistry`（注意：此时 BaseServiceRegistry 仍在 1.x 包，文件名用现包名；T5 搬迁时同步改名）：

```
local=io.github.upowerman.registry.impl.LocalServiceRegistry
```

接口加注解（两处一行改动）：

```java
// LoadBalancer.java
@Spi("random")
public interface LoadBalancer {

// Serializer.java
@Spi("hessian")
public interface Serializer {
```

（import `io.github.upowerman.core.spi.Spi`。BaseServiceRegistry 本任务不加注解——它不是 interface，SpiLoader.of 会拒绝 abstract class。**裁定**：登记文件保留，Task 2 的 registry 接入改走 `SpiLoader` 之外的装配方式或 T5 将 BaseServiceRegistry 改为 interface 时再接入；本任务先只登记 LoadBalancer 与 Serializer 于生产 META-INF，registry 登记文件**一并创建但暂无人消费**，T5 改 interface 后生效。）

- [ ] **Step 4: 跑测试确认通过 + 全量回归**

Run: `mvn -f small-rpc-core/pom.xml test -q`
Expected: 全量 PASS（基线 104 + 新增 7 = 111）

- [ ] **Step 5: Commit**

```bash
git add small-rpc-core/src/main/java/io/github/upowerman/core/spi small-rpc-core/src/main/resources/META-INF small-rpc-core/src/main/java/io/github/upowerman/core/loadbalance/LoadBalancer.java small-rpc-core/src/main/java/io/github/upowerman/core/serialize/Serializer.java small-rpc-core/src/test
git commit -m "feat(rpc2): custom SPI framework — @Spi + SpiLoader name lookup with META-INF registration"
```

---

### Task 2: SPI 增强——@SpiInject IoC 构造注入 + Adaptive 动态分发

**Files:**
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/spi/SpiInject.java`
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/spi/Adaptive.java`
- Modify: `small-rpc-core/src/main/java/io/github/upowerman/core/spi/SpiLoader.java`（newInstance 后注入 + getAdaptive）
- Modify: `small-rpc-core/src/main/java/io/github/upowerman/core/loadbalance/LoadBalancer.java`（加 `@Adaptive("lb")`）
- Test: `small-rpc-core/src/test/java/io/github/upowerman/core/spi/SpiInjectTest.java`、`SpiAdaptiveTest.java`

**Interfaces:**
- Consumes: Task 1 的 SpiLoader 全部签名
- Produces:
  - `@SpiInject`（`ElementType.FIELD`，RUNTIME）——扩展实现内的字段注入其它 SPI 的**默认扩展**
  - `@Adaptive("参数键")`（`ElementType.TYPE`，RUNTIME）——标注在 SPI 接口上
  - `SpiLoader<S>.getAdaptive() → S`——JDK 动态代理分发器：方法调用时从首个 `Invocation` 类型参数的 `attachments().get(key)` 取扩展名（空/缺省 → 默认扩展）再委托；接口没有任何含 `Invocation` 参数的方法时 `getAdaptive()` 抛 IllegalStateException

- [ ] **Step 1: 写失败测试（注入）**

`SpiInjectTest.java`：

```java
package io.github.upowerman.core.spi;

import io.github.upowerman.core.spi.fixture.CycleSpi;
import io.github.upowerman.core.spi.fixture.CycleSpiA;
import org.junit.Test;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * @SpiInject：默认扩展按类型注入；循环依赖大声失败。
 */
public class SpiInjectTest {

    @Test
    public void spiInjectFieldGetsDefaultExtension() {
        WithInject ext = SpiLoader.of(WithInject.class).getExtension("with");
        assertTrue(ext.dependency instanceof DemoSpi);
        // 注入的是同一 SpiLoader 池里的单例
        assertSame(SpiLoader.of(DemoSpi.class).getDefaultExtension(), ext.dependency);
    }

    @Test
    public void cycleDependencyFailsLoudlyNotStackOverflow() {
        try {
            SpiLoader.of(CycleSpi.class).getExtension("cycle-a");
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("cycle"));
        }
    }
}
```

fixtures（`fixture/` 追加）：

```java
// WithInject.java
package io.github.upowerman.core.spi.fixture;
import io.github.upowerman.core.spi.SpiInject;

public class WithInject implements DemoSpi {
    public @SpiInject DemoSpi dependency;
    public String name() { return "with"; }
}

// CycleSpi.java
package io.github.upowerman.core.spi.fixture;
public interface CycleSpi {
    String name();
}

// CycleSpiA.java —— A 注入 B，B 注入 A：必须 IllegalStateException，不是 StackOverflowError
package io.github.upowerman.core.spi.fixture;
import io.github.upowerman.core.spi.SpiInject;

public class CycleSpiA implements CycleSpi {
    public @SpiInject CycleSpi other;
    public String name() { return "cycle-a"; }
}

// CycleSpiB.java
package io.github.upowerman.core.spi.fixture;
import io.github.upowerman.core.spi.SpiInject;

public class CycleSpiB implements CycleSpi {
    public @SpiInject CycleSpi other;
    public String name() { return "cycle-b"; }
}
```

test 登记文件追加：

`src/test/resources/META-INF/small-rpc/io.github.upowerman.core.spi.fixture.CycleSpi`：

```
cycle-a=io.github.upowerman.core.spi.fixture.CycleSpiA
cycle-b=io.github.upowerman.core.spi.fixture.CycleSpiB
```

`src/test/resources/META-INF/small-rpc/io.github.upowerman.core.spi.fixture.DemoSpi` 追加一行：

```
with=io.github.upowerman.core.spi.fixture.WithInject
```

- [ ] **Step 2: 写失败测试（Adaptive）**

`SpiAdaptiveTest.java`：

```java
package io.github.upowerman.core.spi;

import io.github.upowerman.core.directory.ServiceInstance;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.loadbalance.LoadBalancer;
import io.github.upowerman.core.spi.fixture.DemoSpi;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Adaptive 分发器：attachments[key] 决定扩展；缺省走默认；无 Invocation 参数的接口大声失败。
 */
public class SpiAdaptiveTest {

    private static Invocation invocationWith(Map<String, Object> attachments) {
        return new GenericInvocationForTest(attachments);
    }

    @Test
    public void adaptiveDispatchesByAttachmentsKey() {
        // random 与 roundrobin 对空/单元素列表行为无法区分——用 roundrobin 的共享计数器语义断言：
        // lb=roundrobin 时两次 select 在两实例间交替；lb=random 只断言选中列表成员。
        ServiceInstance a = new ServiceInstance("addr-a");
        ServiceInstance b = new ServiceInstance("addr-b");
        LoadBalancer adaptive = SpiLoader.of(LoadBalancer.class).getAdaptive();

        Map<String, Object> rr = new HashMap<String, Object>();
        rr.put("lb", "roundrobin");
        Invocation inv = invocationWith(rr);
        ServiceInstance first = adaptive.select(Arrays.asList(a, b), inv);
        ServiceInstance second = adaptive.select(Arrays.asList(a, b), inv);
        assertTrue((first == a && second == b) || (first == b && second == a));
    }

    @Test
    public void adaptiveWithoutKeyUsesDefaultExtension() {
        LoadBalancer adaptive = SpiLoader.of(LoadBalancer.class).getAdaptive();
        ServiceInstance picked = adaptive.select(
                Collections.singletonList(new ServiceInstance("only")), invocationWith(Collections.<String, Object>emptyMap()));
        assertEquals("only", picked.getAddress());
    }

    @Test
    public void adaptiveWithoutInvocationParameterFails() {
        try {
            SpiLoader.of(DemoSpi.class).getAdaptive();
            fail("expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Invocation"));
        }
    }
}
```

测试夹具 `GenericInvocationForTest`（`fixture/`）：

```java
package io.github.upowerman.core.spi.fixture;
import io.github.upowerman.core.invocation.Invocation;
import java.util.Collections;
import java.util.Map;

public final class GenericInvocationForTest implements Invocation {
    private final Map<String, Object> attachments;
    public GenericInvocationForTest(Map<String, Object> attachments) {
        this.attachments = attachments == null ? Collections.<String, Object>emptyMap() : attachments;
    }
    public String serviceName() { return "demo"; }
    public String methodName() { return "name"; }
    public Class<?>[] parameterTypes() { return new Class<?>[0]; }
    public Object[] arguments() { return new Object[0]; }
    public Map<String, Object> attachments() { return attachments; }
}
```

（若 `core.invocation` 已有可公开构造的 GenericInvocation，直接用之——实现者先读 `GenericInvocation.java` 构造器签名，等价替换并删除本 fixture，测试语义不变。）

- [ ] **Step 3: 跑测试确认失败**

Run: `mvn -f small-rpc-core/pom.xml test -q -Dtest='SpiInjectTest,SpiAdaptiveTest'`
Expected: 编译失败（`SpiInject`/`Adaptive`/`getAdaptive` 不存在）

- [ ] **Step 4: 实现**

`SpiInject.java`：

```java
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
```

`Adaptive.java`：

```java
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
```

`SpiLoader.java` 三处修改：

1) 字段追加：

```java
    /** 正在构造中的实现类（SpiInject 环检测；单 loader 内 synchronized 串行，ThreadLocal 防递归重入） */
    private static final ThreadLocal<java.util.Set<Class<?>>> CONSTRUCTING =
            new ThreadLocal<java.util.Set<Class<?>>>() {
                @Override
                protected java.util.Set<Class<?>> initialValue() {
                    return new java.util.HashSet<Class<?>>();
                }
            };
```

2) `newInstance(impl)` 改为 `instantiate(impl)`（getExtension 调用点同步改名）：

```java
    private S instantiate(Class<S> impl) {
        java.util.Set<Class<?>> visiting = CONSTRUCTING.get();
        if (!visiting.add(impl)) {
            throw new IllegalStateException("spi cycle detected: " + impl.getName()
                    + " is already being constructed (SpiInject 环依赖)");
        }
        try {
            S instance = impl.getDeclaredConstructor().newInstance();
            injectFields(instance);
            return instance;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot instantiate spi extension " + impl.getName()
                    + " (需要公共无参构造器)", e);
        } finally {
            visiting.remove(impl);
        }
    }

    private void injectFields(S instance) throws IllegalAccessException {
        for (Class<?> c = instance.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (java.lang.reflect.Field field : c.getDeclaredFields()) {
                if (!field.isAnnotationPresent(SpiInject.class)) {
                    continue;
                }
                field.setAccessible(true);
                if (field.get(instance) != null) {
                    continue;
                }
                Object dependency = SpiLoader.of(field.getType()).getDefaultExtension();
                field.set(instance, dependency);
            }
        }
    }
```

3) 追加 `getAdaptive()`（import `io.github.upowerman.core.invocation.Invocation`、`java.lang.reflect.InvocationHandler`、`java.lang.reflect.Method`、`java.lang.reflect.Proxy`）：

```java
    private volatile S adaptiveProxy;

    /** 自适应分发器（单例）：方法调用时按 attachments[key] 选扩展再委托 */
    @SuppressWarnings("unchecked")
    public S getAdaptive() {
        Adaptive adaptive = type.getAnnotation(Adaptive.class);
        if (adaptive == null) {
            throw new IllegalStateException(type.getName() + " is not annotated with @Adaptive");
        }
        boolean hasInvocationParameter = false;
        for (Method method : type.getMethods()) {
            for (Class<?> parameterType : method.getParameterTypes()) {
                if (parameterType == Invocation.class) {
                    hasInvocationParameter = true;
                }
            }
        }
        if (!hasInvocationParameter) {
            throw new IllegalStateException(type.getName()
                    + " has no method taking an Invocation parameter; @Adaptive 不可用");
        }
        S result = adaptiveProxy;
        if (result == null) {
            final String key = adaptive.value();
            result = (S) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            if (method.getDeclaringClass() == Object.class) {
                                return method.invoke(SpiLoader.this, args);
                            }
                            Invocation invocation = findInvocation(method, args);
                            if (invocation == null) {
                                throw new IllegalStateException(
                                        "adaptive method must take an Invocation parameter: " + method);
                            }
                            Object raw = invocation.attachments() == null
                                    ? null : invocation.attachments().get(key);
                            String name = raw == null ? null : String.valueOf(raw);
                            Object target = getExtension(name);
                            return method.invoke(target, args);
                        }
                    });
            adaptiveProxy = result;
        }
        return result;
    }

    private static Invocation findInvocation(Method method, Object[] args) {
        Class<?>[] parameterTypes = method.getParameterTypes();
        for (int i = 0; i < parameterTypes.length; i++) {
            if (parameterTypes[i] == Invocation.class && args != null && args[i] != null) {
                return (Invocation) args[i];
            }
        }
        return null;
    }
```

`LoadBalancer.java` 加 `@Adaptive("lb")`（与 Task 1 的 `@Spi("random")` 并列）。

- [ ] **Step 5: 跑测试确认通过 + 全量回归**

Run: `mvn -f small-rpc-core/pom.xml test -q`
Expected: 全量 PASS（111 + 5 = 116）

- [ ] **Step 6: Commit**

```bash
git add small-rpc-core/src/main/java/io/github/upowerman/core/spi small-rpc-core/src/main/java/io/github/upowerman/core/loadbalance/LoadBalancer.java small-rpc-core/src/test
git commit -m "feat(rpc2): SPI @SpiInject IoC + Adaptive dispatcher (JDK proxy, no codegen)"
```

---

### Task 3: SPI 接入点——SerializerRegistry.fromSpi + 生产装配落位

**Files:**
- Modify: `small-rpc-core/src/main/java/io/github/upowerman/core/serialize/SerializerRegistry.java`（加 `fromSpi()` 静态工厂）
- Test: `small-rpc-core/src/test/java/io/github/upowerman/core/serialize/SerializerRegistrySpiTest.java`

**Interfaces:**
- Consumes: Task 1/2 的 SpiLoader、生产 META-INF（hessian 登记）
- Produces: `SerializerRegistry.fromSpi() → SerializerRegistry`——注册所有经 SPI 登记的 Serializer（T4 starter 用它替代手工 `new LegacyHessianSerializer()`；T5 后等价于「rpc-core 不认识 Hessian，transport-netty 经登记文件供上」）

- [ ] **Step 1: 写失败测试**

```java
package io.github.upowerman.core.serialize;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/** fromSpi：生产登记的序列化实现全部装配进注册表（当前 = hessian/typeId 1）。 */
public class SerializerRegistrySpiTest {

    @Test
    public void fromSpiRegistersAllSpiSerializers() {
        SerializerRegistry registry = SerializerRegistry.fromSpi();
        Serializer hessian = registry.find((byte) 1);
        assertNotNull(hessian);
        assertEquals(1, hessian.typeId());
    }

    @Test
    public void fromSpiRoundTrips() {
        SerializerRegistry registry = SerializerRegistry.fromSpi();
        Serializer serializer = registry.find((byte) 1);
        String original = "spi-hello";
        byte[] bytes = serializer.serialize(original);
        assertEquals(original, serializer.deserialize(bytes, String.class));
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `mvn -f small-rpc-core/pom.xml test -q -Dtest=SerializerRegistrySpiTest`
Expected: 编译失败（`fromSpi` 不存在）

- [ ] **Step 3: 实现**

`SerializerRegistry.java` 追加（import `io.github.upowerman.core.spi.SpiLoader`）：

```java
    /** 装配所有经 SPI 登记的序列化实现；rpc-core 不认识任何具体序列化器 */
    public static SerializerRegistry fromSpi() {
        SerializerRegistry registry = new SerializerRegistry();
        SpiLoader<Serializer> loader = SpiLoader.of(Serializer.class);
        for (String name : loader.getSupportedExtensions()) {
            registry.register(loader.getExtension(name));
        }
        return registry;
    }
```

- [ ] **Step 4: 跑测试确认通过 + 全量回归**

Run: `mvn -f small-rpc-core/pom.xml test -q`
Expected: 全量 PASS（116 + 2 = 118）

- [ ] **Step 5: Commit**

```bash
git add small-rpc-core/src/main/java/io/github/upowerman/core/serialize/SerializerRegistry.java small-rpc-core/src/test/java/io/github/upowerman/core/serialize/SerializerRegistrySpiTest.java
git commit -m "feat(rpc2): SerializerRegistry.fromSpi — wire serializers via SPI registry"
```

---

### Task 4: rpc-spring + rpc-spring-boot-starter + 样例纯 2.0

**Files:**
- Create: `small-rpc-spring/pom.xml`、`small-rpc-spring-boot-starter/pom.xml`
- Create: `small-rpc-spring/src/main/java/io/github/upowerman/annotation/RpcService.java`（自 small-rpc-core git mv，删 `version()` 属性）
- Create: `small-rpc-spring/src/main/java/io/github/upowerman/annotation/RpcReference.java`（重写：删 1.x 枚举属性）
- Create: `small-rpc-spring/src/main/java/io/github/upowerman/spring/ReferenceBeanPostProcessor.java`
- Create: `small-rpc-spring-boot-starter/src/main/java/io/github/upowerman/spring/boot/Rpc2Properties.java`
- Create: `small-rpc-spring-boot-starter/src/main/java/io/github/upowerman/spring/boot/Rpc2ProviderAutoConfiguration.java`
- Create: `small-rpc-spring-boot-starter/src/main/java/io/github/upowerman/spring/boot/Rpc2ConsumerAutoConfiguration.java`
- Create: `small-rpc-spring-boot-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Modify: `small-rpc-simple/pom.xml`（modules 加两个新模块为依赖方……实际：small-rpc-simple pom 加 `<module>` 不合适——新模块与 small-rpc 平级，落在仓库根目录，各自独立 pom，不入聚合（T5 才建根聚合）。样例 pom 的依赖由 install 到本地库解决）
- Modify: `small-rpc-simple/small-rpc-sample-springboot-server/.../config/RpcProviderConfig.java`（删 1.x bean 与 2.0 手工 bean，只剩业务）
- Modify: `small-rpc-simple/small-rpc-sample-springboot-client/.../config/RpcInvokerConfig.java`（整个删除）
- Modify: `small-rpc-simple/small-rpc-sample-springboot-client/.../controller/HelloController.java`（单一 @RpcReference 注入，双端点共用）
- Modify: server/client 两个 `application.yml`（键见 Step 5）
- Test: `small-rpc-spring/src/test/java/io/github/upowerman/spring/ReferenceBeanPostProcessorTest.java`

**Interfaces:**
- Consumes: Task 1–3 的 SpiLoader/Spi 注解/`SerializerRegistry.fromSpi()`；P0/P1 的 `RpcProxyFactory`/`TraceFilter`/`FailoverClusterInvoker`/`PullServiceDirectory`/`RemoteInvoker`/`NettyTransport`/`RpcServer`/`ReflectiveInvoker`
- Produces: 注解 `@RpcService`（类级）与 `@RpcReference`（字段级：`loadBalance()` 默认 ""、`timeout()` 默认 3000、`address()` 默认 ""）；starter 自动装配两个 AutoConfiguration

- [ ] **Step 1: 新建两个模块骨架与注解**

`small-rpc-spring/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>io.github.upowerman</groupId>
    <artifactId>small-rpc-spring</artifactId>
    <version>1.0.0</version>

    <properties>
        <maven.compiler.source>1.8</maven.compiler.source>
        <maven.compiler.target>1.8</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <spring.version>5.3.31</spring.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>io.github.upowerman</groupId>
            <artifactId>small-rpc-core</artifactId>
            <version>1.0.0</version>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-context</artifactId>
            <version>${spring.version}</version>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>junit</groupId>
            <artifactId>junit</artifactId>
            <version>4.13.2</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

`small-rpc-spring-boot-starter/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>io.github.upowerman</groupId>
    <artifactId>small-rpc-spring-boot-starter</artifactId>
    <version>1.0.0</version>

    <properties>
        <maven.compiler.source>1.8</maven.compiler.source>
        <maven.compiler.target>1.8</maven.compiler.target>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <spring-boot.version>2.7.18</spring-boot.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>io.github.upowerman</groupId>
            <artifactId>small-rpc-spring</artifactId>
            <version>1.0.0</version>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure</artifactId>
            <version>${spring-boot.version}</version>
            <scope>provided</scope>
        </dependency>
    </dependencies>
</project>
```

注解（`small-rpc-spring/src/main/java/io/github/upowerman/annotation/`）：

```java
// RpcService.java —— 自 small-rpc-core git mv 后删 version() 属性（2.0 无版本路由）
package io.github.upowerman.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 提供方注解：标注实现类，实现类须实现恰好要暴露的业务接口 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Inherited
public @interface RpcService {
}
```

```java
// RpcReference.java —— 重写，删 1.x 枚举属性（NetEnum/SerializeEnum/LoadBalance/version）
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
```

（同时 `git rm small-rpc-core/src/main/java/io/github/upowerman/annotation/`——两个注解搬走，1.x 的引用者会在后续编译中暴露并随之清理；本任务末尾样例已不用 1.x，core 内 1.x 引用者 = RpcSpringInvokerFactory/RpcSpringProviderFactory/RpcReferenceBean/RpcReferenceInvocationHandler，属 Task 5 删除对象，**本任务暂留但在 small-rpc-core 内编译会因 annotation 搬走而断**——因此本步骤同时把这几个类对 `io.github.upowerman.annotation` 的 import 改为指向新位置不可行（core 不能依赖 spring 模块）。**裁定**：把 `annotation/` 包**复制语义**改为「git mv 到 small-rpc-spring + 在 small-rpc-core 里 git rm 之」，core 内四个 1.x 引用类（RpcSpringInvokerFactory、RpcSpringProviderFactory、RpcReferenceBean、RpcReferenceInvocationHandler）**本任务提前 git rm**（它们唯一的生产使用方是样例 1.x bean，本任务同步删除），1.x net/invoker 剩余类不依赖 annotation 即可继续编译。实现者须先 `grep -rln "io.github.upowerman.annotation" small-rpc-core/src/main/java/io/github/upowerman/` 核实恰好上述四文件，再执行删除；发现更多引用者立即 BLOCKED 上报。）

- [ ] **Step 2: 写 ReferenceBeanPostProcessor 失败测试**

`small-rpc-spring/src/test/java/io/github/upowerman/spring/ReferenceBeanPostProcessorTest.java`：

```java
package io.github.upowerman.spring;

import io.github.upowerman.annotation.RpcReference;
import org.junit.Test;
import org.springframework.mock.env.MockEnvironment;  // 不可用——spring-test 未引入；用简易 BeanFactory 桩

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * 处理器装配 2.0 链路：@RpcReference 字段被注入非 null 代理，且代理可直连本地桩 Transport。
 * 不起真 Netty：注入 InMemoryTransport（P0 测试夹具同款语义，本模块内新建桩实现）。
 */
public class ReferenceBeanPostProcessorTest {

    public static class Consumer {
        @RpcReference(address = "localhost:0", timeout = 100)
        private EchoService echoService;
    }

    @Test
    public void rpcReferenceFieldIsInjectedWithWorkingProxy() throws Exception {
        // 桩 Transport：request 恒回 SUCCESS/echo（结果对象按 P0 Result 构造）
        Transport stub = new EchoStubTransport();
        ReferenceBeanPostProcessor processor = new ReferenceBeanPostProcessor(
                stub, new LocalServiceRegistryForTest());
        Consumer consumer = new Consumer();
        processor.injectReferences(consumer);
        Field field = Consumer.class.getDeclaredField("echoService");
        field.setAccessible(true);
        Object proxy = field.get(consumer);
        assertNotNull(proxy);
        assertTrue(field.getType().isInstance(proxy));
    }
}
```

（测试需要的三个支撑类随测试写入：`EchoService`/`EchoStubTransport`/`LocalServiceRegistryForTest`——`EchoStubTransport implements Transport` 的 `request` 返回 `CompletableFuture.completedFuture(Result.success("echo"))`（先读 `core/result` 的 Result 工厂方法名，按实际签名调整）；`LocalServiceRegistryForTest` = `new LocalServiceRegistry()` + `start(param 含 DIRECT_ADDRESS=localhost:0)`——registry-local 未拆出前用现 1.x 包的 LocalServiceRegistry，T5 后改 import。）

- [ ] **Step 3: 实现 ReferenceBeanPostProcessor**

`small-rpc-spring/src/main/java/io/github/upowerman/spring/ReferenceBeanPostProcessor.java`：

```java
package io.github.upowerman.spring;

import io.github.upowerman.annotation.RpcReference;
import io.github.upowerman.core.cluster.FailoverClusterInvoker;
import io.github.upowerman.core.directory.PullServiceDirectory;
import io.github.upowerman.core.filter.Filter;
import io.github.upowerman.core.filter.TraceFilter;
import io.github.upowerman.core.invoker.RemoteInvoker;
import io.github.upowerman.core.loadbalance.LoadBalancer;
import io.github.upowerman.core.proxy.RpcProxyFactory;
import io.github.upowerman.core.spi.SpiLoader;
import io.github.upowerman.core.transport.Transport;
import io.github.upowerman.registry.BaseServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessorAdapter;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Field;
import java.util.Collections;

/**
 * 消费方装配：字段 @RpcReference → 2.0 链路代理。
 * 链路（P1 样例验证过的同一结构）：RpcProxyFactory(TraceFilter)
 *   → FailoverClusterInvoker(retries=1, timeout=注解值)
 *   → PullServiceDirectory(registry) → SPI 选 LoadBalancer
 *   → RemoteInvoker(transport, iface)。
 * 逻辑自 1.x RpcSpringInvokerFactory.postProcessAfterInstantiation 原样搬运，只换链路内核。
 */
public class ReferenceBeanPostProcessor extends InstantiationAwareBeanPostProcessorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(ReferenceBeanPostProcessor.class);

    private final Transport transport;
    private final BaseServiceRegistry registry;

    public ReferenceBeanPostProcessor(Transport transport, BaseServiceRegistry registry) {
        this.transport = transport;
        this.registry = registry;
    }

    @Override
    public boolean postProcessAfterInstantiation(final Object bean, final String beanName) throws BeansException {
        ReflectionUtils.doWithFields(bean.getClass(), new ReflectionUtils.FieldCallback() {
            @Override
            public void doWith(Field field) throws IllegalArgumentException, IllegalAccessException {
                RpcReference reference = field.getAnnotation(RpcReference.class);
                if (reference == null) {
                    return;
                }
                Class<?> iface = field.getType();
                if (!iface.isInterface()) {
                    throw new IllegalStateException("@RpcReference 字段必须是接口: " + field);
                }
                Object proxy = buildProxy(iface, reference);
                field.setAccessible(true);
                field.set(bean, proxy);
                logger.info("rpc2 reference injected: {} -> {}", field, iface.getName());
            }
        });
        return true;
    }

    /** 测试直达入口（不走 Spring 生命周期） */
    void injectReferences(Object bean) throws IllegalAccessException {
        postProcessAfterInstantiation(bean, bean.getClass().getName());
    }

    private Object buildProxy(Class<?> iface, RpcReference reference) {
        PullServiceDirectory directory = new PullServiceDirectory(registry, null);
        LoadBalancer loadBalancer = reference.loadBalance().isEmpty()
                ? SpiLoader.of(LoadBalancer.class).getDefaultExtension()
                : SpiLoader.of(LoadBalancer.class).getExtension(reference.loadBalance());
        RemoteInvoker remoteInvoker = new RemoteInvoker(transport, iface);
        FailoverClusterInvoker cluster = new FailoverClusterInvoker(
                directory, loadBalancer, remoteInvoker, 1, reference.timeout());
        return new RpcProxyFactory<Object>(iface,
                Collections.<Filter>singletonList(new TraceFilter()), cluster).getProxy();
    }
}
```

（实现者注意：`PullServiceDirectory`/`FailoverClusterInvoker` 的构造器签名以现有源码为准——先读再写，参数顺序/数量不符时以源码为准并保持「目录→均衡器→远程→重试→超时」语义。）

- [ ] **Step 4: 实现 starter（properties + 两个 AutoConfiguration + imports 文件）**

`Rpc2Properties.java`：

```java
package io.github.upowerman.spring.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/** small-rpc.* 配置项 */
@ConfigurationProperties(prefix = "small-rpc")
public class Rpc2Properties {

    private Provider provider = new Provider();
    private Registry registry = new Registry();

    /** 负载均衡扩展名（SPI 名），空 = 默认 */
    private String loadBalance = "";

    public static class Provider {
        /** 2.0 RpcServer 监听端口 */
        private int rpc2Port = 7081;
        public int getRpc2Port() { return rpc2Port; }
        public void setRpc2Port(int rpc2Port) { this.rpc2Port = rpc2Port; }
    }

    public static class Registry {
        /** 注册中心扩展名（SPI 名）：local（zk/redis 属 P3） */
        private String type = "local";
        /** 注册中心启动参数（local 直连场景放 DIRECT_ADDRESS） */
        private Map<String, String> param = new HashMap<String, String>();
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public Map<String, String> getParam() { return param; }
        public void setParam(Map<String, String> param) { this.param = param; }
    }

    public Provider getProvider() { return provider; }
    public void setProvider(Provider provider) { this.provider = provider; }
    public Registry getRegistry() { return registry; }
    public void setRegistry(Registry registry) { this.registry = registry; }
    public String getLoadBalance() { return loadBalance; }
    public void setLoadBalance(String loadBalance) { this.loadBalance = loadBalance; }
}
```

`Rpc2ProviderAutoConfiguration.java`：

```java
package io.github.upowerman.spring.boot;

import io.github.upowerman.core.provider.ReflectiveInvoker;
import io.github.upowerman.core.serialize.SerializerRegistry;
import io.github.upowerman.core.server.RpcServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;

import io.github.upowerman.annotation.RpcService;

/**
 * 提供方自动装配：收集 @RpcService bean → ReflectiveInvoker 注册 → 起 RpcServer。
 * 接口解析沿用 1.x RpcSpringProviderFactory 口径：getInterfaces()[0]。
 */
@Configuration
@ConditionalOnClass(RpcServer.class)
@ConditionalOnMissingBean(RpcServer.class)
@EnableConfigurationProperties(Rpc2Properties.class)
public class Rpc2ProviderAutoConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(Rpc2ProviderAutoConfiguration.class);

    @Bean(destroyMethod = "shutdown")
    public RpcServer rpc2Server(ApplicationContext applicationContext, Rpc2Properties properties,
                                SerializerRegistry serializerRegistry) throws InterruptedException {
        RpcServer server = new RpcServer(properties.getProvider().getRpc2Port(), serializerRegistry);
        int registered = 0;
        for (Object serviceBean : applicationContext.getBeansWithAnnotation(RpcService.class).values()) {
            Class<?>[] interfaces = serviceBean.getClass().getInterfaces();
            if (interfaces.length == 0) {
                throw new IllegalStateException("@RpcService 服务必须实现接口: "
                        + serviceBean.getClass().getName());
            }
            server.register(interfaces[0].getName(), new ReflectiveInvoker(interfaces[0], serviceBean));
            registered++;
        }
        server.start();
        logger.info("rpc2 provider started on port {} with {} services",
                properties.getProvider().getRpc2Port(), registered);
        return server;
    }
}
```

`Rpc2ConsumerAutoConfiguration.java`：

```java
package io.github.upowerman.spring.boot;

import io.github.upowerman.core.serialize.Serializer;
import io.github.upowerman.core.serialize.SerializerRegistry;
import io.github.upowerman.core.spi.SpiLoader;
import io.github.upowerman.core.transport.NettyTransport;
import io.github.upowerman.registry.BaseServiceRegistry;
import io.github.upowerman.spring.ReferenceBeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 消费方自动装配：SPI 取注册中心与序列化 → NettyTransport → ReferenceBeanPostProcessor。
 */
@Configuration
@ConditionalOnClass(NettyTransport.class)
@EnableConfigurationProperties(Rpc2Properties.class)
public class Rpc2ConsumerAutoConfiguration {

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean(NettyTransport.class)
    public NettyTransport nettyTransport() {
        Serializer serializer = SpiLoader.of(Serializer.class).getDefaultExtension();
        return new NettyTransport(serializer);
    }

    @Bean(destroyMethod = "stop")
    public BaseServiceRegistry rpc2Registry(Rpc2Properties properties) {
        BaseServiceRegistry registry = SpiLoader.of(BaseServiceRegistry.class)
                .getExtension(properties.getRegistry().getType());
        registry.start(properties.getRegistry().getParam());
        return registry;
    }

    @Bean
    public SerializerRegistry serializerRegistry() {
        return SerializerRegistry.fromSpi();
    }

    @Bean
    public ReferenceBeanPostProcessor referenceBeanPostProcessor(NettyTransport nettyTransport,
                                                                 BaseServiceRegistry rpc2Registry) {
        return new ReferenceBeanPostProcessor(nettyTransport, rpc2Registry);
    }
}
```

（实现者注意：`NettyTransport` 构造器签名、`BaseServiceRegistry.stop()` 是否存在、`registry` 的 SPI 接入——BaseServiceRegistry 是 abstract class，`SpiLoader.of` 拒绝非接口。**裁定落地**：本步把 `BaseServiceRegistry` 从 abstract class 改为 interface（`io.github.upowerman.registry.BaseServiceRegistry`，方法签名不变、去掉 abstract 关键字），`LocalServiceRegistry` 加 `implements`——这是最小改造让 registry 走 SPI；类迁移到 core.registry 包留 T5。）

`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`：

```
io.github.upowerman.spring.boot.Rpc2ProviderAutoConfiguration
io.github.upowerman.spring.boot.Rpc2ConsumerAutoConfiguration
```

- [ ] **Step 5: 样例纯 2.0 改造**

server：
- `RpcProviderConfig.java`：删 `rpc2Server(...)` bean、`rpcSpringProviderFactory()` bean、`@Value rpc2Port` 字段与相关 import——starter 已接管；文件里若只剩 1.x 内容则整个删除（1.x provider bean = RpcSpringProviderFactory，删除后 1.x 链路 7080 不再启动）
- `application.yml`：`small-rpc.provider` 段删 `port: 7080`；保留/改为：

```yaml
small-rpc:
  provider:
    rpc2-port: 7081
```

client：
- 删除 `RpcInvokerConfig.java` 整个文件（1.x registry/invoker bean + 2.0 手工链路全部由 starter 接管）
- `HelloController.java`：只保留一个 `@RpcReference private HelloService helloService;` 字段；`/rpc2/hello` 端点改为与 `/hello` 共用该字段（或直接删除 /rpc2/hello 映射——保留两个端点但都走 2.0 代理，便于回归对比）
- `application.yml`：改为：

```yaml
small-rpc:
  registry:
    type: local
    param:
      DIRECT_ADDRESS: localhost:7081
  loadbalance: random
```

（删 `small-rpc.rpc2.address`、`small-rpc.registry.address` 等 1.x/P1 旧键。）

- [ ] **Step 6: 构建 + 全量回归**

Run: `mvn -f small-rpc-core/pom.xml install -DskipTests -Dgpg.skip=true -Dmaven.javadoc.skip=true`
Run: `mvn -f small-rpc-spring/pom.xml test -q`（新模块测试）
Run: `mvn -f small-rpc-spring-boot-starter/pom.xml install -DskipTests -Dgpg.skip=true -Dmaven.javadoc.skip=true`
Run: `mvn -f small-rpc-core/pom.xml test -q`
Run: `mvn -f small-rpc-simple/pom.xml test`
Expected: 全部 BUILD SUCCESS；core 全量 PASS（118）

- [ ] **Step 7: Commit**

```bash
git add small-rpc-spring small-rpc-spring-boot-starter small-rpc-simple small-rpc-core/src/main/java/io/github/upowerman/annotation
git commit -m "feat(rpc2): rpc-spring + starter auto-config, sample goes pure-2.0 via SPI wiring"
```

---

### Task 5: 拆多模块 + 1.x 删除 + HessianSerializer 重写

**Files:**
- Create: `pom.xml`（仓库根聚合 pom）
- Rename: `small-rpc-core/` → `rpc-core/`（git mv，pom artifactId 同步改）
- Rename: `small-rpc-simple/` → `rpc-examples/`（git mv；子模块目录名与 artifactId 保留 `small-rpc-sample-*` 以减小 diff，或一并改 `rpc-example-*`——**裁定：目录与 artifactId 一并改名** `rpc-example-api`/`rpc-example-client`/`rpc-example-server`，一次到位）
- Create: `rpc-transport-netty/`（Netty 相关类自 rpc-core 迁出 + HessianSerializer 重写）
- Create: `rpc-registry-local/`（LocalServiceRegistry 迁入 + BaseServiceRegistry 迁往 rpc-core 后的 implements 修正）
- Delete: rpc-core 内 1.x 全部（清单见 Step 2）
- Move: `io.github.upowerman.registry.BaseServiceRegistry` → `rpc-core/src/main/java/io/github/upowerman/core/registry/BaseServiceRegistry.java`（包名迁移 + 已是 interface）
- Move: `io.github.upowerman.registry.impl.LocalServiceRegistry` → `rpc-registry-local/src/main/java/io/github/upowerman/core/registry/local/LocalServiceRegistry.java`（包名迁移 + 删 spring StringUtils 依赖）

**Interfaces:**
- Consumes: Task 1–4 全部；P1 全部 2.0 代码
- Produces: 根聚合 `mvn test -q` 可构建 6 模块（rpc-core / rpc-transport-netty / rpc-registry-local / small-rpc-spring / small-rpc-spring-boot-starter / rpc-examples）；模块名与 spec §5 布局一致（zk/redis 两模块 P3 补）

- [ ] **Step 1: 删除 1.x（先 grep 核实引用面，再 git rm）**

核实命令：

```bash
grep -rln "io.github.upowerman\.\(net\|invoker\|provider\|serialize\.[A-Z]\|registry\.\(Base\|impl\)\)" \
  rpc-core/src/main/java/io/github/upowerman/core/ | grep -v "/adapter/"
```

（Task 4 后预期只剩 `core/directory/PullServiceDirectory.java` 引用 BaseServiceRegistry。）

git rm 清单（rpc-core 内）：

```bash
git rm -r rpc-core/src/main/java/io/github/upowerman/net \
          rpc-core/src/main/java/io/github/upowerman/invoker \
          rpc-core/src/main/java/io/github/upowerman/provider \
          rpc-core/src/main/java/io/github/upowerman/serialize \
          rpc-core/src/main/java/io/github/upowerman/core/adapter
# registry 包在 BaseServiceRegistry/LocalServiceRegistry 迁出后整体删除
```

1.x 测试删除（git rm，逐个核实其测试对象确属 1.x）：

```bash
git rm rpc-core/src/test/java/io/github/upowerman/RedisServiceRegistryIntegrationCheck.java \
       rpc-core/src/test/java/io/github/upowerman/RedisServiceRegistryTest.java \
       rpc-core/src/test/java/io/github/upowerman/ZookeeperServiceRegistryTest.java \
       rpc-core/src/test/java/io/github/upowerman/LegacyConnectionTest.java \
       rpc-core/src/test/java/io/github/upowerman/LegacyHessianSerializerTest.java \
       rpc-core/src/test/java/io/github/upowerman/LegacyNettyTransportIntegrationTest.java
```

（另有 1.x 链路测试若存在——如测试 1.x NettyServer/RpcInvokerFactory 的类——按「测试对象已被删」原则一并 git rm；实现者用 `grep -rln "io.github.upowerman.net\|io.github.upowerman.invoker\|LegacyHessian\|adapter\." rpc-core/src/test/java/` 列出后核对，拿不准的 BLOCKED 上报。util/ 包：`grep -rln "io.github.upowerman.util" rpc-core/src/main/java rpc-core/src/test/java`——若零引用则整体 git rm，有引用则保留被引用文件并 BLOCKED 上报清单。）

- [ ] **Step 2: HessianSerializer 重写（rpc-core 内先重写再搬）**

Create `rpc-core/.../core/serialize/HessianSerializer.java`：

```java
package io.github.upowerman.core.serialize;

import com.caucho.hessian.io.HessianInput;
import com.caucho.hessian.io.HessianOutput;
import io.github.upowerman.exception.RpcException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Hessian 序列化（typeId=1，协议帧 codec 字段）。
 * 基于 HessianInput/HessianOutput 直写，不再委托 1.x。
 * 反序列化沿用既定契约：忽略 clazz 参数，类型防线在调用方强制转型
 * （ServerHandler/ResponseHandler 的 (RpcRequestBody) 转型）。
 */
public class HessianSerializer implements Serializer {

    /** Hessian 的算法 ID（约定值，进协议帧 codec 字段） */
    public static final byte TYPE_ID = 1;

    @Override
    public byte typeId() {
        return TYPE_ID;
    }

    @Override
    public byte[] serialize(Object obj) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        HessianOutput output = new HessianOutput(buffer);
        try {
            output.writeObject(obj);
            output.flush();
            return buffer.toByteArray();
        } catch (IOException e) {
            throw new RpcException("hessian serialize failed", e);
        }
    }

    @Override
    public Object deserialize(byte[] bytes, Class<?> clazz) {
        HessianInput input = new HessianInput(new ByteArrayInputStream(bytes));
        try {
            return input.readObject();
        } catch (IOException e) {
            throw new RpcException("hessian deserialize failed", e);
        }
    }
}
```

（先读 `RpcException` 构造器签名按实调整。`LegacyHessianSerializer` git rm；`META-INF/small-rpc/io.github.upowerman.core.serialize.Serializer` 行改为 `hessian=io.github.upowerman.core.serialize.HessianSerializer`；引用 LegacyHessianSerializer 的测试/样例 import 同步改 HessianSerializer。）

- [ ] **Step 3: BaseServiceRegistry 迁包 + LocalServiceRegistry 迁模块**

- `git mv rpc-core/src/main/java/io/github/upowerman/registry/BaseServiceRegistry.java rpc-core/src/main/java/io/github/upowerman/core/registry/BaseServiceRegistry.java`，包名改 `io.github.upowerman.core.registry`
- `git mv` LocalServiceRegistry 至 `rpc-registry-local/src/main/java/io/github/upowerman/core/registry/local/LocalServiceRegistry.java`，包名 `io.github.upowerman.core.registry.local`，implements 改为新包名；**删 `org.springframework.util.StringUtils` import**，`StringUtils.isEmpty(address)` 改 `address == null || address.isEmpty()`
- 登记文件改名+改行：`META-INF/small-rpc/io.github.upowerman.registry.BaseServiceRegistry` → `META-INF/small-rpc/io.github.upowerman.core.registry.BaseServiceRegistry`，行 `local=io.github.upowerman.core.registry.local.LocalServiceRegistry`（文件迁入 rpc-registry-local resources）
- 全仓 import 修正：`PullServiceDirectory`、Task 4 starter 的 `ReferenceBeanPostProcessor`/`Rpc2ConsumerAutoConfiguration`、样例残留引用、既有测试（LocalServiceRegistryTest、PullServiceDirectoryTest 等）

- [ ] **Step 4: 拆 transport-netty（git mv 保持历史）**

从 rpc-core 迁出至 `rpc-transport-netty/src/main/java/...`（包名不变）：

```bash
git mv rpc-core/src/main/java/io/github/upowerman/core/protocol rpc-transport-netty/src/main/java/io/github/upowerman/core/protocol
git mv rpc-core/src/main/java/io/github/upowerman/core/server rpc-transport-netty/src/main/java/io/github/upowerman/core/server
git mv rpc-core/src/main/java/io/github/upowerman/core/transport rpc-transport-netty/src/main/java/io/github/upowerman/core/transport
```

（**裁定**：`core/transport` 里纯接口 `Transport/Connection/Endpoint` 留 rpc-core、`NettyTransport/NettyConnection` 迁走——实现者先 `ls core/transport`，按文件逐个 git mv：接口三件套留 core，Netty 实现迁出；`protocol/` 全包迁走（Frame 模型随用方走，rpc-core 无协议依赖需求）；`HessianSerializer` 与 `META-INF/...Serializer` 登记文件随迁。对应测试同路径迁移：FrameCodecTest/ServerHandlerTest/NettyClientHandlersTest/NettyProtocolEndToEndTest/PendingRequestsTest 等按所属类跟迁。）

`rpc-transport-netty/pom.xml`：依赖 rpc-core + netty-all + hessian + slf4j + junit(test)。

- [ ] **Step 5: 改名两个既有模块 + 根聚合 pom**

```bash
git mv small-rpc-core rpc-core && git mv small-rpc-simple rpc-examples
git mv rpc-examples/small-rpc-sample-springboot-api rpc-examples/rpc-example-api
git mv rpc-examples/small-rpc-sample-springboot-client rpc-examples/rpc-example-client
git mv rpc-examples/small-rpc-sample-springboot-server rpc-examples/rpc-example-server
```

- 各 pom 的 artifactId 同步改名（rpc-core / rpc-example-api / rpc-example-client / rpc-example-server）；样例 pom 与 starter/spring pom 的依赖坐标 `small-rpc-core` → `rpc-core`；`rpc-example-*` 增加依赖：`rpc-transport-netty`、`rpc-registry-local`、`small-rpc-spring-boot-starter`（server 与 client 都要）
- rpc-core pom 依赖裁剪：删 netty/hessian/jedis/curator/spring-*，只留 slf4j-api + junit(test)
- 新建根聚合 `pom.xml`（packaging=pom，modules 按依赖序列出 6 模块，`<dependencyManagement>` 可省——各子 pom 自带版本）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>io.github.upowerman</groupId>
    <artifactId>small-rpc</artifactId>
    <version>1.0.0</version>
    <packaging>pom</packaging>

    <modules>
        <module>rpc-core</module>
        <module>rpc-transport-netty</module>
        <module>rpc-registry-local</module>
        <module>small-rpc-spring</module>
        <module>small-rpc-spring-boot-starter</module>
        <module>rpc-examples</module>
    </modules>
</project>
```

- [ ] **Step 6: 全仓 grep 清零验证 + 全量构建**

```bash
# 1.x 零残留
grep -rn "io.github.upowerman\.\(net\|invoker\|provider\)\." rpc-*/ small-rpc-*/ --include="*.java" | grep -v "core/invoker\|core/provider" || echo CLEAN
grep -rn "core\.adapter\|LegacyHessian\|LegacyConnection\|LegacyNettyTransport" rpc-*/ small-rpc-*/ --include="*.java" || echo CLEAN
# core 无违禁依赖
grep -n "netty\|hessian\|spring\|jedis\|curator" rpc-core/pom.xml || echo CLEAN
```

Run: `mvn test -q -Dgpg.skip=true`（根聚合，6 模块 reactor）
Expected: BUILD SUCCESS，全量测试 = 118 − 1.x 已删测试数 + 迁移后原样数量（迁移不减测试）；若样例测试随模块迁移，数字在报告中列明细

- [ ] **Step 7: Commit**

```bash
git add -u && git add pom.xml rpc-transport-netty rpc-registry-local
git commit -m "refactor(rpc2): multi-module split — rpc-core slf4j-only, transport-netty/registry-local/examples extracted, 1.x removed"
```

（`git add -u` 在本任务为受控使用——只暂存已知改名/删除/修改；实现者须先 `git status` 核对无 mise.toml 等意外文件再执行。）

---

### Task 6: 全量回归 + 样例手动验收 + 收尾

**Files:**
- Modify: `docs/superpowers/plans/2026-09-24-small-rpc-2-phase2.md`（勾选 + 实际输出记录）
- 无生产代码改动（除非回归暴露缺陷——修复计入本任务，DEVIATION 醒目标注）

- [ ] **Step 1: 根聚合全量回归**

Run: `mvn test -q -Dgpg.skip=true`
Expected: 6 模块全绿；记录总测试数

- [ ] **Step 2: 样例手动验收（双应用，纯 2.0）**

```bash
mvn -f rpc-examples/rpc-example-server spring-boot:run   # 终端 1：2.0 provider 7081
mvn -f rpc-examples/rpc-example-client spring-boot:run  # 终端 2：HTTP 8091
```

验证（由控制器内联执行，实现者不跑本步）：

```bash
curl -s 'http://127.0.0.1:8091/hello?name=p2'
```

Expected: 200 且 JSON 正确；server 日志 `rpc2 provider started on port 7081`（或含 7081 的启动行）；两端零 ERROR/Exception；**7080 端口不再监听**（`lsof -i :7080` 为空）；验收后停应用、确认 7081/8090/8091 释放

- [ ] **Step 3: SPI 切换可观测验收（单测已钉，此处仅确认）**

Run: `mvn -f rpc-core/pom.xml test -q -Dtest='SpiLoaderTest,SpiInjectTest,SpiAdaptiveTest,SerializerRegistrySpiTest'`
Expected: PASS——loadbalance=hessian/registry 的 SPI 装配与 attachments lb 分发均被测试钉住

- [ ] **Step 4: 验收清单勾选 + Commit**

对照 P2 验收清单（本计划末节）逐项勾选，commit：

```bash
git add docs/superpowers/plans/2026-09-24-small-rpc-2-phase2.md
git commit -m "docs(rpc2): check off P2 plan — multi-module + SPI + pure-2.0 sample accepted"
```

---

## P2 验收清单（对照 spec 补篇 §5）

- [ ] 根聚合 pom + 6 模块（rpc-core/transport-netty/registry-local/spring/starter/examples），`mvn test -q` 全绿
- [ ] rpc-core 只依赖 slf4j-api（+junit test）——pom grep 无 netty/hessian/spring/jedis/curator
- [ ] 1.x 零残留：`io.github.upowerman.{net,invoker,provider,serialize(1.x),annotation(旧位置),registry(旧包)}` 与 `core/adapter/` 不复存在
- [ ] 自研 SPI 三特性落地并有测试：按名查找（登记解析/单例/默认/大声失败）、@SpiInject（含环依赖大声失败）、Adaptive（attachments 分发/缺省回退/无 Invocation 参数拒绝）
- [ ] SPI 接入点：LoadBalancer/Serializer/Registry 走 SpiLoader；`SerializerRegistry.fromSpi()` 装配；1.x 硬编码枚举（NetEnum/SerializeEnum）随 1.x 删除
- [ ] HessianSerializer 基于 HessianInput/HessianOutput 直写（typeId=1 不变，反序列化忽略 clazz 的既定契约保留），LegacyHessianSerializer 删除
- [ ] rpc-spring：@RpcService/@RpcReference（新属性）+ ReferenceBeanPostProcessor 装配 2.0 链路
- [ ] starter 自动装配：provider 端 RpcServer+服务注册、consumer 端 transport+registry+reference 注入，yml `small-rpc.*` 驱动
- [ ] 样例纯 2.0：/hello 经 2.0 链路 curl 200；7080 不再监听；零 ERROR
- [ ] feature/rpc2 不合并不 push；本计划与 spec 补篇已提交
