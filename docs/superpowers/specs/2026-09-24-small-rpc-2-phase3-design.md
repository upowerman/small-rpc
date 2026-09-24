# small-rpc 2.0 P3 设计补篇（Registry 订阅 + Directory 缓存降级 + ZK/Redis 注册中心）

日期：2026-09-24
状态：待用户确认
母文档：`2026-09-23-small-rpc-2-design.md`（§3 Registry/Directory、§5 模块、§6 验收、§7 P3 行）；本篇钉死 P3 执行口径，冲突处以本篇为准。
前置：P2 已收口（HEAD `2840442`，135/135 全绿）。P2 台账承接项（Ruling 11）全部纳入本篇。

## 0. 范围与既定裁定

| 决策点 | 结论 | 来源 |
|---|---|---|
| P3 内容 | Registry 订阅 + Directory 缓存降级 + ZK/Redis 注册中心模块 + 容错决策表测试补缺 + 多实例样例 + README 全量重写 | spec §7 P3 行 + §6 |
| 模块粒度 | spec §5 全量 8 模块在 P3 收齐（新增 rpc-registry-zookeeper、rpc-registry-redis） | P2 用户裁定顺延 |
| ZK/Redis 依赖 | **沿用 1.x 选型**：Curator 5.6.0（curator-framework）、Jedis 4.4.3 | 1.x 先例（master pom 实测） |
| 容错决策表 | FailoverClusterInvoker 决策逻辑 P0 已实现且有参数化测试（SERVICE_NOT_FOUND / 重试换实例 / 业务失败不重试 / budget 超时）；P3 **只补 Directory 降级测试**，不重做 | 现状核对 |
| D-11（P2 承接） | 根 pom 转 parent + dependencyManagement，**P3 第一件事** | P2 Ruling 9 |
| M-5（P2 承接） | SPI 持连接扩展生命周期 → 本篇 §5 解决 | P2 复核 M-5 |
| D-8（P2 承接） | Adaptive 生产接线：P3 不做。多实例下按调用选路仍未出现需求 | P2 Ruling 9 延续 |
| 环境 | 本机 Docker 单机 ZK 3.9(:2181) + Redis 7(:6379) 已就绪（用户确认）；集成测试直连，服务不可用时 **Assume 跳过**（诚实计数，不做 1.x 式 try-catch 假绿） | 用户消息 + 探测 |

## 1. 新抽象（rpc-core，取代 1.x 形态的 BaseServiceRegistry）

现状：`BaseServiceRegistry` 是 1.x 形态（字符串 key/value、`discovery` 拉取、`start(Map)/stop`），`PullServiceDirectory` 每次 `list` 都打注册中心——正是 spec §3 要消灭的形态。

```java
/** 只负责"和注册中心打交道"；生命周期显式化（M-5 解法） */
public interface Registry {
    void init(Map<String, String> param);   // 建立连接/资源；失败大声抛（启动期 fail-fast）
    void destroy();                          // 释放连接；幂等
    void register(String service, ServiceInstance instance);    // provider 侧
    void unregister(String service, ServiceInstance instance);
    void subscribe(String service, ServiceListener listener);   // 订阅即推当前全量
    void unsubscribe(String service, ServiceListener listener);
}

/** 全量快照语义：每次回调给"该服务当前完整实例列表" */
public interface ServiceListener {
    void onChange(List<ServiceInstance> instances);
}
```

- `Registry` 带 `init/destroy` 是对 spec §3 签名的**有意扩展**：ZK/Redis 实现持连接，生命周期必须有落点（1.x 的 `start/stop` 精华保留）；`service` 显式传参而非塞进 ServiceInstance（provider 一个实例注册 N 个接口 = N 次 register，ServiceInstance 保持"地址+元数据"纯度）。
- 现有 `ServiceDirectory` 接口（`list` + `subscribe`）签名不变，语义升级：`subscribe` 从 no-op 变为"订阅 + 建缓存"，`list` 变为"读缓存，永不阻塞"。

## 2. CachingServiceDirectory 与降级语义（本抽象存在的意义）

rpc-core 新增 `CachingServiceDirectory implements ServiceDirectory`（core 不依赖任何客户端）：

