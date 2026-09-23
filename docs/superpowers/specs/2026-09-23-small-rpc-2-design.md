# small-rpc 2.0 设计文档

日期：2026-09-23
状态：已评审通过（各节均经口头确认）
定位：深入学习 + 作品集。每个抽象都要能讲清楚"为什么这么分层"，不追求生产部署。

## 0. 目标与范围

### 目标
把 1.x 里"Spring + Netty + Registry 的一堆代码"重构为可长期演进的分层抽象。
1.x 已解决"RPC 怎么跑起来"，2.0 解决"RPC 为什么能够长期演进"。

### 2.0 范围（四大块）
1. **核心调用链分层**：Invocation → Filter → ClusterInvoker → ServiceDirectory → LoadBalancer → Invoker → Transport（含 CompletableFuture 化与 Result 状态码模型）
2. **协议 + 序列化抽象**：二进制 Header/Body 协议、long requestId、Serializer SPI
3. **Directory 缓存 + 订阅**：注册中心从调用热路径移除，变更推送 + 本地缓存
4. **集群容错**：Retry / Failover（基于状态码）

### 明确排除（留给 3.0）
- 熔断（CircuitBreaker）：需统计窗口与半开状态机，状态码模型已为其预留决策依据
- methodId 跨语言注册表（建议 #11 被否决，见 §2）
- Nacos/Consul/Kryo/Protobuf 等未出现的实现，只建抽象不建目录

### 落地方式
- **路线 A：抽象先行**。P0 在现有单模块内定义新接口体系并跑通新链路 → P1 协议升级 → P2 拆多模块 + 自研 SPI → P3 Directory 订阅 + 容错。
- 2.x 分支（`feature/rpc2`）渐进重构，每步可编译、可测试；master 始终是可用的 1.x。
- 2.0 允许破坏性变更，不做 1.x 兼容层。

## 1. 核心调用链

### 接口体系

```java
/** 一次调用的不变描述：服务、方法、参数、附加属性 */
public interface Invocation {
    String serviceName();              // 接口全限定名
    String methodName();
    Class<?>[] parameterTypes();
    Object[] arguments();
    Map<String, Object> attachments(); // traceId、timeout 等横切信息
}

/** 调用链上所有可执行节点的统一抽象 */
public interface Invoker {
    Class<?> interfaceClass();
    CompletableFuture<Result> invoke(Invocation invocation);
}

/** 调用结果：返回值 + 状态码 + 异常 */
public interface Result {
    Status status();          // SUCCESS / TIMEOUT / SERVICE_NOT_FOUND /
                              // METHOD_NOT_FOUND / SERIALIZATION_ERROR /
                              // SERVER_ERROR / NETWORK_ERROR
    Object value();
    Throwable exception();    // 失败时的原始异常
}
```

### 关键决定

**① 全链路异步签名。** `invoke` 返回 `CompletableFuture<Result>`：底层响应匹配换成
`ConcurrentHashMap<Long, CompletableFuture<Result>>`（`RpcFutureResponse` 与 wait/notify 整个删除）后，
中间环节（Filter、ClusterInvoker、LoadBalancer）异步穿透，同步/异步/超时/重试统一在一个模型里。
同步调用 = 链尾 `.get()`；反向（先同步后异步）则改不动。

**② Result 承载状态码而非 errorMsg 字符串。** 集群容错层按状态码决策（§4），不 parse 异常消息。

### 分层职责（每层只做一件事）

```text
Proxy/InvocationHandler   只做 Invocation 构造 + 发起调用（十几行，不再是上帝类）
Filter 链                  横切逻辑：trace、metrics（§4）
ClusterInvoker            directory.list → LoadBalancer.select → 远程 invoke →
                          按状态码 retry/failover、统一 orTimeout（§4）
ServiceDirectory          给定 service 返回实例列表（本地缓存，§3）
LoadBalancer              List<ServiceInstance> + Invocation → 选一个实例
                          （Random / RoundRobin 为内置实现，接口留出扩展）
Invoker(远程)              ConnectionManager 取连接 → Transport 发请求
Transport/Connection      纯网络：connect(endpoint) / request 返回 CompletableFuture
```

### 数据流

