# small-rpc 2.0 Phase 0 实现计划：核心调用链分层

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 `small-rpc-core` 单模块内建立 2.0 新接口体系（Invocation → Filter → ClusterInvoker → ServiceDirectory → LoadBalancer → Invoker → Transport），用适配器包住 1.x 实现，让新调用链先跑通（内存 Transport 单测 + 真 Netty 集成测试 + 样例接入）。

**Architecture:** 新代码全部放在新包根 `io.github.upowerman.core` 下，与 1.x 包并存、互不修改；通过适配器（PullServiceDirectory、LegacyNettyTransport、LegacyHessianSerializer）复用 1.x 的注册中心、Netty 客户端和 Hessian 序列化。全链路异步签名 `CompletableFuture<Result>`，超时用自写 `Futures.withTimeout`（Java 8 没有 `orTimeout`）。

**Tech Stack:** Java 8、Netty 4.1、JUnit 4.13.2、Maven（单模块 small-rpc-core）。

**Spec:** `docs/superpowers/specs/2026-09-23-small-rpc-2-design.md`（§1 核心调用链、§7 P0 行）

## Global Constraints

- **Java 8 语法**（pom source/target 1.8）：禁用 `orTimeout`/`completeOnTimeout`（Java 9+）、`var`、`List.of`、`Map.of`；超时一律用本计划的 `Futures.withTimeout`。
- **JUnit 4.13.2**：测试写法用 `org.junit.Test` + `org.junit.Assert.*`（沿用 1.x 测试风格），不要用 JUnit 5。
- **测试命令**：core 模块用 `mvn -f small-rpc-core/pom.xml test -q`（工程无根聚合 pom，`-pl` 不可用）。**禁止 `mvn verify`**：pom 里 GPG 插件绑定在 verify 阶段，本机无密钥会失败。
- **分支**：所有工作在 `feature/rpc2` 分支；master 保持 1.x 不动。
- **包边界**：新代码包根 `io.github.upowerman.core`；`core` 包内禁止 import Spring 类型；只有适配器任务允许 import 1.x 类（`io.github.upowerman.net.*`、`io.github.upowerman.registry.*`、`io.github.upowerman.serialize.*`、`io.github.upowerman.provider.RpcProviderFactory`、`io.github.upowerman.exception.RpcException`）。
- **1.x 不动**：不修改 `io.github.upowerman`（非 `core`）下的任何已有类；已有测试必须保持通过。
- 每个 Task 结束时提交一次 git commit（消息格式见各任务最后一步）。

---

### Task 1: Result 状态模型（Status / Result / DefaultResult）