- 构造：`(Registry registry)`。
- `subscribe(service)` → `registry.subscribe(service, listener)`；listener.onChange **整体替换** volatile 缓存（copy-on-write，读无锁）。
- **降级**：onChange 回调抛任何异常 → 吞掉并保留旧缓存；注册中心断连 → 推送自然停止，缓存自动冻结（"继续使用上次缓存的服务列表"）；实现重连后补推 → 缓存自动恢复。`list` 永不打注册中心、永不阻塞。
- 未 subscribe 过的服务：`list` 返回空列表（显式订阅契约，不隐式拉取）。
- 消费端接线点：`ReferenceBeanPostProcessor` 构建代理时对目标 service 调 `subscribe` 一次（替代现在的 `PullServiceDirectory` 透传）。

## 3. 两个注册中心实现

### 3.1 ZooKeeper（rpc-registry-zookeeper，Curator 5.6.0）

- 节点布局：`/{namespace}/{service}/instances/{address}`；`{service}` 与 `instances` 为**持久节点**，`{address}` 为 **EPHEMERAL**（掉线自动摘除，替代 1.x Redis 的 TTL/心跳）。
- register/unregister：创建/删除临时节点（内容暂空，metadata 留 P3+）。
- subscribe：`PathChildrenCache`（或 TreeCache，实现者按 Curator 5.6 实测 API 定）监听 `instances` 子节点，任何变更 → 全量列举 → `listener.onChange`；订阅建立时立即推一次当前全量。
- init：`CuratorFrameworkFactory.builder() + ExponentialBackoffRetry` + `start()`；destroy：`close()`（幂等）。
- param：`zk.connect`（必填，如 `localhost:2181`）、`zk.namespace`（默认 `small-rpc`）、`zk.session-timeout-ms`、`zk.connection-timeout-ms`（默认 10000/3000）。

### 3.2 Redis（rpc-registry-redis，Jedis 4.4.3）

- 数据结构：`small-rpc:registry:{service}` → **Set**（member = address）。
- register/unregister：SADD / SREM。
- **推送主实现 = 定时全量拉取 + 差异检测**：subscribe 时立即 SMEMBERS 推一次，之后调度线程按 `redis.poll-interval-ms`（默认 3000）SMEMBERS 与上次快照比对，有差异才 onChange。**不做 keyspace notification**——spec 明言"若实现不优雅，保留定时增量拉取作为兼容实现"；Docker Redis 默认关闭 keyspace 事件，走它需 `CONFIG SET` 侵入服务端或改配置文件，对开箱即用的学习样例不优雅。ZK（真推送）与 Redis（拉补推送）恰好展示两种范式，README 有故事可讲。
- init：JedisPool（host/port/timeout/password/database param）；destroy：pool.close() + 停调度线程。
- param：`redis.host`（默认 localhost）、`redis.port`（默认 6379）、`redis.database`（默认 0）、`redis.timeout-ms`（默认 2000）、`redis.password`（可选）、`redis.poll-interval-ms`。

### 3.3 Local（rpc-registry-local 迁移）

`LocalServiceRegistry` 实现新 `Registry`：内存 `Map<String, Set<address>>`；register/unregister 即改 Map 并同步遍历 notify 该服务的全部 listener（单线程语义，单测简单）；subscribe 立即推当前全量；`DIRECT_ADDRESS` param 语义保留（init 时若预置，则任何服务的订阅结果都含该地址——样例 client 直连用法不变）。destroy 清 Map（幂等）。

### 3.4 SPI

`Registry` 标注 `@Spi("local")`；三个实现模块各带 `META-INF/small-rpc/io.github.upowerman.core.registry.Registry` 登记（`local=…local.LocalServiceRegistry`、`zookeeper=…`、`redis=…`——多模块同名文件合并后按 name 区分，M6 的 (name,FQCN) 口径覆盖此场景）。`@SpiInject` 暂无注册中心扩展需要注入的其它 SPI，预留。

## 4. 1.x 形态退役（迁移连锁清单）

**删除**：`BaseServiceRegistry` 接口（rpc-core）、`PullServiceDirectory`（rpc-core）、rpc-registry-local 的旧 META-INF 登记文件。

**随迁改造**（删旧接口后必须全链编译）：
- `rpc-registry-local`：LocalServiceRegistry 按 §3.3 重写（旧测试语义随迁）。
- `small-rpc-spring`：`ReferenceBeanPostProcessor` 的 directory 构造从 `new PullServiceDirectory(registry, null)` 换 `CachingServiceDirectory`；构造入参类型随之改 `Registry`。
- `small-rpc-spring-boot-starter`：`Rpc2ConsumerAutoConfiguration` 的 registry bean 类型改 `Registry`；`Rpc2Properties.registry.param` 传给 `init(param)`（启动后 init，应用关闭时**不自动 destroy**——见 §5）。
- 样例 client yml 的 `registry.type: local + param.DIRECT_ADDRESS` 用法保持可用。