```text
Business → JDK Proxy → Invocation → Filter 链 → ClusterInvoker
    → ServiceDirectory(本地缓存) → LoadBalancer → 远程 Invoker
    → ConnectionManager → Netty Connection.request()
    → 协议帧 → Provider 端 Filter 链 → Provider Invoker → 反射调用目标服务
```

## 2. 二进制协议 + 序列化抽象

### 协议帧

```text
 0         2     3     4     5     6     7      8              16        20
+---------+-----+-----+-----+-----+-----+------+--------------+---------+---------+
| magic(2)| ver | type|codec|status|rsvd | rsvd | requestId(8) | bodyLen | body(变长)|
+---------+-----+-----+-----+-----+-----+------+--------------+---------+---------+
```

- `magic`：帧魔数，用于快速拒绝非法流
- `type`：`REQUEST / RESPONSE / HEARTBEAT` —— 心跳为协议一等公民（替代 1.x 的 `Beat` 借道业务请求）
- `codec`：序列化算法 ID，请求携带、响应沿用 —— 多序列化共存的前提
- `status`：响应帧回传状态码，与 `Result.Status` 一一对应
- `requestId`：`long`，`AtomicLong` 生成，取代 UUID（建议 #10）

### 调用 Body

序列化后的 `RpcRequestBody` 字段：`serviceName + methodName + parameterTypes + arguments + attachments`。
把"协议格式"与"调用描述"分开：未来引入 methodId 只改 Body，不动帧结构。
（对建议 #11 的取舍：methodId 注册表跨语言潜力好，但需"接口→方法 ID"协商机制，对学习项目复杂度不划算；
携带全名 + parameterTypes 已满足"收发双方只共享一个 api jar"。）

### 序列化 SPI

```java
public interface Serializer {
    byte typeId();   // 对应帧里的 codec 字段
    byte[] serialize(Object obj);
    <T> T deserialize(byte[] bytes, Class<T> clazz);
}
```

- `rpc-core` 只认识 `Serializer` 接口；Hessian 实现连同 Maven 依赖一起移出 core（建议 #4）
- 序列化失败转成 `SERIALIZATION_ERROR` 状态的 `Result`，不抛裸异常

## 3. Registry 与 ServiceDirectory 分离 + 订阅

### 接口

```java
/** 只负责"和注册中心打交道" */
public interface Registry {
    void register(ServiceInstance instance);
    void unregister(ServiceInstance instance);
    void subscribe(String service, ServiceListener listener);
    void unsubscribe(String service, ServiceListener listener);
    // 无 discover()：查询职责整体移交 Directory
}

/** 消费者的本地视图，调用热路径只经过它 */
public interface ServiceDirectory {
    List<ServiceInstance> list(String service);   // 读本地缓存，永不阻塞
    void subscribe(String service);               // 首次订阅 + 建缓存
}
```

### 数据流

```text
启动/首次调用 → Directory.subscribe → Registry.subscribe → 注册中心 watch
     ↓（变更回调）
Directory 更新本地 List<ServiceInstance> → LoadBalancer 直接读
```

### 各注册中心实现要点

- **ZooKeeper**：watcher 回调；永久节点存接口、临时节点存实例（掉线自动摘除，
  替代 1.x Redis 实现的 TTL/心跳逻辑）
- **Redis**：keyspace notification（`__keyspace@0__`）订阅 + 初始化全量拉取；
  若实现不优雅，保留定时增量拉取作为兼容实现
- **Local**：内存 Map，Directory 缓存退化为直读，供单测/样例

### 降级语义（本抽象存在的意义）

订阅回调失败或注册中心不可达时，Directory **继续使用上次缓存的服务列表**。
1.x"每次调用查注册中心"的架构给不了这个能力。

### ServiceInstance 扩展

补齐 `weight`、`startTime`（活跃数统计用）、`metadata`。

## 4. Filter 链 + 集群容错

### Filter

```java
public interface Filter {
    CompletableFuture<Result> invoke(Invoker next, Invocation invocation);
}
```

- 装饰器模式组装：`ProxyFactory` 创建代理时把 `filters → ClusterInvoker` 串成责任链，
  `FilterNode.invoke` 调 `next.invoke(invocation)`，链尾是 ClusterInvoker
- 内置 Filter 只做两个够讲故事的：`TraceFilter`（生成/透传 traceId，放 attachments）、
  `MetricsFilter`（RT、状态码分布）
- 不做注解激活、不做顺序排序配置——2.0 只需要"链存在、可插入"

