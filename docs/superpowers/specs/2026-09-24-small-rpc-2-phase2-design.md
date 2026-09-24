# small-rpc 2.0 P2 设计补篇（拆多模块 + 自研 SPI + Spring 搬家）

日期：2026-09-24
状态：已评审通过（控制器与用户逐项确认四个范围决策后成文）
母文档：`2026-09-23-small-rpc-2-design.md`（§5 模块结构 + SPI + Spring、§7 P2 行）；本篇钉死 P2 执行口径，冲突处以本篇为准。

## 0. 范围决策（用户已裁定）

| 决策点 | 结论 |
|---|---|
| 1.x 处置（feature/rpc2 上） | **彻底 2.0 化**：删除 1.x 框架包与 P0 桥接适配器；注解+Spring 集成搬入 rpc-spring 对接 2.0；样例改纯 2.0。master 保留完整 1.x |
| 模块粒度 | **spec §5 全量 8 模块为目标态**；ZK/Redis 注册中心**连同模块整体 P3**，故 P2 实建 6 模块 + 根聚合 pom（不建空模块） |
| SPI 特性 | **全三特性**：按名查找 + @SpiInject IoC + Adaptive |
| ZK/Redis 时机 | **整体 P3**（订阅推送语义本就属 P3，spec §3）；「经注册中心」样例验收点随之顺延 |

spec §6 的 P2 验收写 `mvn verify`：本仓库 GPG 插件不可用（P0/P1 既定禁令），**以根聚合 `mvn test` 全绿 + 样例 curl 为等价验收**。

## 1. 模块布局（groupId io.github.upowerman 不变）

```
small-rpc（根聚合 pom，新增；packaging=pom）
├── rpc-core               纯抽象 + 自研 SPI。只依赖 slf4j + junit(test)：
│   ├── invocation / invoker / result / cluster / directory / filter /
│   │   loadbalance（接口+内置 Random/RoundRobin）/ proxy / provider(ReflectiveInvoker)
│   ├── protocol 模型：Frame / RpcRequestBody / RpcResponseBody / ProtocolStatus（纯 Java，无 Netty）
│   ├── serialize：Serializer 接口 + SerializerRegistry
│   ├── transport：Transport / Connection / Endpoint 接口
│   ├── registry：BaseServiceRegistry 接口（自 1.x registry/ 包收编，包名迁入 core.registry）
│   ├── exception.RpcException（自 1.x exception/ 收编，包名不动）+ 2.0 用到的 util
│   └── spi：@Spi / SpiLoader / @SpiInject / @Adaptive + Adaptive 分发器
├── rpc-transport-netty    依赖 netty + hessian + rpc-core：
│   ├── FrameEncoder / FrameDecoder / ServerHandler / RpcServer（provider 端）
│   ├── NettyTransport / NettyConnection / ResponseHandler / HeartbeatTrigger（consumer 端）
│   └── HessianSerializer（基于 HessianInput/HessianOutput 直写，替换 LegacyHessianSerializer；typeId 沿用）
├── rpc-registry-local     依赖 rpc-core：LocalServiceRegistry（自 1.x registry/impl 收编）
├── rpc-spring             依赖 rpc-core + spring-context：@RpcService / @RpcReference +
│   │                      ServiceBeanPostProcessor / ReferenceBeanPostProcessor
│   └                      （1.x RpcSpringProviderFactory/RpcSpringInvokerFactory/RpcReferenceBean
│                          逻辑搬运，对接 2.0 链路；@RpcReference 生成的代理走
│                          Directory→Cluster→RemoteInvoker→Transport）
├── rpc-spring-boot-starter 依赖 rpc-spring + rpc-transport-netty + rpc-registry-local：
│   │                      自动装配（yml small-rpc.* 驱动）：provider 端 RpcServer 起
│   └                      rpc2-port 并注册 @RpcService 服务；consumer 端 NettyTransport +
│                          Directory + FailoverClusterInvoker + 代理注入
└── rpc-examples           聚合现有 small-rpc-simple 三子模块（api/client/server），纯 2.0
```

依赖铁律：**rpc-core 不出现 Netty/Spring/Hessian/ZK/Redis 任何依赖**（spec §5「只依赖 slf4j」）。

## 2. 1.x 删除与收编清单