## 5. 生命周期与 M-5 收口（SPI 单例 × 连接资源）

- Registry 扩展是 **SPI 进程级单例**（多 Spring 上下文共享），Spring 的 `destroyMethod` 不得碰它（P2 D-5 裁定延续）。
- **关闭语义**：JVM 退出由守护线程自然回收；需要显式释放的场景（长驻进程换注册中心）由应用自行调 `destroy()`。starter 不注册任何 destroy 回调。README 说明此取舍。
- ZK/Redis 的重连恢复：Curator 自带重连；Jedis 池按借还自愈。订阅侧断连期间的降级由 §2 缓存冻结承接，重连后由实现补推（ZK watcher 重挂 / Redis 下一轮轮询）。

## 6. Provider 注册（补全"经注册中心"链路）

现状缺口：provider 只把 `@RpcService` 注册进内存 provider 表，从不进注册中心（local 直连靠 `DIRECT_ADDRESS` param 预置绕过）。

P3 在 `Rpc2ProviderAutoConfiguration` 补：`RpcServer.start()` 成功后，对每个已注册服务接口调用 `registry.register(iface, new ServiceInstance(selfAddress))`；`selfAddress` 取 `small-rpc.provider.rpc2-port` + 本机 IP（复用/收编 1.x 的 IpUtil 口径，实测后定）。`registry.type` 为 `none` 时跳过注册（纯直连部署）。local 类型同样注册（写本进程内存，语义统一，无害）。

## 7. starter 装配与样例

- `Rpc2Properties.registry`：`type`（local/zookeeper/redis/none，默认 local）+ `param`（Map<String,String>，透传 init）。
- consumer：按 type 经 SPI 取 Registry 单例 → init(param) → CachingServiceDirectory → 注入 ReferenceBeanPostProcessor。
- 样例（rpc-examples）：默认 profile 保持 local 直连开箱即用；新增 `application-zookeeper.yml` / `application-redis.yml`（server 与 client 各一份，2.0 键），演示多实例：两台 server（7081/7082）注册同一服务 → client 订阅轮询 → kill 7081 → 临时节点摘除/轮询发现 → 推送 → 调用全部落 7082。验收脚本记入 README 演示步骤。
- README 全量重写（母 spec §6「合并时 README 重写为 2.0 架构」）：8 模块表、SPI 三特性、Registry 订阅/降级、三种消费方式（local 直连 / @RpcReference.address / 经注册中心）、多实例演示。

## 8. 测试与验收

- **纯单测（rpc-core/local）**：CachingServiceDirectory 缓存替换、未订阅返回空、onChange 异常保留旧缓存（降级决策表，参数化）、LocalServiceRegistry 全生命周期 + 同步推送、DIRECT_ADDRESS 兼容。
- **集成测试（ZK/Redis 模块）**：JUnit `Assume` 探测（Redis `PING` / ZK 端口可达）→ 不可用 skip（surefire 计数可见）；可用则覆盖 init/register/subscribe 推送/register 二实例推送/destroy 幂等。注册用随机 namespace/DB 前缀避免污染本机服务。
- **P3 验收清单**：
  1. 根聚合 `mvn test -q -Dgpg.skip=true` 全绿（Assume 跳过可见）
  2. local 直连样例回归：`/rpc2/hello` curl 200（P2 行为不回退）
  3. ZK 多实例：两实例注册 → client 经注册中心调用 → kill 一台 → 推送后调用全落存活实例（日志可观测缓存更新）
  4. Redis 多实例：同上（轮询延迟 ≤ poll-interval + 余量）
  5. 降级演示：ZK 停机期间 client 调用仍成功（用缓存），重启后恢复推送
  6. README 2.0 全量重写完成；8 模块收齐；1.x 形态零残留（grep 验证）
- mvn 禁令不变：绝不 `mvn verify`。

## 9. 任务切分（详见实施计划）

T1 根 pom parent 化（D-11）→ T2 新抽象 + Local 迁移 + 1.x 形态退役（全链编译）→ T3 rpc-registry-zookeeper → T4 rpc-registry-redis（T3/T4 可并行）→ T5 provider 注册 + starter registry.type 扩展 + 样例 profile → T6 全量回归 + 多实例端到端验收 + README 重写。

依赖：T2 是 T3/T4/T5 的接口地基；T3/T4 互不依赖；T6 收口。每任务走 SDD：实现者 + 独立评审 + 修复轮 + scoped 复核 + 最终全分支评审。