### ClusterInvoker 容错（Failover）

```text
invoke:
  instances = directory.list(service)            // 本地缓存，不碰注册中心
  if empty → Result(SERVICE_NOT_FOUND)
  loop (1 + retries 次):
    instance = loadBalancer.select(instances, invocation)
    result = 远程 Invoker.invoke(...)             // 统一 orTimeout
    if status == SUCCESS → return
    if status ∈ {TIMEOUT, NETWORK_ERROR, SERVER_ERROR}
       → 还有次数则换实例重试；否则 return 最后的 result
    if status ∈ {SERIALIZATION_ERROR, METHOD_NOT_FOUND, ...业务性失败}
       → 直接 return，不重试
```

- 超时收敛在 ClusterInvoker：`attachments.timeout`（默认值）+ `orTimeout`
- **2.0 只做 Retry/Failover 一种集群策略**；熔断留 3.0，状态码模型已预留决策依据

## 5. 模块结构 + SPI + Spring

### Maven 模块（多模块拆分，建议 #14/#15 收敛版）

```text
small-rpc
├── rpc-core                    // 纯抽象：Invocation/Invoker/Cluster/Directory/
│                               // Registry 接口/协议/Filter/SPI。只依赖 slf4j，
│                               // 不依赖 Netty、Spring、ZooKeeper、Redis
├── rpc-transport-netty         // Netty Transport/Connection + 编解码 + Hessian Serializer
├── rpc-registry-zookeeper      // Registry + Directory 的 ZK 实现
├── rpc-registry-redis          // Registry + Directory 的 Redis 实现
├── rpc-registry-local          // 单测/样例用
├── rpc-spring                  // @RpcService/@RpcReference + Spring 集成
├── rpc-spring-boot-starter     // 自动装配，thin layer
└── rpc-examples                // 现有 small-rpc-simple 样例迁入
```

### 自研 SPI（类 Dubbo 扩展机制）

不使用 JDK `ServiceLoader`。核心三件事：

```java
@Spi("random")
public interface LoadBalancer { ... }

LoadBalancer lb = SpiLoader.of(LoadBalancer.class).getExtension("consistentHash");
```

1. **按名字找实现**（替代 `SerializeEnum`/`NetEnum` 等硬编码枚举）
2. **IoC 构造注入**：SPI 对象内 `@SpiInject` 其它 SPI
3. **Adaptive 扩展**：按 URL/attachments 参数动态选实现

hessian、zookeeper、redis、loadbalance 全部走 SPI，架构边界就此闭环。

### Spring 集成

`RpcProviderFactory` / `RpcInvokerFactory` / `RpcSpringProviderFactory`
收编为 `rpc-spring` 的 `ServiceBeanPostProcessor` + `ReferenceBeanPostProcessor`，
逻辑基本原样搬运、只对接新 SPI 接口。**Spring 这层 2.0 不重构，只做"不泄漏"**
（core 不出现任何 Spring 类型）。

## 6. 测试与验收

- **每 Phase 验收**：`HelloService` 样例在新链路跑通（Spring Boot 服务端 + 客户端，
  直连与经注册中心两种方式）；`mvn verify` 全绿
- **P0 专项**：新调用链用 LocalRegistry + 内存 Transport 做纯单元测试（不依赖网络）；
  Directory 降级、按状态码重试的决策表用参数化测试覆盖
- **P1 专项**：编解码器粘包/半包测试；帧字段 roundtrip
- **渐进保障**：`feature/rpc2` 分支，master 保持可用 1.x；每 Phase 一组 commit；
  合并时 README 重写为 2.0 架构

## 7. 阶段路线图

| Phase | 内容 | 验收 |
|-------|------|------|
| P0 | 新接口体系（Invocation/Invoker/ClusterInvoker/ServiceDirectory/LoadBalancer/Transport/Serializer/Filter）+ 适配器包住现有实现，新链路跑通 | 单元测试 + 样例走新链路 |
| P1 | 二进制协议帧、long requestId、心跳一等公民、Result 状态码贯通 | 编解码测试 + 样例 |
| P2 | 拆多模块 Maven、自研 SPI、Spring 模块搬家 | `mvn verify` + 样例 |
| P3 | Registry 订阅 + Directory 缓存降级、Failover/Retry | 容错决策表测试 + 多实例样例 |