**Files:**
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/result/Status.java`
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/result/Result.java`
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/result/DefaultResult.java`
- Test: `small-rpc-core/src/test/java/io/github/upowerman/core/result/DefaultResultTest.java`

**Interfaces:**
- Consumes: 无（地基）
- Produces: `enum Status { SUCCESS, TIMEOUT, SERVICE_NOT_FOUND, METHOD_NOT_FOUND, SERIALIZATION_ERROR, SERVER_ERROR, NETWORK_ERROR }`；`interface Result { Status status(); Object value(); Throwable exception(); }`；静态工厂 `DefaultResult.success(Object)` / `DefaultResult.failure(Status)` / `DefaultResult.failure(Status, Throwable)`

- [ ] **Step 1: 写失败测试**

```java
package io.github.upowerman.core.result;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class DefaultResultTest {

    @Test
    public void successCarriesValue() {
        Result result = DefaultResult.success("hello");
        assertSame(Status.SUCCESS, result.status());
        assertEquals("hello", result.value());
        assertNull(result.exception());
    }

    @Test
    public void failureCarriesStatusAndException() {
        RuntimeException ex = new RuntimeException("boom");
        Result result = DefaultResult.failure(Status.TIMEOUT, ex);
        assertSame(Status.TIMEOUT, result.status());
        assertNull(result.value());
        assertSame(ex, result.exception());
    }

    @Test
    public void failureWithoutException() {
        Result result = DefaultResult.failure(Status.SERVICE_NOT_FOUND);
        assertSame(Status.SERVICE_NOT_FOUND, result.status());
        assertNull(result.value());
        assertNull(result.exception());
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -f small-rpc-core/pom.xml test -q -Dtest=DefaultResultTest`
Expected: 编译失败（`DefaultResult`/`Status`/`Result` 不存在）

- [ ] **Step 3: 最小实现**

`Status.java`:

```java
package io.github.upowerman.core.result;

/**
 * 调用结果状态码，集群容错层据此决策重试/熔断
 */
public enum Status {
    /** 成功 */
    SUCCESS,
    /** 调用超时 */
    TIMEOUT,
    /** 服务没有可用实例 */
    SERVICE_NOT_FOUND,
    /** 方法不存在 */
    METHOD_NOT_FOUND,
    /** 序列化/反序列化失败 */
    SERIALIZATION_ERROR,
    /** 服务端执行出错 */
    SERVER_ERROR,
    /** 网络错误 */
    NETWORK_ERROR
}
```

`Result.java`:

```java
package io.github.upowerman.core.result;

/**
 * 调用结果：返回值 + 状态码 + 异常，取代 1.x 的 errorMsg 字符串
 */
public interface Result {

    Status status();

    Object value();

    Throwable exception();
}
```

`DefaultResult.java`:

```java
package io.github.upowerman.core.result;

/**
 * Result 默认实现
 */
public final class DefaultResult implements Result {

    private final Status status;
    private final Object value;
    private final Throwable exception;

    private DefaultResult(Status status, Object value, Throwable exception) {
        this.status = status;
        this.value = value;
        this.exception = exception;
    }

    public static DefaultResult success(Object value) {
        return new DefaultResult(Status.SUCCESS, value, null);
    }

    public static DefaultResult failure(Status status) {
        return new DefaultResult(status, null, null);
    }

    public static DefaultResult failure(Status status, Throwable exception) {
        return new DefaultResult(status, null, exception);
    }

    @Override
    public Status status() {
        return status;
    }

    @Override
    public Object value() {
        return value;
    }

    @Override
    public Throwable exception() {
        return exception;
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -f small-rpc-core/pom.xml test -q -Dtest=DefaultResultTest`
Expected: PASS（3 个测试）

- [ ] **Step 5: 创建分支并提交**

```bash
git checkout -b feature/rpc2
git add small-rpc-core/src/main/java/io/github/upowerman/core/result small-rpc-core/src/test/java/io/github/upowerman/core/result
git commit -m "feat(rpc2): add Status/Result model"
```

---

### Task 2: Invocation 模型 + RpcConstants

**Files:**
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/RpcConstants.java`
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/invocation/Invocation.java`
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/invocation/GenericInvocation.java`
- Test: `small-rpc-core/src/test/java/io/github/upowerman/core/invocation/GenericInvocationTest.java`

**Interfaces:**
- Consumes: 无
- Produces: `interface Invocation { String serviceName(); String methodName(); Class<?>[] parameterTypes(); Object[] arguments(); Map<String,Object> attachments(); }`；`new GenericInvocation(String serviceName, String methodName, Class<?>[] parameterTypes, Object[] arguments, Map<String,Object> attachments)`；常量 `RpcConstants.ATTACH_ADDRESS = "rpc.address"`、`RpcConstants.ATTACH_TIMEOUT = "rpc.timeout"`、`RpcConstants.ATTACH_TRACE_ID = "traceId"`

- [ ] **Step 1: 写失败测试**

```java
package io.github.upowerman.core.invocation;

import io.github.upowerman.core.RpcConstants;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class GenericInvocationTest {

    @Test
    public void carriesAllFields() {
        Map<String, Object> attachments = new HashMap<>();
        attachments.put(RpcConstants.ATTACH_ADDRESS, "127.0.0.1:7080");
        GenericInvocation invocation = new GenericInvocation(
                "com.test.EchoService", "echo", new Class<?>[]{String.class},
                new Object[]{"x"}, attachments);

        assertEquals("com.test.EchoService", invocation.serviceName());
        assertEquals("echo", invocation.methodName());
        assertSame(attachments, invocation.attachments());
        assertEquals(1, invocation.parameterTypes().length);
        assertEquals("x", invocation.arguments()[0]);
        assertEquals("127.0.0.1:7080",
                invocation.attachments().get(RpcConstants.ATTACH_ADDRESS));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -f small-rpc-core/pom.xml test -q -Dtest=GenericInvocationTest`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 最小实现**

`RpcConstants.java`:

```java
package io.github.upowerman.core;

/**
 * RPC 框架级常量
 */
public final class RpcConstants {

    /** attachments 中存放目标实例地址的 key，由 ClusterInvoker 写入、远程 Invoker 读取 */
    public static final String ATTACH_ADDRESS = "rpc.address";

    /** attachments 中存放单次调用超时毫秒数的 key */
    public static final String ATTACH_TIMEOUT = "rpc.timeout";

    /** attachments 中存放 traceId 的 key */
    public static final String ATTACH_TRACE_ID = "traceId";

    private RpcConstants() {
    }
}
```

`Invocation.java`:

```java
package io.github.upowerman.core.invocation;

import java.util.Map;

/**
 * 一次调用的不变描述：服务、方法、参数、附加属性
 */
public interface Invocation {

    /** 接口全限定名 */
    String serviceName();

    String methodName();

    Class<?>[] parameterTypes();

    Object[] arguments();

    /** 横切信息：traceId、超时、目标地址等 */
    Map<String, Object> attachments();
}
```

`GenericInvocation.java`:

```java
package io.github.upowerman.core.invocation;

import java.util.Map;

/**
 * Invocation 默认实现
 */
public class GenericInvocation implements Invocation {

    private final String serviceName;
    private final String methodName;
    private final Class<?>[] parameterTypes;
    private final Object[] arguments;
    private final Map<String, Object> attachments;

    public GenericInvocation(String serviceName, String methodName,
                             Class<?>[] parameterTypes, Object[] arguments,
                             Map<String, Object> attachments) {
        this.serviceName = serviceName;
        this.methodName = methodName;
        this.parameterTypes = parameterTypes;
        this.arguments = arguments;
        this.attachments = attachments;
    }

    @Override
    public String serviceName() {
        return serviceName;
    }

    @Override
    public String methodName() {
        return methodName;
    }

    @Override
    public Class<?>[] parameterTypes() {
        return parameterTypes;
    }

    @Override
    public Object[] arguments() {
        return arguments;
    }

    @Override
    public Map<String, Object> attachments() {
        return attachments;
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -f small-rpc-core/pom.xml test -q -Dtest=GenericInvocationTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add small-rpc-core/src/main/java/io/github/upowerman/core small-rpc-core/src/test/java/io/github/upowerman/core/invocation
git commit -m "feat(rpc2): add Invocation model"
```

---

### Task 3: ServiceInstance / ServiceDirectory / PullServiceDirectory 适配器

**Files:**
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/directory/ServiceInstance.java`
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/directory/ServiceDirectory.java`
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/directory/PullServiceDirectory.java`
- Test: `small-rpc-core/src/test/java/io/github/upowerman/core/directory/PullServiceDirectoryTest.java`

**Interfaces:**
- Consumes: 1.x `BaseServiceRegistry.discovery(String key)` 返回 `TreeSet<String>`；1.x `RpcProviderFactory.makeServiceKey(String iface, String version)`；1.x `LocalServiceRegistry`（`start(Map)` + `DIRECT_ADDRESS`）
- Produces: `class ServiceInstance { String getAddress(); int getWeight(); long getStartTime(); new ServiceInstance(String address) }`；`interface ServiceDirectory { List<ServiceInstance> list(String service); void subscribe(String service); }`；`new PullServiceDirectory(BaseServiceRegistry registry, String version)`

- [ ] **Step 1: 写失败测试**

```java
package io.github.upowerman.core.directory;

import io.github.upowerman.registry.impl.LocalServiceRegistry;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PullServiceDirectoryTest {

    @Test
    public void listsInstancesFromRegistry() {
        LocalServiceRegistry registry = new LocalServiceRegistry();
        Map<String, String> param = new HashMap<>();
        param.put(LocalServiceRegistry.DIRECT_ADDRESS, "127.0.0.1:7080");
        registry.start(param);

        PullServiceDirectory directory = new PullServiceDirectory(registry, null);
        List<ServiceInstance> instances = directory.list("com.test.EchoService");

        assertEquals(1, instances.size());
        assertEquals("127.0.0.1:7080", instances.get(0).getAddress());
        registry.stop();
    }

    @Test
    public void emptyWhenRegistryHasNoAddress() {
        LocalServiceRegistry registry = new LocalServiceRegistry();
        registry.start(new HashMap<String, String>());

        PullServiceDirectory directory = new PullServiceDirectory(registry, "1.0");
        assertTrue(directory.list("com.test.EchoService").isEmpty());
        registry.stop();
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -f small-rpc-core/pom.xml test -q -Dtest=PullServiceDirectoryTest`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 最小实现**

`ServiceInstance.java`:

```java
package io.github.upowerman.core.directory;

/**
 * 服务实例：P0 只有地址，weight/startTime 为后续负载均衡与活跃数统计预留
 */
public final class ServiceInstance {

    private final String address;
    private final int weight;
    private final long startTime;

    public ServiceInstance(String address) {
        this(address, 1, System.currentTimeMillis());
    }

    public ServiceInstance(String address, int weight, long startTime) {
        this.address = address;
        this.weight = weight;
        this.startTime = startTime;
    }

    public String getAddress() {
        return address;
    }

    public int getWeight() {
        return weight;
    }

    public long getStartTime() {
        return startTime;
    }

    @Override
    public String toString() {
        return "ServiceInstance{" + address + ", weight=" + weight + "}";
    }
}
```

`ServiceDirectory.java`:

```java
package io.github.upowerman.core.directory;

import java.util.List;

/**
 * 消费者的本地服务视图，调用热路径只经过它，不直接访问注册中心。
 * P0 由适配器每次拉取（等价 1.x 行为）；P3 接入订阅推送 + 缓存降级。
 */
public interface ServiceDirectory {

    List<ServiceInstance> list(String service);

    /** 订阅服务变更。P0 适配器为空实现。 */
    void subscribe(String service);
}
```

`PullServiceDirectory.java`:

```java
package io.github.upowerman.core.directory;

import io.github.upowerman.provider.RpcProviderFactory;
import io.github.upowerman.registry.BaseServiceRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/**
 * P0 适配器：包装 1.x BaseServiceRegistry 的 discovery 拉取，
 * 把 1.x 的 serviceKey(address 集合) 转成 List&lt;ServiceInstance&gt;
 */
public class PullServiceDirectory implements ServiceDirectory {

    private final BaseServiceRegistry registry;
    private final String version;

    public PullServiceDirectory(BaseServiceRegistry registry, String version) {
        this.registry = registry;
        this.version = version;
    }

    @Override
    public List<ServiceInstance> list(String service) {
        if (registry == null) {
            return Collections.emptyList();
        }
        String serviceKey = RpcProviderFactory.makeServiceKey(service, version);
        TreeSet<String> addresses = registry.discovery(serviceKey);
        if (addresses == null || addresses.isEmpty()) {
            return Collections.emptyList();
        }
        List<ServiceInstance> instances = new ArrayList<ServiceInstance>(addresses.size());
        for (String address : addresses) {
            instances.add(new ServiceInstance(address));
        }
        return instances;
    }

    @Override
    public void subscribe(String service) {
        // P0 无推送能力，P3 由各注册中心实现变更通知
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -f small-rpc-core/pom.xml test -q -Dtest=PullServiceDirectoryTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add small-rpc-core/src/main/java/io/github/upowerman/core/directory small-rpc-core/src/test/java/io/github/upowerman/core/directory
git commit -m "feat(rpc2): add ServiceDirectory with 1.x registry adapter"
```

---

### Task 4: LoadBalancer（Random / RoundRobin）

**Files:**
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/loadbalance/LoadBalancer.java`
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/loadbalance/RandomLoadBalancer.java`
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/loadbalance/RoundRobinLoadBalancer.java`
- Test: `small-rpc-core/src/test/java/io/github/upowerman/core/loadbalance/LoadBalancerTest.java`

**Interfaces:**
- Consumes: `ServiceInstance.getAddress()`（Task 3）、`Invocation`（Task 2）
- Produces: `interface LoadBalancer { ServiceInstance select(List<ServiceInstance> instances, Invocation invocation); }`；`new RandomLoadBalancer()`；`new RoundRobinLoadBalancer()`

- [ ] **Step 1: 写失败测试**

```java
package io.github.upowerman.core.loadbalance;

import io.github.upowerman.core.directory.ServiceInstance;
import io.github.upowerman.core.invocation.GenericInvocation;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class LoadBalancerTest {

    private final List<ServiceInstance> instances = Arrays.asList(
            new ServiceInstance("127.0.0.1:1"),
            new ServiceInstance("127.0.0.1:2"),
            new ServiceInstance("127.0.0.1:3"));

    private GenericInvocation invocation() {
        return new GenericInvocation("com.test.EchoService", "echo",
                new Class<?>[0], new Object[0], new HashMap<String, Object>());
    }

    @Test
    public void randomSelectsExistingInstance() {
        RandomLoadBalancer lb = new RandomLoadBalancer();
        for (int i = 0; i < 20; i++) {
            ServiceInstance selected = lb.select(instances, invocation());
            assertTrue(instances.contains(selected));
        }
    }

    @Test
    public void roundRobinCyclesInOrder() {
        RoundRobinLoadBalancer lb = new RoundRobinLoadBalancer();
        assertSame(instances.get(0), lb.select(instances, invocation()));
        assertSame(instances.get(1), lb.select(instances, invocation()));
        assertSame(instances.get(2), lb.select(instances, invocation()));
        assertSame(instances.get(0), lb.select(instances, invocation()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsEmptyInstances() {
        new RandomLoadBalancer().select(new ArrayList<ServiceInstance>(), invocation());
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -f small-rpc-core/pom.xml test -q -Dtest=LoadBalancerTest`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 最小实现**

`LoadBalancer.java`:

```java
package io.github.upowerman.core.loadbalance;

import io.github.upowerman.core.directory.ServiceInstance;
import io.github.upowerman.core.invocation.Invocation;

import java.util.List;

/**
 * 负载均衡：实例列表 + 调用信息 → 选出一个实例
 */
public interface LoadBalancer {

    ServiceInstance select(List<ServiceInstance> instances, Invocation invocation);
}
```

`RandomLoadBalancer.java`:

```java
package io.github.upowerman.core.loadbalance;

import io.github.upowerman.core.directory.ServiceInstance;
import io.github.upowerman.core.invocation.Invocation;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 随机负载均衡
 */
public class RandomLoadBalancer implements LoadBalancer {

    @Override
    public ServiceInstance select(List<ServiceInstance> instances, Invocation invocation) {
        if (instances == null || instances.isEmpty()) {
            throw new IllegalArgumentException("instances is empty");
        }
        return instances.get(ThreadLocalRandom.current().nextInt(instances.size()));
    }
}
```

`RoundRobinLoadBalancer.java`:

```java
package io.github.upowerman.core.loadbalance;

import io.github.upowerman.core.directory.ServiceInstance;
import io.github.upowerman.core.invocation.Invocation;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 轮询负载均衡
 */
public class RoundRobinLoadBalancer implements LoadBalancer {

    private final AtomicInteger sequence = new AtomicInteger(0);

    @Override
    public ServiceInstance select(List<ServiceInstance> instances, Invocation invocation) {
        if (instances == null || instances.isEmpty()) {
            throw new IllegalArgumentException("instances is empty");
        }
        int index = Math.abs(sequence.getAndIncrement() % instances.size());
        return instances.get(index);
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -f small-rpc-core/pom.xml test -q -Dtest=LoadBalancerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add small-rpc-core/src/main/java/io/github/upowerman/core/loadbalance small-rpc-core/src/test/java/io/github/upowerman/core/loadbalance
git commit -m "feat(rpc2): add LoadBalancer with random/round-robin"
```

---

### Task 5: Filter + FilterChain + TraceFilter

**Files:**
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/invoker/Invoker.java`
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/filter/Filter.java`
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/filter/FilterChain.java`
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/filter/TraceFilter.java`
- Test: `small-rpc-core/src/test/java/io/github/upowerman/core/filter/FilterChainTest.java`

**Interfaces:**
- Consumes: `Invocation`、`Result`/`DefaultResult`（Task 1/2）
- Produces: `interface Invoker { Class<?> interfaceClass(); CompletableFuture<Result> invoke(Invocation invocation); }`；`interface Filter { CompletableFuture<Result> invoke(Invoker next, Invocation invocation); }`；`FilterChain.build(List<Filter> filters, Invoker terminal)` 返回链头 `Invoker`（filters 为空时直接返回 terminal）；`TraceFilter`（无 traceId 时生成 UUID 放入 `RpcConstants.ATTACH_TRACE_ID`）

- [ ] **Step 1: 写失败测试**

```java
package io.github.upowerman.core.filter;

import io.github.upowerman.core.RpcConstants;
import io.github.upowerman.core.invocation.GenericInvocation;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class FilterChainTest {

    /** 记录经过顺序的测试 Filter */
    static class OrderFilter implements Filter {
        private final String name;
        private final List<String> order;

        OrderFilter(String name, List<String> order) {
            this.name = name;
            this.order = order;
        }

        @Override
        public CompletableFuture<Result> invoke(Invoker next, Invocation invocation) {
            order.add(name + ":before");
            return next.invoke(invocation).thenApply(result -> {
                order.add(name + ":after");
                return result;
            });
        }
    }

    private Invoker terminal(final List<String> order) {
        return new Invoker() {
            @Override
            public Class<?> interfaceClass() {
                return Object.class;
            }

            @Override
            public CompletableFuture<Result> invoke(Invocation invocation) {
                order.add("terminal");
                return CompletableFuture.completedFuture(DefaultResult.success("ok"));
            }
        };
    }

    @Test
    public void filtersExecuteAroundTerminalInOrder() throws Exception {
        List<String> order = new ArrayList<>();
        Invoker head = FilterChain.build(
                Arrays.asList(new OrderFilter("a", order), new OrderFilter("b", order)),
                terminal(order));

        assertEquals("ok", head.invoke(anyInvocation()).get().value());
        assertEquals(Arrays.asList("a:before", "b:before", "terminal", "b:after", "a:after"), order);
    }

    @Test
    public void emptyFiltersReturnsTerminalDirectly() {
        Invoker terminal = terminal(new ArrayList<String>());
        assertSame(terminal, FilterChain.build(new ArrayList<Filter>(), terminal));
    }

    @Test
    public void traceFilterInjectsTraceId() throws Exception {
        HashMap<String, Object> attachments = new HashMap<>();
        Invoker captured = new Invoker() {
            @Override
            public Class<?> interfaceClass() {
                return Object.class;
            }

            @Override
            public CompletableFuture<Result> invoke(Invocation invocation) {
                return CompletableFuture.completedFuture(DefaultResult.success(
                        invocation.attachments().get(RpcConstants.ATTACH_TRACE_ID)));
            }
        };
        Invoker head = FilterChain.build(
                Arrays.<Filter>asList(new TraceFilter()), captured);

        Result result = head.invoke(new GenericInvocation("s", "m",
                new Class<?>[0], new Object[0], attachments)).get();
        assertNotNull(result.value());
    }

    private Invocation anyInvocation() {
        return new GenericInvocation("com.test.EchoService", "echo",
                new Class<?>[0], new Object[0], new HashMap<String, Object>());
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -f small-rpc-core/pom.xml test -q -Dtest=FilterChainTest`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 最小实现**

`Invoker.java`:

```java
package io.github.upowerman.core.invoker;

import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.result.Result;

import java.util.concurrent.CompletableFuture;

/**
 * 调用链上所有可执行节点的统一抽象
 */
public interface Invoker {

    Class<?> interfaceClass();

    CompletableFuture<Result> invoke(Invocation invocation);
}
```

`Filter.java`:

```java
package io.github.upowerman.core.filter;

import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.result.Result;

import java.util.concurrent.CompletableFuture;

/**
 * 横切逻辑过滤器，装饰器模式串成责任链
 */
public interface Filter {

    CompletableFuture<Result> invoke(Invoker next, Invocation invocation);
}
```

`FilterChain.java`:

```java
package io.github.upowerman.core.filter;

import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.result.Result;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 把 filters 串成责任链，链尾是 terminal Invoker（通常是 ClusterInvoker）
 */
public final class FilterChain {

    private FilterChain() {
    }

    public static Invoker build(List<Filter> filters, Invoker terminal) {
        Invoker next = terminal;
        for (int i = filters.size() - 1; i >= 0; i--) {
            next = new FilterNode(filters.get(i), next);
        }
        return next;
    }

    static final class FilterNode implements Invoker {

        private final Filter filter;
        private final Invoker next;

        FilterNode(Filter filter, Invoker next) {
            this.filter = filter;
            this.next = next;
        }

        @Override
        public Class<?> interfaceClass() {
            return next.interfaceClass();
        }

        @Override
        public CompletableFuture<Result> invoke(Invocation invocation) {
            return filter.invoke(next, invocation);
        }
    }
}
```

`TraceFilter.java`:

```java
package io.github.upowerman.core.filter;

import io.github.upowerman.core.RpcConstants;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.result.Result;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * trace 透传：无 traceId 时生成一个，随 attachments 传递
 */
public class TraceFilter implements Filter {

    @Override
    public CompletableFuture<Result> invoke(Invoker next, Invocation invocation) {
        if (!invocation.attachments().containsKey(RpcConstants.ATTACH_TRACE_ID)) {
            invocation.attachments().put(RpcConstants.ATTACH_TRACE_ID, UUID.randomUUID().toString());
        }
        return next.invoke(invocation);
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -f small-rpc-core/pom.xml test -q -Dtest=FilterChainTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add small-rpc-core/src/main/java/io/github/upowerman/core/invoker small-rpc-core/src/main/java/io/github/upowerman/core/filter small-rpc-core/src/test/java/io/github/upowerman/core/filter
git commit -m "feat(rpc2): add Filter chain and TraceFilter"
```

---

### Task 6: PendingRequests + Futures（Java 8 超时工具）

**Files:**
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/transport/PendingRequests.java`
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/transport/Futures.java`
- Test: `small-rpc-core/src/test/java/io/github/upowerman/core/transport/PendingRequestsTest.java`
- Test: `small-rpc-core/src/test/java/io/github/upowerman/core/transport/FuturesTest.java`

**Interfaces:**
- Consumes: `Result`/`DefaultResult`（Task 1）
- Produces: `class PendingRequests { long nextRequestId(); CompletableFuture<Result> register(long requestId); void complete(long requestId, Result result); void remove(long requestId); int size(); }`；`Futures.withTimeout(CompletableFuture<T> future, long timeoutMillis, Supplier<T> onTimeout)` 返回带超时的新 future（原 future 先完成则透传其值，`timeoutMillis <= 0` 时原样返回）

- [ ] **Step 1: 写失败测试**

`PendingRequestsTest.java`:

```java
package io.github.upowerman.core.transport;

import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class PendingRequestsTest {

    @Test
    public void requestIdIncrements() {
        PendingRequests pending = new PendingRequests();
        assertEquals(1L, pending.nextRequestId());
        assertEquals(2L, pending.nextRequestId());
    }

    @Test
    public void completeResolvesFutureAndRemovesEntry() throws Exception {
        PendingRequests pending = new PendingRequests();
        long id = pending.nextRequestId();
        CompletableFuture<Result> future = pending.register(id);
        assertEquals(1, pending.size());

        pending.complete(id, DefaultResult.success("ok"));

        assertSame("ok", future.get(1, TimeUnit.SECONDS).value());
        assertEquals(0, pending.size());
    }

    @Test
    public void completeUnknownIdIsIgnored() {
        PendingRequests pending = new PendingRequests();
        pending.complete(999L, DefaultResult.success("ok"));
        assertEquals(0, pending.size());
    }

    @Test
    public void removeDropsPendingEntry() {
        PendingRequests pending = new PendingRequests();
        long id = pending.nextRequestId();
        pending.register(id);
        pending.remove(id);
        assertEquals(0, pending.size());

        // remove 后再 complete：未知 id 静默忽略，不会悬挂
        pending.complete(id, DefaultResult.success("x"));
        assertEquals(0, pending.size());
    }
}
```

`FuturesTest.java`:

```java
package io.github.upowerman.core.transport;

import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import org.junit.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class FuturesTest {

    @Test
    public void returnsUpstreamValueWhenCompletedInTime() throws Exception {
        CompletableFuture<Result> upstream = new CompletableFuture<>();
        upstream.complete(DefaultResult.success("fast"));

        Result result = Futures.withTimeout(upstream, 1000L,
                () -> DefaultResult.failure(io.github.upowerman.core.result.Status.TIMEOUT))
                .get(2, TimeUnit.SECONDS);

        assertEquals("fast", result.value());
    }

    @Test
    public void completesWithTimeoutValueWhenUpstreamIsSilent() throws Exception {
        CompletableFuture<Result> upstream = new CompletableFuture<>();

        Result result = Futures.withTimeout(upstream, 50L,
                () -> DefaultResult.failure(io.github.upowerman.core.result.Status.TIMEOUT))
                .get(2, TimeUnit.SECONDS);

        assertSame(io.github.upowerman.core.result.Status.TIMEOUT, result.status());
    }

    @Test
    public void nonPositiveTimeoutReturnsSameFuture() {
        CompletableFuture<Result> upstream = new CompletableFuture<>();
        assertSame(upstream, Futures.withTimeout(upstream, 0L, null));
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -f small-rpc-core/pom.xml test -q -Dtest='PendingRequestsTest,FuturesTest'`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 最小实现**

`PendingRequests.java`:

```java
package io.github.upowerman.core.transport;

import io.github.upowerman.core.result.Result;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * in-flight 请求表：requestId(long, AtomicLong 生成) → CompletableFuture。
 * 取代 1.x 的 RpcFutureResponse(wait/notify) 与 String requestId。
 */
public class PendingRequests {

    private final ConcurrentMap<Long, CompletableFuture<Result>> pending = new ConcurrentHashMap<Long, CompletableFuture<Result>>();
    private final AtomicLong idGenerator = new AtomicLong();

    public long nextRequestId() {
        return idGenerator.incrementAndGet();
    }

    public CompletableFuture<Result> register(long requestId) {
        CompletableFuture<Result> future = new CompletableFuture<Result>();
        pending.put(requestId, future);
        return future;
    }

    /** 完成请求并移除表项；未知 requestId 静默忽略（响应迟到/请求已超时移除） */
    public void complete(long requestId, Result result) {
        CompletableFuture<Result> future = pending.remove(requestId);
        if (future != null) {
            future.complete(result);
        }
    }

    public void remove(long requestId) {
        pending.remove(requestId);
    }

    public int size() {
        return pending.size();
    }
}
```

`Futures.java`:

```java
package io.github.upowerman.core.transport;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Java 8 没有 CompletableFuture.orTimeout(Java 9+)，
 * 用 applyToEither + 调度器实现"先到者赢"的超时语义
 */
public final class Futures {

    private static final ScheduledExecutorService TIMEOUT_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "small-rpc-timeout");
                t.setDaemon(true);
                return t;
            });

    private Futures() {
    }

    public static <T> CompletableFuture<T> withTimeout(CompletableFuture<T> future,
                                                       long timeoutMillis,
                                                       Supplier<T> onTimeout) {
        if (timeoutMillis <= 0) {
            return future;
        }
        CompletableFuture<T> timeout = new CompletableFuture<T>();
        ScheduledFuture<?> task = TIMEOUT_SCHEDULER.schedule(
                () -> timeout.complete(onTimeout.get()), timeoutMillis, TimeUnit.MILLISECONDS);
        return future.applyToEither(timeout, Function.identity());
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -f small-rpc-core/pom.xml test -q -Dtest='PendingRequestsTest,FuturesTest'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add small-rpc-core/src/main/java/io/github/upowerman/core/transport small-rpc-core/src/test/java/io/github/upowerman/core/transport
git commit -m "feat(rpc2): add PendingRequests and Java8 timeout utility"
```

---

### Task 7: Endpoint / Connection / Transport + InMemoryTransport

**Files:**
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/transport/Endpoint.java`
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/transport/Connection.java`
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/transport/Transport.java`
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/transport/InMemoryTransport.java`
- Test: `small-rpc-core/src/test/java/io/github/upowerman/core/transport/InMemoryTransportTest.java`

**Interfaces:**
- Consumes: `Invocation`、`Result`/`DefaultResult`、`Status`、`Invoker`（Task 1/2/5）
- Produces: `class Endpoint { static Endpoint of(String address); String address(); }`；`interface Connection { CompletableFuture<Result> request(Invocation invocation); void close(); }`；`interface Transport { Connection connect(Endpoint endpoint); }`；`InMemoryTransport.register(String address, Invoker provider)` + `implements Transport`（无注册 provider 时 connect 抛 `RpcException`）

- [ ] **Step 1: 写失败测试**

```java
package io.github.upowerman.core.transport;

import io.github.upowerman.core.invocation.GenericInvocation;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.exception.RpcException;
import org.junit.Test;

import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class InMemoryTransportTest {

    @Test
    public void routesRequestToRegisteredProvider() throws Exception {
        InMemoryTransport transport = new InMemoryTransport();
        transport.register("127.0.0.1:7080", new Invoker() {
            @Override
            public Class<?> interfaceClass() {
                return Object.class;
            }

            @Override
            public CompletableFuture<Result> invoke(Invocation invocation) {
                return CompletableFuture.completedFuture(
                        DefaultResult.success("pong:" + invocation.arguments()[0]));
            }
        });

        Connection connection = transport.connect(Endpoint.of("127.0.0.1:7080"));
        Invocation invocation = new GenericInvocation("com.test.EchoService", "echo",
                new Class<?>[]{String.class}, new Object[]{"ping"}, new HashMap<String, Object>());

        Result result = connection.request(invocation).get(1, TimeUnit.SECONDS);
        assertEquals("pong:ping", result.value());
    }

    @Test
    public void connectUnknownAddressThrows() {
        InMemoryTransport transport = new InMemoryTransport();
        try {
            transport.connect(Endpoint.of("127.0.0.1:9999"));
            fail("expected RpcException");
        } catch (RpcException expected) {
            // ok
        }
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -f small-rpc-core/pom.xml test -q -Dtest=InMemoryTransportTest`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 最小实现**

`Endpoint.java`:

```java
package io.github.upowerman.core.transport;

/**
 * 通信端点。P0 用 "host:port" 字符串；P1 协议化时按需扩展 ip/port 字段。
 */
public final class Endpoint {

    private final String address;

    private Endpoint(String address) {
        this.address = address;
    }

    public static Endpoint of(String address) {
        return new Endpoint(address);
    }

    public String address() {
        return address;
    }

    @Override
    public String toString() {
        return address;
    }
}
```

`Connection.java`:

```java
package io.github.upowerman.core.transport;

import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.result.Result;

import java.util.concurrent.CompletableFuture;

/**
 * 一条已建立的连接。P0 入参是 Invocation；P1 协议化后改为 RpcMessage 请求对象。
 */
public interface Connection {

    CompletableFuture<Result> request(Invocation invocation);

    void close();
}
```

`Transport.java`:

```java
package io.github.upowerman.core.transport;

/**
 * 纯网络层：端点 → 连接
 */
public interface Transport {

    Connection connect(Endpoint endpoint);
}
```

`InMemoryTransport.java`:

```java
package io.github.upowerman.core.transport;

import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import io.github.upowerman.exception.RpcException;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存 Transport：绕过网络直接调 registered provider，供单元测试与全链路测试用
 */
public class InMemoryTransport implements Transport {

    private final ConcurrentMap<String, Invoker> providers = new ConcurrentHashMap<String, Invoker>();

    public void register(String address, Invoker provider) {
        providers.put(address, provider);
    }

    @Override
    public Connection connect(Endpoint endpoint) {
        Invoker provider = providers.get(endpoint.address());
        if (provider == null) {
            throw new RpcException("no in-memory provider at " + endpoint.address());
        }
        return new InMemoryConnection(provider);
    }

    static final class InMemoryConnection implements Connection {

        private final Invoker provider;

        InMemoryConnection(Invoker provider) {
            this.provider = provider;
        }

        @Override
        public CompletableFuture<Result> request(Invocation invocation) {
            try {
                return provider.invoke(invocation);
            } catch (Exception e) {
                return CompletableFuture.completedFuture(DefaultResult.failure(Status.NETWORK_ERROR, e));
            }
        }

        @Override
        public void close() {
            // 内存连接无需关闭
        }
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -f small-rpc-core/pom.xml test -q -Dtest=InMemoryTransportTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add small-rpc-core/src/main/java/io/github/upowerman/core/transport small-rpc-core/src/test/java/io/github/upowerman/core/transport
git commit -m "feat(rpc2): add Transport/Connection abstractions with in-memory impl"
```

---

### Task 8: ReflectiveInvoker（Provider 端反射调用）

**Files:**
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/provider/ReflectiveInvoker.java`
- Test: `small-rpc-core/src/test/java/io/github/upowerman/core/provider/ReflectiveInvokerTest.java`

**Interfaces:**
- Consumes: `Invoker`、`Invocation`/`GenericInvocation`、`Result`（Task 1/2/5）
- Produces: `new ReflectiveInvoker(Class<?> interfaceClass, Object serviceBean)`；行为约定——方法存在且执行成功 → `success(返回值)`；`NoSuchMethodException` → `METHOD_NOT_FOUND`；业务异常（`InvocationTargetException` 取 target）→ `SERVER_ERROR + 异常`

- [ ] **Step 1: 写失败测试**

```java
package io.github.upowerman.core.provider;

import io.github.upowerman.core.invocation.GenericInvocation;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.result.Status;
import org.junit.Test;

import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

public class ReflectiveInvokerTest {

    public interface EchoService {
        String echo(String msg);

        void boom();
    }

    public static class EchoServiceImpl implements EchoService {
        @Override
        public String echo(String msg) {
            return "echo:" + msg;
        }

        @Override
        public void boom() {
            throw new IllegalStateException("biz error");
        }
    }

    private ReflectiveInvoker invoker() {
        return new ReflectiveInvoker(EchoService.class, new EchoServiceImpl());
    }

    private Invocation invocation(String method, Class<?>[] types, Object[] args) {
        return new GenericInvocation(EchoService.class.getName(), method, types, args,
                new HashMap<String, Object>());
    }

    @Test
    public void successReturnsValue() throws Exception {
        ReflectiveInvoker invoker = invoker();
        Object value = invoker.invoke(invocation("echo", new Class<?>[]{String.class},
                new Object[]{"hi"})).get(1, TimeUnit.SECONDS).value();
        assertEquals("echo:hi", value);
    }

    @Test
    public void missingMethodGivesMethodNotFound() throws Exception {
        ReflectiveInvoker invoker = invoker();
        io.github.upowerman.core.result.Result result = invoker.invoke(
                invocation("notExists", new Class<?>[]{String.class}, new Object[]{"x"}))
                .get(1, TimeUnit.SECONDS);
        assertSame(Status.METHOD_NOT_FOUND, result.status());
    }

    @Test
    public void businessErrorGivesServerErrorWithCause() throws Exception {
        ReflectiveInvoker invoker = invoker();
        io.github.upowerman.core.result.Result result = invoker.invoke(
                invocation("boom", new Class<?>[0], new Object[0])).get(1, TimeUnit.SECONDS);
        assertSame(Status.SERVER_ERROR, result.status());
        assertNotNull(result.exception());
        assertEquals("biz error", result.exception().getMessage());
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -f small-rpc-core/pom.xml test -q -Dtest=ReflectiveInvokerTest`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 最小实现**

`ReflectiveInvoker.java`:

```java
package io.github.upowerman.core.provider;

import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

/**
 * Provider 端 Invoker：把 Invocation 反射调用到本地 serviceBean。
 * 所有失败都转成带状态码的 Result，不抛裸异常。
 */
public class ReflectiveInvoker implements Invoker {

    private final Class<?> interfaceClass;
    private final Object serviceBean;

    public ReflectiveInvoker(Class<?> interfaceClass, Object serviceBean) {
        this.interfaceClass = interfaceClass;
        this.serviceBean = serviceBean;
    }

    @Override
    public Class<?> interfaceClass() {
        return interfaceClass;
    }

    @Override
    public CompletableFuture<Result> invoke(Invocation invocation) {
        try {
            Method method = serviceBean.getClass()
                    .getMethod(invocation.methodName(), invocation.parameterTypes());
            method.setAccessible(true);
            Object value = method.invoke(serviceBean, invocation.arguments());
            return CompletableFuture.completedFuture(DefaultResult.success(value));
        } catch (NoSuchMethodException e) {
            return CompletableFuture.completedFuture(DefaultResult.failure(Status.METHOD_NOT_FOUND, e));
        } catch (InvocationTargetException e) {
            return CompletableFuture.completedFuture(
                    DefaultResult.failure(Status.SERVER_ERROR, e.getTargetException()));
        } catch (Exception e) {
            return CompletableFuture.completedFuture(DefaultResult.failure(Status.SERVER_ERROR, e));
        }
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -f small-rpc-core/pom.xml test -q -Dtest=ReflectiveInvokerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add small-rpc-core/src/main/java/io/github/upowerman/core/provider small-rpc-core/src/test/java/io/github/upowerman/core/provider
git commit -m "feat(rpc2): add ReflectiveInvoker for provider side"
```

---

### Task 9: RemoteInvoker（Consumer 端远程 Invoker）

**Files:**
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/invoker/RemoteInvoker.java`
- Test: `small-rpc-core/src/test/java/io/github/upowerman/core/invoker/RemoteInvokerTest.java`

**Interfaces:**
- Consumes: `Transport`/`Endpoint`（Task 7）、`RpcConstants.ATTACH_ADDRESS`（Task 2）
- Produces: `new RemoteInvoker(Transport transport, Class<?> interfaceClass)`；行为约定——attachments 缺 `rpc.address` → `NETWORK_ERROR` 失败 Result；有地址 → `transport.connect(...).request(invocation)`；connect/request 抛异常 → `NETWORK_ERROR` 失败 Result

- [ ] **Step 1: 写失败测试**

```java
package io.github.upowerman.core.invoker;

import io.github.upowerman.core.RpcConstants;
import io.github.upowerman.core.invocation.GenericInvocation;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import io.github.upowerman.core.transport.Connection;
import io.github.upowerman.core.transport.Endpoint;
import io.github.upowerman.core.transport.InMemoryTransport;
import org.junit.Test;

import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class RemoteInvokerTest {

    public interface EchoService {
    }

    private Invocation invocation(String address) {
        HashMap<String, Object> attachments = new HashMap<>();
        if (address != null) {
            attachments.put(RpcConstants.ATTACH_ADDRESS, address);
        }
        return new GenericInvocation("com.test.EchoService", "echo",
                new Class<?>[0], new Object[0], attachments);
    }

    @Test
    public void missingAddressAttachmentFailsWithNetworkError() throws Exception {
        RemoteInvoker remote = new RemoteInvoker(new InMemoryTransport(), EchoService.class);
        Result result = remote.invoke(invocation(null)).get(1, TimeUnit.SECONDS);
        assertSame(Status.NETWORK_ERROR, result.status());
    }

    @Test
    public void delegatesToTransportConnection() throws Exception {
        InMemoryTransport transport = new InMemoryTransport();
        transport.register("127.0.0.1:7080", new Invoker() {
            @Override
            public Class<?> interfaceClass() {
                return Object.class;
            }

            @Override
            public CompletableFuture<Result> invoke(Invocation inv) {
                return CompletableFuture.completedFuture(DefaultResult.success("ok"));
            }
        });
        RemoteInvoker remote = new RemoteInvoker(transport, EchoService.class);

        Result result = remote.invoke(invocation("127.0.0.1:7080")).get(1, TimeUnit.SECONDS);

        assertEquals("ok", result.value());
    }

    @Test
    public void transportFailureBecomesNetworkError() throws Exception {
        RemoteInvoker remote = new RemoteInvoker(new InMemoryTransport(), EchoService.class);
        Result result = remote.invoke(invocation("127.0.0.1:9999")).get(1, TimeUnit.SECONDS);
        assertSame(Status.NETWORK_ERROR, result.status());
    }

    /** Connection.request 抛同步异常也要转 NETWORK_ERROR */
    @Test
    public void connectionSyncExceptionBecomesNetworkError() throws Exception {
        Transport broken = new Transport() {
            @Override
            public Connection connect(Endpoint endpoint) {
                return new Connection() {
                    @Override
                    public CompletableFuture<Result> request(Invocation invocation) {
                        throw new IllegalStateException("connection broken");
                    }

                    @Override
                    public void close() {
                    }
                };
            }
        };
        RemoteInvoker remote = new RemoteInvoker(broken, EchoService.class);
        Result result = remote.invoke(invocation("127.0.0.1:1")).get(1, TimeUnit.SECONDS);
        assertSame(Status.NETWORK_ERROR, result.status());
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -f small-rpc-core/pom.xml test -q -Dtest=RemoteInvokerTest`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 最小实现**

`RemoteInvoker.java`:

```java
package io.github.upowerman.core.invoker;

import io.github.upowerman.core.RpcConstants;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import io.github.upowerman.core.transport.Endpoint;
import io.github.upowerman.core.transport.Transport;
import io.github.upowerman.exception.RpcException;

import java.util.concurrent.CompletableFuture;

/**
 * Consumer 端远程 Invoker：从 attachments 取目标地址，经 Transport 发起调用。
 * 目标地址由 ClusterInvoker 选好实例后写入（每次重试可能不同）。
 */
public class RemoteInvoker implements Invoker {

    private final Transport transport;
    private final Class<?> interfaceClass;

    public RemoteInvoker(Transport transport, Class<?> interfaceClass) {
        this.transport = transport;
        this.interfaceClass = interfaceClass;
    }

    @Override
    public Class<?> interfaceClass() {
        return interfaceClass;
    }

    @Override
    public CompletableFuture<Result> invoke(Invocation invocation) {
        Object address = invocation.attachments().get(RpcConstants.ATTACH_ADDRESS);
        if (address == null || address.toString().trim().isEmpty()) {
            return CompletableFuture.completedFuture(DefaultResult.failure(Status.NETWORK_ERROR,
                    new RpcException("invocation missing attachment: " + RpcConstants.ATTACH_ADDRESS)));
        }
        try {
            return transport.connect(Endpoint.of(address.toString())).request(invocation);
        } catch (Exception e) {
            return CompletableFuture.completedFuture(DefaultResult.failure(Status.NETWORK_ERROR, e));
        }
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -f small-rpc-core/pom.xml test -q -Dtest=RemoteInvokerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add small-rpc-core/src/main/java/io/github/upowerman/core/invoker small-rpc-core/src/test/java/io/github/upowerman/core/invoker
git commit -m "feat(rpc2): add RemoteInvoker"
```

---

### Task 10: FailoverClusterInvoker（容错核心）

**Files:**
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/cluster/FailoverClusterInvoker.java`
- Test: `small-rpc-core/src/test/java/io/github/upowerman/core/cluster/FailoverClusterInvokerTest.java`

**Interfaces:**
- Consumes: `ServiceDirectory`（Task 3）、`LoadBalancer`（Task 4）、`Invoker`/`RemoteInvoker`（Task 5/9）、`Futures.withTimeout`（Task 6）、`RpcConstants.ATTACH_ADDRESS`/`ATTACH_TIMEOUT`（Task 2）
- Produces: `new FailoverClusterInvoker(ServiceDirectory directory, LoadBalancer loadBalancer, Invoker remoteInvoker, int retries, long defaultTimeoutMillis)`；行为约定——目录为空 → `SERVICE_NOT_FOUND`；可重试状态 `TIMEOUT/NETWORK_ERROR/SERVER_ERROR` 换实例重试，最多 `retries+1` 次；其余状态不重试；超时 = attachments 里 `rpc.timeout`（毫秒，Long）否则用 `defaultTimeoutMillis`；每次尝试把选中实例地址写入该次副本 invocation 的 `rpc.address`（不污染原始 invocation）

- [ ] **Step 1: 写失败测试**

```java
package io.github.upowerman.core.cluster;

import io.github.upowerman.core.RpcConstants;
import io.github.upowerman.core.directory.ServiceDirectory;
import io.github.upowerman.core.directory.ServiceInstance;
import io.github.upowerman.core.invocation.GenericInvocation;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.loadbalance.LoadBalancer;
import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class FailoverClusterInvokerTest {

    static ServiceDirectory dir(final ServiceInstance... instances) {
        return new ServiceDirectory() {
            @Override
            public List<ServiceInstance> list(String service) {
                return Arrays.asList(instances);
            }

            @Override
            public void subscribe(String service) {
            }
        };
    }

    /** 依次返回预设结果，并统计调用次数 */
    static Invoker scriptedInvoker(final Queue<Result> script, final AtomicInteger count,
                                   final List<String> receivedAddresses) {
        return new Invoker() {
            @Override
            public Class<?> interfaceClass() {
                return Object.class;
            }

            @Override
            public CompletableFuture<Result> invoke(Invocation invocation) {
                count.incrementAndGet();
                receivedAddresses.add((String) invocation.attachments().get(RpcConstants.ATTACH_ADDRESS));
                return CompletableFuture.completedFuture(script.poll());
            }
        };
    }

    static LoadBalancer firstLb() {
        return new LoadBalancer() {
            @Override
            public ServiceInstance select(List<ServiceInstance> instances, Invocation invocation) {
                return instances.get(0);
            }
        };
    }

    static Invocation invocation() {
        return new GenericInvocation("com.test.EchoService", "echo",
                new Class<?>[0], new Object[0], new HashMap<String, Object>());
    }

    @Test
    public void emptyDirectoryGivesServiceNotFound() throws Exception {
        FailoverClusterInvoker cluster = new FailoverClusterInvoker(
                dir(), firstLb(), scriptedInvoker(new ConcurrentLinkedQueue<Result>(),
                new AtomicInteger(), new ArrayList<String>()), 2, 1000);

        Result result = cluster.invoke(invocation()).get(2, TimeUnit.SECONDS);
        assertSame(Status.SERVICE_NOT_FOUND, result.status());
    }

    @Test
    public void successPassesValueAndWritesAddress() throws Exception {
        Queue<Result> script = new ConcurrentLinkedQueue<Result>();
        script.add(DefaultResult.success("ok"));
        List<String> addresses = new ArrayList<>();
        FailoverClusterInvoker cluster = new FailoverClusterInvoker(
                dir(new ServiceInstance("127.0.0.1:1")), firstLb(),
                scriptedInvoker(script, new AtomicInteger(), addresses), 2, 1000);

        Result result = cluster.invoke(invocation()).get(2, TimeUnit.SECONDS);

        assertEquals("ok", result.value());
        assertEquals(Arrays.asList("127.0.0.1:1"), addresses);
    }

    @Test
    public void retriesNetworkErrorThenSucceeds() throws Exception {
        Queue<Result> script = new ConcurrentLinkedQueue<Result>();
        script.add(DefaultResult.failure(Status.NETWORK_ERROR));
        script.add(DefaultResult.success("ok"));
        AtomicInteger count = new AtomicInteger();
        FailoverClusterInvoker cluster = new FailoverClusterInvoker(
                dir(new ServiceInstance("127.0.0.1:1")), firstLb(),
                scriptedInvoker(script, count, new ArrayList<String>()), 2, 1000);

        Result result = cluster.invoke(invocation()).get(2, TimeUnit.SECONDS);

        assertEquals("ok", result.value());
        assertEquals(2, count.get());
    }

    @Test
    public void stopsRetryingOnNonRetryableStatus() throws Exception {
        Queue<Result> script = new ConcurrentLinkedQueue<Result>();
        script.add(DefaultResult.failure(Status.METHOD_NOT_FOUND));
        AtomicInteger count = new AtomicInteger();
        FailoverClusterInvoker cluster = new FailoverClusterInvoker(
                dir(new ServiceInstance("127.0.0.1:1")), firstLb(),
                scriptedInvoker(script, count, new ArrayList<String>()), 3, 1000);

        Result result = cluster.invoke(invocation()).get(2, TimeUnit.SECONDS);

        assertSame(Status.METHOD_NOT_FOUND, result.status());
        assertEquals(1, count.get());
    }

    @Test
    public void retriesExhaustedReturnsLastFailure() throws Exception {
        Queue<Result> script = new ConcurrentLinkedQueue<Result>();
        script.add(DefaultResult.failure(Status.NETWORK_ERROR));
        script.add(DefaultResult.failure(Status.NETWORK_ERROR));
        script.add(DefaultResult.failure(Status.NETWORK_ERROR));
        AtomicInteger count = new AtomicInteger();
        FailoverClusterInvoker cluster = new FailoverClusterInvoker(
                dir(new ServiceInstance("127.0.0.1:1")), firstLb(),
                scriptedInvoker(script, count, new ArrayList<String>()), 2, 1000);

        Result result = cluster.invoke(invocation()).get(2, TimeUnit.SECONDS);

        assertSame(Status.NETWORK_ERROR, result.status());
        assertEquals(3, count.get());
    }

    @Test
    public void silentUpstreamTimesOut() throws Exception {
        AtomicInteger count = new AtomicInteger();
        Invoker silent = new Invoker() {
            @Override
            public Class<?> interfaceClass() {
                return Object.class;
            }

            @Override
            public CompletableFuture<Result> invoke(Invocation invocation) {
                count.incrementAndGet();
                return new CompletableFuture<Result>(); // 永不完成
            }
        };
        FailoverClusterInvoker cluster = new FailoverClusterInvoker(
                dir(new ServiceInstance("127.0.0.1:1")), firstLb(), silent, 0, 50L);

        Result result = cluster.invoke(invocation()).get(2, TimeUnit.SECONDS);

        assertSame(Status.TIMEOUT, result.status());
        assertEquals(1, count.get()); // retries=0，不重试
    }

    @Test
    public void timeoutFromAttachmentsOverridesDefault() throws Exception {
        // attachments 指定 50ms，默认 10000ms —— 应在 attachments 超时生效
        HashMap<String, Object> attachments = new HashMap<>();
        attachments.put(RpcConstants.ATTACH_TIMEOUT, 50L);
        Invocation inv = new GenericInvocation("com.test.EchoService", "echo",
                new Class<?>[0], new Object[0], attachments);
        Invoker silent = new Invoker() {
            @Override
            public Class<?> interfaceClass() {
                return Object.class;
            }

            @Override
            public CompletableFuture<Result> invoke(Invocation i) {
                return new CompletableFuture<Result>();
            }
        };
        FailoverClusterInvoker cluster = new FailoverClusterInvoker(
                dir(new ServiceInstance("127.0.0.1:1")), firstLb(), silent, 0, 10000L);

        Result result = cluster.invoke(inv).get(2, TimeUnit.SECONDS);

        assertSame(Status.TIMEOUT, result.status());
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -f small-rpc-core/pom.xml test -q -Dtest=FailoverClusterInvokerTest`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 最小实现**

`FailoverClusterInvoker.java`:

```java
package io.github.upowerman.core.cluster;

import io.github.upowerman.core.RpcConstants;
import io.github.upowerman.core.directory.ServiceDirectory;
import io.github.upowerman.core.directory.ServiceInstance;
import io.github.upowerman.core.invocation.GenericInvocation;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.loadbalance.LoadBalancer;
import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import io.github.upowerman.core.transport.Futures;
import io.github.upowerman.exception.RpcException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Failover 集群容错：目录查询 → 负载均衡选实例 → 远程调用 →
 * 按状态码决定是否换实例重试；超时统一由本层施加。
 * 熔断留给 3.0 —— 状态码模型已为其预留决策依据。
 */
public class FailoverClusterInvoker implements Invoker {

    private final ServiceDirectory directory;
    private final LoadBalancer loadBalancer;
    private final Invoker remoteInvoker;
    private final int retries;
    private final long defaultTimeoutMillis;

    public FailoverClusterInvoker(ServiceDirectory directory, LoadBalancer loadBalancer,
                                  Invoker remoteInvoker, int retries, long defaultTimeoutMillis) {
        this.directory = directory;
        this.loadBalancer = loadBalancer;
        this.remoteInvoker = remoteInvoker;
        this.retries = retries;
        this.defaultTimeoutMillis = defaultTimeoutMillis;
    }

    @Override
    public Class<?> interfaceClass() {
        return remoteInvoker.interfaceClass();
    }

    @Override
    public CompletableFuture<Result> invoke(Invocation invocation) {
        List<ServiceInstance> instances = directory.list(invocation.serviceName());
        if (instances == null || instances.isEmpty()) {
            return CompletableFuture.completedFuture(DefaultResult.failure(Status.SERVICE_NOT_FOUND,
                    new RpcException("no provider for " + invocation.serviceName())));
        }
        CompletableFuture<Result> root = new CompletableFuture<Result>();
        attempt(invocation, instances, 0, root);
        return root;
    }

    private void attempt(Invocation invocation, List<ServiceInstance> instances,
                         int attempt, CompletableFuture<Result> root) {
        ServiceInstance instance = loadBalancer.select(instances, invocation);
        Map<String, Object> attachments = new HashMap<String, Object>(invocation.attachments());
        attachments.put(RpcConstants.ATTACH_ADDRESS, instance.getAddress());
        Invocation attemptInvocation = new GenericInvocation(invocation.serviceName(),
                invocation.methodName(), invocation.parameterTypes(), invocation.arguments(),
                attachments);

        long timeoutMillis = resolveTimeout(attemptInvocation);
        CompletableFuture<Result> future = Futures.withTimeout(
                remoteInvoker.invoke(attemptInvocation), timeoutMillis,
                new java.util.function.Supplier<Result>() {
                    @Override
                    public Result get() {
                        return DefaultResult.failure(Status.TIMEOUT,
                                new RpcException("timeout after " + timeoutMillis + "ms"));
                    }
                });

        future.whenComplete((result, error) -> {
            if (error != null) {
                result = DefaultResult.failure(Status.NETWORK_ERROR, error);
            }
            if (attempt + 1 <= retries && isRetryable(result.status())) {
                attempt(invocation, instances, attempt + 1, root);
            } else {
                root.complete(result);
            }
        });
    }

    private long resolveTimeout(Invocation invocation) {
        Object timeout = invocation.attachments().get(RpcConstants.ATTACH_TIMEOUT);
        if (timeout instanceof Long && ((Long) timeout) > 0) {
            return (Long) timeout;
        }
        return defaultTimeoutMillis;
    }

    private boolean isRetryable(Status status) {
        return status == Status.TIMEOUT
                || status == Status.NETWORK_ERROR
                || status == Status.SERVER_ERROR;
    }
}
```

- [ ] **Step 4: 运行确认通过**

Run: `mvn -f small-rpc-core/pom.xml test -q -Dtest=FailoverClusterInvokerTest`
Expected: PASS（7 个测试）

- [ ] **Step 5: Commit**

```bash
git add small-rpc-core/src/main/java/io/github/upowerman/core/cluster small-rpc-core/src/test/java/io/github/upowerman/core/cluster
git commit -m "feat(rpc2): add FailoverClusterInvoker with status-driven retry"
```

---

### Task 11: RpcProxyFactory + 全链路内存集成测试

**Files:**
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/proxy/RpcProxyFactory.java`
- Test: `small-rpc-core/src/test/java/io/github/upowerman/core/proxy/RpcChainIntegrationTest.java`

**Interfaces:**
- Consumes: 前面所有任务的完整链路
- Produces: `new RpcProxyFactory<T>(Class<T> interfaceClass, List<Filter> filters, Invoker terminal)`；`getProxy()` 返回 JDK 动态代理——业务方法 → 构造 Invocation → 链式调用 → `.get()` 同步取 Result；`SUCCESS` 返回 value；`RpcException` 原样抛出；其他异常包成 `RpcException`；`Object` 声明的方法抛 `RpcException`

- [ ] **Step 1: 写失败测试**

`RpcProxyFactory` 单元行为 + 全链路集成（内存 Transport + LocalServiceRegistry）：

```java
package io.github.upowerman.core.proxy;

import io.github.upowerman.core.cluster.FailoverClusterInvoker;
import io.github.upowerman.core.directory.PullServiceDirectory;
import io.github.upowerman.core.filter.Filter;
import io.github.upowerman.core.filter.TraceFilter;
import io.github.upowerman.core.invoker.RemoteInvoker;
import io.github.upowerman.core.loadbalance.RandomLoadBalancer;
import io.github.upowerman.core.provider.ReflectiveInvoker;
import io.github.upowerman.core.transport.InMemoryTransport;
import io.github.upowerman.exception.RpcException;
import io.github.upowerman.registry.impl.LocalServiceRegistry;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class RpcChainIntegrationTest {

    public interface EchoService {
        String echo(String msg);
    }

    public static class EchoServiceImpl implements EchoService {
        @Override
        public String echo(String msg) {
            return "echo:" + msg;
        }
    }

    /** Business → Proxy → TraceFilter → ClusterInvoker → Directory → LB → RemoteInvoker → InMemoryTransport → ReflectiveInvoker */
    @Test
    public void fullChainThroughInMemoryTransport() {
        // provider 端
        InMemoryTransport transport = new InMemoryTransport();
        transport.register("127.0.0.1:7080",
                new ReflectiveInvoker(EchoService.class, new EchoServiceImpl()));

        // consumer 端：LocalServiceRegistry 直连地址 → PullServiceDirectory
        LocalServiceRegistry registry = new LocalServiceRegistry();
        Map<String, String> param = new HashMap<String, String>();
        param.put(LocalServiceRegistry.DIRECT_ADDRESS, "127.0.0.1:7080");
        registry.start(param);
        PullServiceDirectory directory = new PullServiceDirectory(registry, null);

        FailoverClusterInvoker cluster = new FailoverClusterInvoker(
                directory, new RandomLoadBalancer(),
                new RemoteInvoker(transport, EchoService.class), 2, 1000);

        EchoService echoService = new RpcProxyFactory<EchoService>(EchoService.class,
                Collections.<Filter>singletonList(new TraceFilter()), cluster).getProxy();

        assertEquals("echo:hello", echoService.echo("hello"));
        registry.stop();
    }

    @Test
    public void objectMethodsAreRejected() {
        InMemoryTransport transport = new InMemoryTransport();
        transport.register("127.0.0.1:7080",
                new ReflectiveInvoker(EchoService.class, new EchoServiceImpl()));
        FailoverClusterInvoker cluster = new FailoverClusterInvoker(
                new PullServiceDirectory(new LocalServiceRegistry(), null),
                new RandomLoadBalancer(),
                new RemoteInvoker(transport, EchoService.class), 0, 1000);
        EchoService echoService = new RpcProxyFactory<EchoService>(EchoService.class,
                Collections.<Filter>emptyList(), cluster).getProxy();

        try {
            echoService.toString();
            fail("expected RpcException");
        } catch (RpcException expected) {
            // ok
        }
    }

    @Test
    public void serviceNotFoundSurfacesAsRpcException() {
        LocalServiceRegistry registry = new LocalServiceRegistry();
        registry.start(new HashMap<String, String>());
        FailoverClusterInvoker cluster = new FailoverClusterInvoker(
                new PullServiceDirectory(registry, null),
                new RandomLoadBalancer(),
                new RemoteInvoker(new InMemoryTransport(), EchoService.class), 0, 300);
        EchoService echoService = new RpcProxyFactory<EchoService>(EchoService.class,
                Collections.<Filter>emptyList(), cluster).getProxy();

        try {
            echoService.echo("x");
            fail("expected RpcException");
        } catch (RpcException expected) {
            // ok
        }
        registry.stop();
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -f small-rpc-core/pom.xml test -q -Dtest=RpcChainIntegrationTest`
Expected: 编译失败（`RpcProxyFactory` 不存在）

- [ ] **Step 3: 最小实现**

`RpcProxyFactory.java`:

```java
package io.github.upowerman.core.proxy;

import io.github.upowerman.core.filter.FilterChain;
import io.github.upowerman.core.invocation.GenericInvocation;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import io.github.upowerman.exception.RpcException;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;

/**
 * 2.0 代理工厂：InvocationHandler 只做 Invocation 构造 + 发起调用，
 * 与 1.x 上帝类 RpcReferenceInvocationHandler 的对应物。
 */
public class RpcProxyFactory<T> {

    private final Class<T> interfaceClass;
    private final Invoker chainHead;

    public RpcProxyFactory(Class<T> interfaceClass, List<io.github.upowerman.core.filter.Filter> filters,
                           Invoker terminal) {
        this.interfaceClass = interfaceClass;
        this.chainHead = FilterChain.build(filters, terminal);
    }

    @SuppressWarnings("unchecked")
    public T getProxy() {
        return (T) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[]{interfaceClass},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        throw new RpcException("proxy object does not support Object method: "
                                + method.getName());
                    }
                    Invocation invocation = new GenericInvocation(interfaceClass.getName(),
                            method.getName(), method.getParameterTypes(), args,
                            new HashMap<String, Object>());
                    Result result = chainHead.invoke(invocation).get();
                    if (result.status() == Status.SUCCESS) {
                        return result.value();
                    }
                    Throwable cause = result.exception();
                    if (cause instanceof RpcException) {
                        throw (RpcException) cause;
                    }
                    if (cause != null) {
                        throw new RpcException(cause);
                    }
                    throw new RpcException("rpc call failed, status: " + result.status());
                });
    }
}
```

- [ ] **Step 4: 运行全部 core 测试，确认新旧都绿**

Run: `mvn -f small-rpc-core/pom.xml test -q`
Expected: PASS —— 新链路测试 + 1.x 既有测试（RpcReferenceInvocationHandlerTest、LocalServiceRegistryTest 等）全部通过

- [ ] **Step 5: Commit**

```bash
git add small-rpc-core/src/main/java/io/github/upowerman/core/proxy small-rpc-core/src/test/java/io/github/upowerman/core/proxy
git commit -m "feat(rpc2): add RpcProxyFactory, full in-memory chain integration test"
```

---

### Task 12: 1.x 适配层 —— Serializer 接口 + LegacyHessianSerializer + LegacyNettyTransport + 真 Netty 集成测试

**Files:**
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/serialize/Serializer.java`
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/serialize/LegacyHessianSerializer.java`
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/adapter/LegacyNettyTransport.java`
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/adapter/LegacyConnection.java`
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/adapter/BridgingFuture.java`
- Test: `small-rpc-core/src/test/java/io/github/upowerman/core/serialize/LegacyHessianSerializerTest.java`
- Test: `small-rpc-core/src/test/java/io/github/upowerman/core/adapter/LegacyNettyTransportIntegrationTest.java`

**Interfaces:**
- Consumes: 1.x `BaseClient.asyncSend(String address, RpcRequest request)`（经 `RpcReferenceBean` 模板获得 `NettyClient`）；1.x `RpcInvokerFactory.notifyInvokerFuture` 响应路由（`NettyClientHandler` 用 `NettyConnectClient.init` 传入的 factory 实例）；1.x `RpcFutureResponse`（构造时自注册进 factory 的 future 池，`setResponse` 可覆写）；1.x `HessianSerializer.serialize/deserialize`
- Produces: `interface Serializer { byte typeId(); byte[] serialize(Object obj); Object deserialize(byte[] bytes, Class<?> clazz); }`（`typeId` 对应 P1 协议帧的 codec 字段，P0 先定为 1）；`new LegacyHessianSerializer()`；`new LegacyNettyTransport(BaseSerializer serializer, RpcInvokerFactory invokerFactory, String version)` implements `Transport`——行为约定：long requestId（`PendingRequests.nextRequestId`）→ `String.valueOf` 填进 1.x `RpcRequest`；响应经 `BridgingFuture`（`RpcFutureResponse` 子类，覆写 `setResponse` 桥接）→ `PendingRequests.complete`；`errorMsg != null` → `SERVER_ERROR`（P1 有状态码后修正映射）；send 异常 → `NETWORK_ERROR`

**适配原理（为什么这样桥接）：** 1.x 响应路由是 `NettyClientHandler.channelRead0 → rpcInvokerFactory.notifyInvokerFuture(requestId, response) → 从 factory 的 future 池取 RpcFutureResponse 调 setResponse`。适配器在发起请求时把一个 `BridgingFuture`（`RpcFutureResponse` 子类）注册进 1.x 池——`super()` 构造即完成注册——再覆写 `setResponse`，把响应转发到新链路的 `PendingRequests`。无需改动 1.x 任何代码。

- [ ] **Step 1: 写失败测试**

`LegacyHessianSerializerTest.java`:

```java
package io.github.upowerman.core.serialize;

import org.junit.Test;

import java.io.Serializable;

import static org.junit.Assert.assertEquals;

public class LegacyHessianSerializerTest {

    public static class Payload implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String msg;

        public Payload(String msg) {
            this.msg = msg;
        }

        public String getMsg() {
            return msg;
        }
    }

    @Test
    public void roundTrip() {
        LegacyHessianSerializer serializer = new LegacyHessianSerializer();
        byte[] bytes = serializer.serialize(new Payload("hello"));
        Payload decoded = (Payload) serializer.deserialize(bytes, Payload.class);
        assertEquals("hello", decoded.getMsg());
    }

    @Test
    public void typeIdIsOne() {
        assertEquals(1, new LegacyHessianSerializer().typeId());
    }
}
```

`LegacyNettyTransportIntegrationTest.java`（真 Netty：起 1.x 服务端，走新链路客户端）:

```java
package io.github.upowerman.core.adapter;

import io.github.upowerman.core.cluster.FailoverClusterInvoker;
import io.github.upowerman.core.directory.PullServiceDirectory;
import io.github.upowerman.core.filter.Filter;
import io.github.upowerman.core.filter.TraceFilter;
import io.github.upowerman.core.invoker.RemoteInvoker;
import io.github.upowerman.core.loadbalance.RandomLoadBalancer;
import io.github.upowerman.core.proxy.RpcProxyFactory;
import io.github.upowerman.core.serialize.LegacyHessianSerializer;
import io.github.upowerman.core.transport.Transport;
import io.github.upowerman.invoker.RpcInvokerFactory;
import io.github.upowerman.net.base.NetEnum;
import io.github.upowerman.provider.RpcProviderFactory;
import io.github.upowerman.registry.impl.LocalServiceRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class LegacyNettyTransportIntegrationTest {

    public interface EchoService {
        EchoDTO echo(EchoDTO dto);
    }

    public static class EchoDTO implements Serializable {
        private static final long serialVersionUID = 1L;
        private String msg;

        public EchoDTO() {
        }

        public EchoDTO(String msg) {
            this.msg = msg;
        }

        public String getMsg() {
            return msg;
        }

        public void setMsg(String msg) {
            this.msg = msg;
        }
    }

    public static class EchoServiceImpl implements EchoService {
        @Override
        public EchoDTO echo(EchoDTO dto) {
            return new EchoDTO("echo:" + dto.getMsg());
        }
    }

    private static final int PORT = 18080;

    private RpcProviderFactory providerFactory;

    @Before
    public void startProvider() throws Exception {
        providerFactory = new RpcProviderFactory();
        providerFactory.setNetType(NetEnum.NETTY);
        providerFactory.setSerializer(new io.github.upowerman.serialize.HessianSerializer());
        providerFactory.setPort(PORT);
        // 校验配置并补齐线程池默认参数（core=60/max=300），不调用则池参数为 0
        providerFactory.initConfig();
        providerFactory.addService(EchoService.class.getName(), null, new EchoServiceImpl());
        providerFactory.start();
    }

    @After
    public void stopProvider() throws Exception {
        if (providerFactory != null) {
            providerFactory.stop();
        }
    }

    @Test
    public void newChainCallsRealNettyServer() throws Exception {
        LocalServiceRegistry registry = new LocalServiceRegistry();
        Map<String, String> param = new HashMap<String, String>();
        param.put(LocalServiceRegistry.DIRECT_ADDRESS, "127.0.0.1:" + PORT);
        registry.start(param);

        RpcInvokerFactory invokerFactory = new RpcInvokerFactory();
        Transport transport = new LegacyNettyTransport(
                new io.github.upowerman.serialize.HessianSerializer(), invokerFactory, null);

        FailoverClusterInvoker cluster = new FailoverClusterInvoker(
                new PullServiceDirectory(registry, null), new RandomLoadBalancer(),
                new RemoteInvoker(transport, EchoService.class), 1, 5000);

        EchoService echoService = new RpcProxyFactory<EchoService>(EchoService.class,
                Collections.<Filter>singletonList(new TraceFilter()), cluster).getProxy();

        assertEquals("echo:world", echoService.echo(new EchoDTO("world")).getMsg());
        registry.stop();
    }
}
```

- [ ] **Step 2: 运行确认失败**

Run: `mvn -f small-rpc-core/pom.xml test -q -Dtest='LegacyHessianSerializerTest,LegacyNettyTransportIntegrationTest'`
Expected: 编译失败（类不存在）

- [ ] **Step 3: 实现**

`Serializer.java`:

```java
package io.github.upowerman.core.serialize;

/**
 * 2.0 序列化 SPI。rpc-core 只认识这个接口；
 * P1 协议化后 typeId 填进帧的 codec 字段，多序列化实现共存。
 */
public interface Serializer {

    /** 序列化算法 ID，对应协议帧 codec 字段 */
    byte typeId();

    byte[] serialize(Object obj);

    Object deserialize(byte[] bytes, Class<?> clazz);
}
```

`LegacyHessianSerializer.java`:

```java
package io.github.upowerman.core.serialize;

/**
 * 适配 1.x HessianSerializer。P2 拆模块时本类搬入 rpc-transport-netty
 * 并直接基于 HessianInput/HessianOutput 重写，不再走 delegate。
 */
public class LegacyHessianSerializer implements Serializer {

    /** Hessian 的算法 ID（约定值，P1 起进协议帧） */
    public static final byte TYPE_ID = 1;

    private final io.github.upowerman.serialize.HessianSerializer delegate =
            new io.github.upowerman.serialize.HessianSerializer();

    @Override
    public byte typeId() {
        return TYPE_ID;
    }

    @Override
    public byte[] serialize(Object obj) {
        return delegate.serialize(obj);
    }

    @Override
    public Object deserialize(byte[] bytes, Class<?> clazz) {
        return delegate.deserialize(bytes, clazz);
    }
}
```

`BridgingFuture.java`:

```java
package io.github.upowerman.core.adapter;

import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Status;
import io.github.upowerman.core.transport.PendingRequests;
import io.github.upowerman.exception.RpcException;
import io.github.upowerman.invoker.RpcInvokerFactory;
import io.github.upowerman.net.base.RpcFutureResponse;
import io.github.upowerman.net.base.RpcRequest;
import io.github.upowerman.net.base.RpcResponse;

/**
 * RpcFutureResponse 子类：构造时自注册进 1.x future 池（super 副作用），
 * 响应到达时把结果桥接给新链路的 PendingRequests。
 */
final class BridgingFuture extends RpcFutureResponse {

    private final long requestId;
    private final PendingRequests pending;

    BridgingFuture(RpcInvokerFactory invokerFactory, RpcRequest request,
                   long requestId, PendingRequests pending) {
        super(invokerFactory, request);
        this.requestId = requestId;
        this.pending = pending;
    }

    @Override
    public void setResponse(RpcResponse response) {
        super.setResponse(response);
        if (response.getErrorMsg() != null) {
            // P1 引入状态码后按错误内容映射到 SERVICE_NOT_FOUND 等
            pending.complete(requestId, DefaultResult.failure(Status.SERVER_ERROR,
                    new RpcException(response.getErrorMsg())));
        } else {
            pending.complete(requestId, DefaultResult.success(response.getResult()));
        }
    }
}
```

`LegacyConnection.java`:

```java
package io.github.upowerman.core.adapter;

import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import io.github.upowerman.core.transport.Connection;
import io.github.upowerman.core.transport.PendingRequests;
import io.github.upowerman.exception.RpcException;
import io.github.upowerman.invoker.RpcInvokerFactory;
import io.github.upowerman.net.base.BaseClient;
import io.github.upowerman.net.base.RpcRequest;

import java.util.concurrent.CompletableFuture;

/**
 * 包装 1.x BaseClient 的连接：long requestId → String，
 * 响应经 BridgingFuture 回到新链路。
 */
final class LegacyConnection implements Connection {

    private final BaseClient client;
    private final RpcInvokerFactory invokerFactory;
    private final PendingRequests pending;
    private final String address;
    private final String version;

    LegacyConnection(BaseClient client, RpcInvokerFactory invokerFactory,
                     PendingRequests pending, String address, String version) {
        this.client = client;
        this.invokerFactory = invokerFactory;
        this.pending = pending;
        this.address = address;
        this.version = version;
    }

    @Override
    public CompletableFuture<Result> request(Invocation invocation) {
        long requestId = pending.nextRequestId();
        CompletableFuture<Result> future = pending.register(requestId);

        RpcRequest request = new RpcRequest();
        request.setRequestId(String.valueOf(requestId));
        request.setCreateMillisTime(System.currentTimeMillis());
        request.setClassName(invocation.serviceName());
        request.setMethodName(invocation.methodName());
        request.setParameterTypes(invocation.parameterTypes());
        request.setParameters(invocation.arguments());
        request.setVersion(version);

        // 构造即注册进 1.x future 池，响应到达时经 setResponse 桥接回来
        new BridgingFuture(invokerFactory, request, requestId, pending);

        try {
            client.asyncSend(address, request);
        } catch (Exception e) {
            pending.complete(requestId, DefaultResult.failure(Status.NETWORK_ERROR,
                    new RpcException("send failed to " + address, e)));
        }
        return future;
    }

    @Override
    public void close() {
        // 1.x 连接池由 RpcInvokerFactory.stop 回调统一关闭
    }
}
```

`LegacyNettyTransport.java`:

```java
package io.github.upowerman.core.adapter;

import io.github.upowerman.core.transport.Connection;
import io.github.upowerman.core.transport.Endpoint;
import io.github.upowerman.core.transport.PendingRequests;
import io.github.upowerman.core.transport.Transport;
import io.github.upowerman.invoker.RpcInvokerFactory;
import io.github.upowerman.invoker.route.LoadBalance;
import io.github.upowerman.net.base.BaseClient;
import io.github.upowerman.net.base.NetEnum;
import io.github.upowerman.serialize.BaseSerializer;
import io.github.upowerman.serialize.HessianSerializer;

/**
 * Transport 适配器：复用 1.x Netty 客户端与连接池。
 * 借 RpcReferenceBean 构造副作用创建可用的 NettyClient（其构造会 new + init client），
 * 一个实例可对多个 address 建连（1.x ConnectClient 按 address 池化）。
 */
public class LegacyNettyTransport implements Transport {

    private final BaseClient client;
    private final RpcInvokerFactory invokerFactory;
    private final PendingRequests pending;
    private final String version;

    public LegacyNettyTransport(BaseSerializer serializer, RpcInvokerFactory invokerFactory,
                                String version) {
        this.invokerFactory = invokerFactory;
        this.pending = new PendingRequests();
        this.version = version;
        io.github.upowerman.invoker.reference.RpcReferenceBean template =
                new io.github.upowerman.invoker.reference.RpcReferenceBean(
                        NetEnum.NETTY, serializer != null ? serializer : new HessianSerializer(),
                        LoadBalance.RANDOM, Object.class, version, 0L, null, invokerFactory);
        this.client = template.getClient();
    }

    @Override
    public Connection connect(Endpoint endpoint) {
        return new LegacyConnection(client, invokerFactory, pending,
                endpoint.address(), version);
    }
}
```

- [ ] **Step 4: 运行确认通过（注意本测试占用 18080 端口）**

Run: `mvn -f small-rpc-core/pom.xml test -q -Dtest='LegacyHessianSerializerTest,LegacyNettyTransportIntegrationTest'`
Expected: PASS。若 18080 被占用，先 `lsof -ti:18080 | xargs kill` 再重跑

- [ ] **Step 5: 运行全量测试**

Run: `mvn -f small-rpc-core/pom.xml test -q`
Expected: 全部 PASS（含 1.x 测试）

- [ ] **Step 6: Commit**

```bash
git add small-rpc-core/src/main/java/io/github/upowerman/core/serialize small-rpc-core/src/main/java/io/github/upowerman/core/adapter small-rpc-core/src/test/java/io/github/upowerman/core/serialize small-rpc-core/src/test/java/io/github/upowerman/core/adapter
git commit -m "feat(rpc2): add 1.x adapters — Serializer SPI, LegacyHessianSerializer, LegacyNettyTransport"
```

---

### Task 13: 样例接入新链路（spring-boot client 增加 /rpc2/hello）

**Files:**
- Modify: `small-rpc-simple/small-rpc-sample-springboot-client/src/main/java/io/github/upowerman/sample/config/RpcInvokerConfig.java`
- Modify: `small-rpc-simple/small-rpc-sample-springboot-client/src/main/java/io/github/upowerman/sample/controller/HelloController.java`

**Interfaces:**
- Consumes: Task 11/12 的 `RpcProxyFactory`、`FailoverClusterInvoker`、`PullServiceDirectory`、`RemoteInvoker`、`LegacyNettyTransport`、`TraceFilter`、`RandomLoadBalancer`
- Produces: Spring Bean `rpc2HelloService`（`HelloService` 类型，走 2.0 新链路）；HTTP 端点 `GET /rpc2/hello?name=`

- [ ] **Step 1: RpcInvokerConfig 增加 rpc2 链路的 bean**

在 `RpcInvokerConfig` 类内（`jobExecutor()` 方法之后）追加：

```java
    /**
     * 2.0 新链路代理：Proxy → TraceFilter → FailoverClusterInvoker →
     * PullServiceDirectory → LoadBalancer → RemoteInvoker → LegacyNettyTransport
     */
    @Bean
    public HelloService rpc2HelloService(RpcSpringInvokerFactory invokerFactory) {
        LegacyNettyTransport transport = new LegacyNettyTransport(
                new HessianSerializer(), invokerFactory, null);

        LocalServiceRegistry registry = new LocalServiceRegistry();
        Map<String, String> param = new HashMap<>();
        param.put(LocalServiceRegistry.DIRECT_ADDRESS, address);
        registry.start(param);

        PullServiceDirectory directory = new PullServiceDirectory(registry, null);
        FailoverClusterInvoker cluster = new FailoverClusterInvoker(
                directory, new RandomLoadBalancer(),
                new RemoteInvoker(transport, HelloService.class), 1, 3000);

        return new RpcProxyFactory<>(HelloService.class,
                Collections.<Filter>singletonList(new TraceFilter()), cluster).getProxy();
    }
```

对应新增 import：

```java
import io.github.upowerman.core.adapter.LegacyNettyTransport;
import io.github.upowerman.core.cluster.FailoverClusterInvoker;
import io.github.upowerman.core.directory.PullServiceDirectory;
import io.github.upowerman.core.filter.Filter;
import io.github.upowerman.core.filter.TraceFilter;
import io.github.upowerman.core.invoker.RemoteInvoker;
import io.github.upowerman.core.loadbalance.RandomLoadBalancer;
import io.github.upowerman.core.proxy.RpcProxyFactory;
import io.github.upowerman.registry.impl.LocalServiceRegistry;
import io.github.upowerman.serialize.HessianSerializer;
import io.github.upowerman.service.HelloService;
import java.util.Collections;
```

- [ ] **Step 2: HelloController 增加 /rpc2/hello 端点**

在 `HelloController` 类内追加（保留原 `@RpcReference` 字段与 `/hello` 端点不动）：

```java
    @org.springframework.beans.factory.annotation.Autowired
    @org.springframework.beans.factory.annotation.Qualifier("rpc2HelloService")
    private HelloService rpc2HelloService;

    @GetMapping("/rpc2/hello")
    public HelloDTO rpc2Hello(String name) {
        return rpc2HelloService.hello(name);
    }
```

- [ ] **Step 3: 编译与既有测试**

Run: `mvn -f small-rpc-simple/pom.xml test -q`
Expected: 全模块 PASS（small-rpc-core 测试 + 编译 sample 模块）

- [ ] **Step 4: 手动验收（新链路打通真服务）**

```bash
# 终端 1：起服务端（local 注册中心需配直连地址；见 RpcProviderConfig 的配置项）
cd small-rpc-simple/small-rpc-sample-springboot-server
mvn spring-boot:run -Dspring-boot.run.arguments="--small-rpc.registry.type=local --small-rpc.registry.address=127.0.0.1:7080"

# 终端 2：起客户端
cd small-rpc-simple/small-rpc-sample-springboot-client
mvn spring-boot:run -Dspring-boot.run.arguments="--small-rpc.registry.type=local --small-rpc.registry.address=127.0.0.1:7080 --server.port=8081"

# 终端 3：两种链路各自验证
curl 'http://127.0.0.1:8081/hello?name=rpc1'    # 1.x 链路
curl 'http://127.0.0.1:8081/rpc2/hello?name=rpc2'  # 2.0 新链路
```

Expected: 两个 curl 都返回 `HelloDTO` JSON（含 name 回显），`/rpc2/hello` 证明新调用链在真实 Netty + 注册中心上跑通。

- [ ] **Step 5: Commit**

```bash
git add small-rpc-simple/small-rpc-sample-springboot-client
git commit -m "feat(rpc2): expose new invocation chain via /rpc2/hello in sample app"
```

---

## 验收清单（对照 spec §7 P0 行）

- [ ] 新接口体系全部落地：`Invocation` / `Invoker` / `ClusterInvoker(FailoverClusterInvoker)` / `ServiceDirectory` / `LoadBalancer` / `Transport`+`Connection` / `Serializer` / `Filter`+`FilterChain`
- [ ] `PendingRequests`（`ConcurrentHashMap<Long, CompletableFuture<Result>>`）取代 `RpcFutureResponse` 的 in-flight 管理（1.x 类不动，仅不再被新链路使用）
- [ ] 容错决策表测试覆盖：空目录、成功、重试后成功、不可重试不重试、重试耗尽、超时、attachments 超时覆盖
- [ ] 全链路单测（内存 Transport）+ 真 Netty 集成测试 + 样例 `/rpc2/hello` 全部通过
- [ ] `mvn -f small-rpc-core/pom.xml test -q` 全绿；1.x 行为零改动