**删除**（feature/rpc2 上，git rm 保留历史）：
- `net/`（16 文件）、`invoker/`（8 文件，1.x 链路）、`serialize/`（3：HessianSerializer/BaseSerializer/SerializeEnum）、`provider/`（2）、`registry/`（4，其中 BaseServiceRegistry/LocalServiceRegistry 为**收编迁移**非删除）、`core/adapter/`（3 桥接文件）
- 对应 1.x 测试：RedisServiceRegistry*、ZookeeperServiceRegistryTest、LegacyConnectionTest、LegacyHessianSerializerTest、LegacyNettyTransportIntegrationTest、1.x 链路集成测试等
- 样例中的 1.x 链路 bean（RpcSpringProviderFactory/RpcSpringInvokerFactory/Redis/ZK registry 配置、1.x 端口 7080）

**收编迁移**：
- `exception.RpcException` → rpc-core（12 处 2.0 引用，包名不动，零代码改动）
- `registry.BaseServiceRegistry` → rpc-core `core.registry`（PullServiceDirectory 的父接口）；`registry.impl.LocalServiceRegistry` → rpc-registry-local
- `annotation.@RpcService/@RpcReference` → rpc-spring（注解保留现语义，处理器对接 2.0）
- 2.0 依赖的 util（若有）→ rpc-core；其余随 1.x 删除
- `core.serialize.LegacyHessianSerializer` → rpc-transport-netty 重写为 `HessianSerializer`（T7 注记：HessianInput/HessianOutput 直写，去委托；既有单测语义随迁）

## 3. 自研 SPI（rpc-core，不用 JDK ServiceLoader）

1. **按名查找**：`@Spi("random")` 标注实现类；`META-INF/small-rpc/<接口全限定名>` 登记文件（行格式 `name=FQCN`，类 Dubbo）；`SpiLoader.of(LoadBalancer.class).getExtension("roundrobin")` —— 懒加载 + 单例 + 按名查找，找不到/重复名大声失败
2. **IoC**：扩展实现内 `@SpiInject` 字段由 SpiLoader 在实例化时注入其它 SPI 扩展（递归装配，环检测报错）
3. **Adaptive**：`@Adaptive("key")` 标注在接口（或方法级键名），运行时按 Invocation attachments/URL 参数值选扩展名分发。**简化设计：不做 Dubbo 式动态字节码编译**，用预生成的分发器实现（反射装配、可单测、可断点——学习项目复杂度划算，spec 已否决过同类协商机制）
4. **接入点**：LoadBalancer（random/roundrobin）、Serializer（hessian）、Registry（local）。`SerializeEnum`/`NetEnum` 等硬编码枚举随 1.x 删除，扩展选择统一走 SpiLoader

## 4. Spring 集成（spec §5「只做不泄漏」）

- rpc-spring：`@RpcService` 服务经 ServiceBeanPostProcessor 注册进 RpcServer 的 provider 表（对接 ReflectiveInvoker）；`@RpcReference` 字段经 ReferenceBeanPostProcessor 注入 2.0 代理（RpcProxyFactory + Filter 链 + FailoverClusterInvoker + PullServiceDirectory + SPI 选路 + NettyTransport）
- rpc-spring-boot-starter：`@ConfigurationProperties(small-rpc.*)` + AutoConfiguration.imports；provider 端自动起 RpcServer（rpc2-port）、consumer 端自动装配 transport/cluster；样例的 RpcProviderConfig/RpcInvokerConfig 手工 bean 全部删除，换 yml 配置

## 5. 验收

- 根聚合 `mvn test` 全绿（Java 8 / JUnit 4.13.2 口径不变）
- 样例纯 2.0：双应用启动，`/rpc2/hello` curl 200，零 ERROR；7080 1.x 端口不复存在
- SPI 生效测试：配置切换 loadbalance=random→roundrobin 行为可观测；serializer/registry 经 SPI 装配等价
- 1.x 零残留：feature/rpc2 上 `io.github.upowerman.{net,invoker,provider,serialize,registry.impl.Base*之外}` 不复存在；adapter/ 不复存在

## 6. 任务切分（详见实施计划）

T1 拆模块+1.x 删除+Hessian 重写 → T2 SPI 按名查找 → T3 SPI IoC+Adaptive → T4 SPI 接入点 → T5 rpc-spring+starter+样例纯 2.0 → T6 全量回归+样例验收+文档
