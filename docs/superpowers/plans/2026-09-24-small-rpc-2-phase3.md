# small-rpc 2.0 P3 实施计划（Registry 订阅 + Directory 缓存降级 + ZK/Redis）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 1.x 形态的注册中心抽象升级为 spec §3 目标态（Registry 订阅 + Directory 缓存降级），补上 ZK/Redis 两个注册中心模块（8 模块收齐），补全 provider 注册链路，多实例样例端到端验收。

**Architecture:** rpc-core 新增 `Registry`（register/unregister/subscribe/unsubscribe + init/destroy 生命周期）与 `CachingServiceDirectory`（订阅即全量快照缓存，回调失败/断连时缓存冻结=降级）；`BaseServiceRegistry` + `PullServiceDirectory` 退役。ZK 走 Curator（EPHEMERAL 实例节点 + PathChildrenCache 真推送），Redis 走 Jedis（Set + 定时拉取比对推送）。provider 侧 `RpcServer.start()` 后把服务接口注册进 Registry。

**Tech Stack:** Java 8 / JUnit 4.13.2 / Maven 多模块 / Spring Boot 2.7.18 / Curator 5.6.0 / Jedis 4.4.3 / Netty / Hessian

**Spec:** `docs/superpowers/specs/2026-09-24-small-rpc-2-phase3-design.md`（执行者必读；冲突以本篇为准，本篇偏离处均已记 Ruling）

## Global Constraints

- **绝不运行 `mvn verify`**（本机 GPG 不可用，P0/P1 既定禁令）。验收命令一律 `mvn test -q -Dgpg.skip=true`（仓库根目录）。
- `git add` 只用明确路径，**绝不 `git add -A` / `git add .`**。未跟踪的 `mise.toml` 与本计划无关，**绝不要 add**。
- 不合并、不 push、不 rebase。commit 落在 `feature/rpc2`。
- **绝不派发子代理**（实现者不派助手、不派评审）。
- 临时文件放 `/Users/gaoyunfeng/.claude/jobs/879bc6ff/tmp`。测试取空闲端口用 `new ServerSocket(0)`。
- 基线：P3 起点 HEAD `f3d7185`，**135 测试全绿**。每个任务结束必须全绿（测试数只增不减，被删测试须在报告中列出并说明）。
- 集成测试连本机 Docker 服务（ZK 3.9 `localhost:2181` / Redis 7 `localhost:6379`）。**服务不可用必须 JUnit `Assume` 跳过（skipped 可见），绝不 try-catch 吞掉断言造成假绿**。
- 集成测试必须用随机 namespace / key 前缀隔离（不得污染本机服务的既有数据）。
- Java 8 语法（无 var/lambda 之外的 9+ 特性；本项目代码风格为显式匿名内部类，保持）。
- 依赖版本：junit 4.13.2、slf4j-api 1.7.36、hessian 4.0.66、curator 5.6.0、jedis 4.4.3、spring-boot 2.7.18。

## Review Focus

1. **降级语义在真实断连下成立**（spec §3 的核心承诺）：ZK 停机期间，已订阅的 client 调用仍成功（走缓存），重启后推送恢复。期望：任何"注册中心不可达就抛异常/清空缓存"的实现都是错的。
2. **SPI 单例 × 连接生命周期**：`Registry` 是进程级 SPI 单例，多 Spring 上下文共享；重复 `init` 不得泄漏连接（幂等或大声失败二选一，须明确）。
3. **ZK 临时节点摘除时效**：进程被 kill 后，陈旧实例在 session 超时窗口内仍在列表中 → 调用失败 → 由 Failover 重试换实例兜底。期望：不静默成功、不永久失败。
4. **Redis 轮询线程并发安全**：调度线程与 subscribe/unsubscribe 线程对同一 service 快照的读写；unsubscribe 后必须停止推送且无泄漏线程。
5. **provider 注册地址正确性**：多网卡/容器环境下本机 IP 的选择必须可被 `small-rpc.provider.address` 覆盖；地址错误会导致注册成功但调用全失败。

---

### Task 1: 根 pom 转 parent + dependencyManagement（P2 承接 D-11）

**Files:**
- Modify: `pom.xml`（根聚合器 → parent）
- Modify: `rpc-core/pom.xml`、`rpc-transport-netty/pom.xml`、`rpc-registry-local/pom.xml`、`small-rpc-spring/pom.xml`、`small-rpc-spring-boot-starter/pom.xml`、`rpc-examples/pom.xml`（及其三个子模块 pom）

**Interfaces:**
- Consumes: 无
- Produces: 根 pom 作为 parent（`io.github.upowerman:small-rpc:1.0.0`，relativePath 默认 `../pom.xml`），提供 `<properties>`（各依赖版本）与 `<dependencyManagement>`（内部模块 + 三方依赖）；子模块 pom 不再写 `groupId`/`version`/依赖版本。

- [x] **Step 1: 改写根 pom 为 parent**

保留现有 `<modules>` 六项，新增 `<properties>` 与 `<dependencyManagement>`：

```xml
<properties>
    <maven.compiler.source>1.8</maven.compiler.source>
    <maven.compiler.target>1.8</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <junit.version>4.13.2</junit.version>
    <slf4j-api.version>1.7.36</slf4j-api.version>
    <hessian.version>4.0.66</hessian.version>
    <spring-boot.version>2.7.18</spring-boot.version>
    <curator.version>5.6.0</curator.version>
    <jedis.version>4.4.3</jedis.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.upowerman</groupId>
            <artifactId>rpc-core</artifactId>
            <version>${project.version}</version>
        </dependency>
        <!-- rpc-transport-netty / rpc-registry-local / small-rpc-spring /
             small-rpc-spring-boot-starter 同形，逐个列出 -->
        <dependency>
            <groupId>junit</groupId>
            <artifactId>junit</artifactId>
            <version>${junit.version}</version>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
            <version>${slf4j-api.version}</version>
        </dependency>
        <!-- hessian / spring-boot-dependencies(import scope, pom) / curator-framework /
             curator-recipes / jedis 同形 -->
    </dependencies>
</dependencyManagement>
```

spring-boot 统一用 BOM import：`<type>pom</type><scope>import</scope>` 引 `org.springframework.boot:spring-boot-dependencies:${spring-boot.version}`（若既有子模块 pom 已逐个写 spring 依赖版本，改为不写版本，由 BOM 管）。

- [x] **Step 2: 改写六个子模块 pom**

每个子模块 pom：删 `groupId`/`version`（保留 `artifactId`），加：

```xml
<parent>
    <groupId>io.github.upowerman</groupId>
    <artifactId>small-rpc</artifactId>
    <version>1.0.0</version>
</parent>
```

删各模块 `<properties>` 里重复的 compiler/sourceEncoding 项（继承 parent），删依赖里的 `<version>`（由 dependencyManagement 管）。`rpc-examples` 及其三子模块同样处理。

- [x] **Step 3: 验证等价构建**

Run: `mvn test -q -Dgpg.skip=true`
Expected: BUILD SUCCESS，135 测试全绿（数量与基线一致）。

- [x] **Step 4: 验证版本集中生效（防「改了没生效」）**

Run: `mvn help:evaluate -Dexpression=project.version -q -DforceStdout -pl rpc-core` → 输出 `1.0.0`；
Run: `mvn dependency:tree -pl rpc-core | grep -E "slf4j-api|junit"` → 版本分别为 1.7.36 / 4.13.2（证明由 parent 管）。

- [x] **Step 5: Commit**

```bash
git add pom.xml rpc-core/pom.xml rpc-transport-netty/pom.xml rpc-registry-local/pom.xml small-rpc-spring/pom.xml small-rpc-spring-boot-starter/pom.xml rpc-examples/pom.xml rpc-examples/rpc-example-api/pom.xml rpc-examples/rpc-example-client/pom.xml rpc-examples/rpc-example-server/pom.xml
git commit -m "build(rpc2): 根 pom 转 parent + dependencyManagement 收敛版本（D-11）"
```

---

### Task 2: 新抽象 + Local 迁移 + 1.x 形态退役（P3 地基）

**Files:**
- Create: `rpc-core/src/main/java/io/github/upowerman/core/registry/Registry.java`
- Create: `rpc-core/src/main/java/io/github/upowerman/core/registry/ServiceListener.java`
- Create: `rpc-core/src/main/java/io/github/upowerman/core/directory/CachingServiceDirectory.java`
- Delete: `rpc-core/src/main/java/io/github/upowerman/core/registry/BaseServiceRegistry.java`
- Delete: `rpc-core/src/main/java/io/github/upowerman/core/directory/PullServiceDirectory.java`
- Modify: `rpc-registry-local/src/main/java/io/github/upowerman/core/registry/local/LocalServiceRegistry.java`（重写）
- Delete: `rpc-registry-local/src/main/resources/META-INF/small-rpc/io.github.upowerman.core.registry.BaseServiceRegistry`
- Create: `rpc-registry-local/src/main/resources/META-INF/small-rpc/io.github.upowerman.core.registry.Registry`
- Modify: `small-rpc-spring/src/main/java/io/github/upowerman/spring/ReferenceBeanPostProcessor.java`
- Modify: `small-rpc-spring-boot-starter/src/main/java/io/github/upowerman/spring/boot/Rpc2ConsumerAutoConfiguration.java`
- Test: `rpc-core/src/test/java/io/github/upowerman/core/directory/CachingServiceDirectoryTest.java`（新）
- Test: `rpc-registry-local/src/test/java/io/github/upowerman/core/registry/local/LocalServiceRegistryTest.java`（重写）

**Interfaces:**
- Consumes: `ServiceInstance(String address)`（已存在）、`ServiceDirectory`（`List<ServiceInstance> list(String)` + `void subscribe(String)`，签名不变）
- Produces:
  - `interface Registry`（`@Spi("local")`）：`void init(Map<String,String> param)` / `void destroy()` / `void register(String service, ServiceInstance instance)` / `void unregister(String service, ServiceInstance instance)` / `void subscribe(String service, ServiceListener listener)` / `void unsubscribe(String service, ServiceListener listener)`
  - `interface ServiceListener`：`void onChange(List<ServiceInstance> instances)`（全量快照语义，首次订阅立即推一次）
  - `class CachingServiceDirectory implements ServiceDirectory`：`CachingServiceDirectory(Registry registry)`
  - `ReferenceBeanPostProcessor(Transport, Registry, String defaultLoadBalance)`（第二参类型变更）

- [x] **Step 1: 写 Registry 与 ServiceListener**

`Registry` 带 `@Spi("local")`（默认扩展名 local）。javadoc 写明：实现是**进程级 SPI 单例**，`init` 幂等或大声失败须明确（实现者选一种并在 javadoc 注明）；`destroy` 幂等；`subscribe` 建立时立即推当前全量；推送线程中 listener 抛异常不得影响注册中心自身状态。

- [x] **Step 2: 写 CachingServiceDirectory 的失败测试**

覆盖（每条一个 `@Test`）：
1. 未订阅 → `list` 返回空列表（不是 null）；
2. `subscribe` 后 listener 收到推送 → `list` 返回该快照（内容与顺序按实现）；
3. **降级**：先推 A，再让 listener 回调抛异常（用抛异常的 Registry 桩：`onChange` 内部逻辑抛错难构造 → 用「Registry 桩在推送时把 null 传进来」或「listener 收到后缓存替换抛错」二选一，实现者按可测性定，须真实触发 catch 分支）→ `list` 仍返回 A；
4. `subscribe` 幂等：同一 service 调两次 → Registry 桩只收到一次订阅；
5. Registry 的 `subscribe` 抛异常 → `subscribe` 调用本身不抛（记 warn），`list` 返回空；
6. 推送空列表 → 缓存被清空（合法变更，与"降级"区分）。

Run: `mvn test -q -Dgpg.skip=true -pl rpc-core` → 预期编译失败（类不存在）。

- [x] **Step 3: 实现 CachingServiceDirectory**

`ConcurrentHashMap<String, List<ServiceInstance>>` 缓存 + `ConcurrentHashMap.newKeySet()` 已订阅集合；`list` 读缓存（缺省 `Collections.emptyList()`）；`subscribe` 幂等（`add` 成功才真订阅）；内部 `ServiceListener` 的 `onChange` **整体 try/catch**（catch Throwable → `logger.warn` 保留旧值，即降级）；缓存写入 `Collections.unmodifiableList(new ArrayList<>(instances))`（copy-on-write，读无锁）。

Run: `mvn test -q -Dgpg.skip=true -pl rpc-core` → 预期 Step 2 的测试全绿。

- [x] **Step 4: 重写 LocalServiceRegistry 的失败测试**

覆盖：`init` 读 `DIRECT_ADDRESS`；`register` 后 `subscribe` 立即收到全量；register/unregister 触发已订阅 listener 的推送；`DIRECT_ADDRESS` 非空时任意 service 的订阅快照都含它（样例兼容语义）；`unsubscribe` 后不再收到推送；`destroy` 清空且幂等（二次调用不抛）。

Run: `mvn test -q -Dgpg.skip=true -pl rpc-registry-local` → 预期编译失败（旧测试引旧接口）。

- [x] **Step 5: 重写 LocalServiceRegistry**

`ConcurrentHashMap<String, Set<String>>`（service → addresses）+ `ConcurrentHashMap<String, List<ServiceListener>>`；`register/unregister` 改 Map 后遍历 notify（同步，单测简单）；`subscribe` 加 listener 并立即推一次快照；`DIRECT_ADDRESS` 保留语义；`destroy` 清两个 Map（幂等）。删旧 META-INF 登记文件，新增 `META-INF/small-rpc/io.github.upowerman.core.registry.Registry`，内容一行：`local=io.github.upowerman.core.registry.local.LocalServiceRegistry`。

Run: `mvn test -q -Dgpg.skip=true -pl rpc-registry-local` → 全绿。

- [x] **Step 6: 全链切换（删旧接口后的连锁编译）**

- 删 `BaseServiceRegistry.java`、`PullServiceDirectory.java`；
- `ReferenceBeanPostProcessor`：字段/构造第二参改 `Registry`；`resolveDirectory` 从 `new PullServiceDirectory(registry, null)` 改 `new CachingServiceDirectory(registry)`，并在返回前对 `iface.getName()` 调一次 `subscribe`（**消费端订阅点**）；
- `Rpc2ConsumerAutoConfiguration`：`rpc2Registry` bean 返回类型改 `Registry`，`registry.start(param)` 改 `registry.init(param)`；
- 更新受影响的既有测试（`ReferenceBeanPostProcessorTest` 等）。

Run: `mvn test -q -Dgpg.skip=true`（根聚合）→ 全绿，测试数 ≥ 135（新增 Step 2/4 的用例）。

- [x] **Step 7: local 直连样例回归（防行为回退）**

起 server（8090/7081）+ client（8091），`curl "http://127.0.0.1:8091/rpc2/hello?name=p3"` → HTTP 200 且 JSON 正确；`lsof -i :7080` 无监听；验完 kill 精确 PID 并确认端口释放。（起停命令与 P2 T6 相同；用 `lsof -ti :7081 -ti :8091` 取 PID。）

- [x] **Step 8: Commit**

```bash
git add rpc-core/src/main/java/io/github/upowerman/core/registry/Registry.java rpc-core/src/main/java/io/github/upowerman/core/registry/ServiceListener.java rpc-core/src/main/java/io/github/upowerman/core/directory/CachingServiceDirectory.java rpc-core/src/test/java/io/github/upowerman/core/directory/CachingServiceDirectoryTest.java rpc-registry-local/src/main/java/io/github/upowerman/core/registry/local/LocalServiceRegistry.java rpc-registry-local/src/test/java/io/github/upowerman/core/registry/local/LocalServiceRegistryTest.java rpc-registry-local/src/main/resources/META-INF/small-rpc/io.github.upowerman.core.registry.Registry small-rpc-spring/src/main/java/io/github/upowerman/spring/ReferenceBeanPostProcessor.java small-rpc-spring-boot-starter/src/main/java/io/github/upowerman/spring/boot/Rpc2ConsumerAutoConfiguration.java
git rm rpc-core/src/main/java/io/github/upowerman/core/registry/BaseServiceRegistry.java rpc-core/src/main/java/io/github/upowerman/core/directory/PullServiceDirectory.java rpc-registry-local/src/main/resources/META-INF/small-rpc/io.github.upowerman.core.registry.BaseServiceRegistry
git commit -m "feat(rpc2): Registry 订阅抽象 + CachingServiceDirectory 缓存降级，1.x 形态退役"
```

---

### Task 3: rpc-registry-zookeeper（Curator 5.6.0）

**Files:**
- Create: `rpc-registry-zookeeper/pom.xml`
- Create: `rpc-registry-zookeeper/src/main/java/io/github/upowerman/core/registry/zookeeper/ZookeeperRegistry.java`
- Create: `rpc-registry-zookeeper/src/main/resources/META-INF/small-rpc/io.github.upowerman.core.registry.Registry`
- Test: `rpc-registry-zookeeper/src/test/java/io/github/upowerman/core/registry/zookeeper/ZookeeperRegistryTest.java`
- Modify: `pom.xml`（`<modules>` 加 `rpc-registry-zookeeper`）

**Interfaces:**
- Consumes: Task 2 的 `Registry` / `ServiceListener` / `ServiceInstance`
- Produces: `ZookeeperRegistry implements Registry`，SPI 名 **`zookeeper`**；param 键：`zk.connect`（必填）、`zk.namespace`（默认 `small-rpc`）、`zk.session-timeout-ms`（默认 10000）、`zk.connection-timeout-ms`（默认 3000）

- [x] **Step 1: 建模块骨架**

pom：parent 指根 pom；依赖 `rpc-core` + `org.apache.curator:curator-framework:${curator.version}` + `org.apache.curator:curator-recipes:${curator.version}`（PathChildrenCache 在 recipes）+ junit(test)。根 pom `<modules>` 加一项。

Run: `mvn test -q -Dgpg.skip=true -pl rpc-registry-zookeeper -am`（注意：`-am` 必须带，否则从本地仓库解析旧 rpc-core jar）。

- [x] **Step 2: 写失败测试（Assume 探测）**

```java
@Before
public void assumeZkAvailable() {
    try (Socket socket = new Socket()) {
        socket.connect(new InetSocketAddress("127.0.0.1", 2181), 500);
    } catch (IOException e) {
        Assume.assumeTrue("本机 ZK 不可用，跳过集成测试: " + e.getMessage(), false);
    }
}
```

用随机 namespace 隔离：`"small-rpc-test-" + UUID.randomUUID()`。覆盖：`init` → `register` 后 `subscribe` 收到含该实例的快照；再注册第二实例 → 收到 2 个；`unregister` → 收到 1 个；`destroy` 幂等。

Run: `mvn test -q -Dgpg.skip=true -pl rpc-registry-zookeeper -am` → 预期编译失败。

- [x] **Step 3: 实现 ZookeeperRegistry**

节点布局 `/{namespace}/{service}/instances/{address}`（`{service}`/`instances` 持久，`{address}` EPHEMERAL）。`init`：`CuratorFrameworkFactory.builder().connectString(connect).namespace(namespace).sessionTimeoutMs(...).connectionTimeoutMs(...).retryPolicy(new ExponentialBackoffRetry(1000, 3)).build()` + `start()`；`register`：`create().creatingParentsIfNeeded().withMode(EPHEMERAL).forPath(...)`；`subscribe`：`PathChildrenCache`（`start(true)` 触发初始全量）+ 回调里全量列举子节点 → `listener.onChange`（**回调内 try/catch，绝不让异常污染 Curator 线程**）；`destroy`：关所有 cache + `client.close()`（幂等）。

META-INF 登记：`zookeeper=io.github.upowerman.core.registry.zookeeper.ZookeeperRegistry`。

- [x] **Step 4: 跑通并验证隔离**

Run: `mvn test -q -Dgpg.skip=true -pl rpc-registry-zookeeper -am` → 全绿（或 Assume 跳过并可见）。
验证无残留：用 `docker exec <zk容器> zkCli.sh -server localhost:2181 ls /` 确认 `small-rpc-test-*` 节点在 destroy 后为空（临时节点随会话关闭消失；持久父节点可能残留，测试的 `@After` 应删除自己 namespace 的根节点）。

- [x] **Step 5: Commit**

```bash
git add rpc-registry-zookeeper/pom.xml rpc-registry-zookeeper/src pom.xml
git commit -m "feat(rpc2): rpc-registry-zookeeper — EPHEMERAL 实例节点 + PathChildrenCache 推送"
```

---

### Task 4: rpc-registry-redis（Jedis 4.4.3）

**Files:**
- Create: `rpc-registry-redis/pom.xml`
- Create: `rpc-registry-redis/src/main/java/io/github/upowerman/core/registry/redis/RedisRegistry.java`
- Create: `rpc-registry-redis/src/main/resources/META-INF/small-rpc/io.github.upowerman.core.registry.Registry`
- Test: `rpc-registry-redis/src/test/java/io/github/upowerman/core/registry/redis/RedisRegistryTest.java`
- Modify: `pom.xml`（`<modules>` 加 `rpc-registry-redis`）

**Interfaces:**
- Consumes: Task 2 的 `Registry` / `ServiceListener` / `ServiceInstance`
- Produces: `RedisRegistry implements Registry`，SPI 名 **`redis`**；param 键：`redis.host`（默认 localhost）、`redis.port`（默认 6379）、`redis.database`（默认 0）、`redis.timeout-ms`（默认 2000）、`redis.password`（可选）、`redis.key-prefix`（默认 `small-rpc`）、`redis.poll-interval-ms`（默认 3000）

- [x] **Step 1: 建模块骨架**

pom：parent 指根 pom；依赖 `rpc-core` + `redis.clients:jedis:${jedis.version}` + junit(test)。根 pom `<modules>` 加一项。

Run: `mvn test -q -Dgpg.skip=true -pl rpc-registry-redis -am`。

- [x] **Step 2: 写失败测试（Assume PING 探测）**

探测：`Jedis` 连接 127.0.0.1:6379 `ping()` 失败 → `Assume.assumeTrue(..., false)`。随机 key 前缀 `"small-rpc-test-" + UUID.randomUUID()`。覆盖：`init` → `register` → `subscribe` 立即收到 1 个；注册第二实例 → **轮询周期内**收到 2 个（用 `Awaitility` 不引入——用轮询等待循环，超时 5s）；`unregister` → 收到 1 个；`unsubscribe` 后不再推送；`destroy` 幂等且**不留存活线程**（断言调度线程数回落或线程名不可见）。

- [x] **Step 3: 实现 RedisRegistry**

数据结构 `{key-prefix}:registry:{service}` → Set（member=address）。`init`：`JedisPool` + `ScheduledExecutorService`（守护线程，单线程）；`register/unregister`：SADD/SREM；`subscribe`：登记 listener + 立即 `SMEMBERS` 推一次 + 启动该 service 的轮询任务（按 `poll-interval-ms` 比对上次快照，有差异才 `onChange`；**快照比对与推送在调度线程内，用 synchronized 保护同一 service 的状态**）；`unsubscribe`：移除 listener，最后一个 listener 移除时取消该 service 的轮询任务；`destroy`：停调度（`shutdownNow` + awaitTermination）+ `pool.close()`（幂等）。

META-INF 登记：`redis=io.github.upowerman.core.registry.redis.RedisRegistry`。

- [x] **Step 4: 跑通并验证隔离**

Run: `mvn test -q -Dgpg.skip=true -pl rpc-registry-redis -am` → 全绿（或 Assume 跳过）。
验证无残留：`docker exec <redis容器> redis-cli keys 'small-rpc-test-*'` → 空（`@After` 清理）。

- [x] **Step 5: Commit**

```bash
git add rpc-registry-redis/pom.xml rpc-registry-redis/src pom.xml
git commit -m "feat(rpc2): rpc-registry-redis — Set 注册表 + 轮询比对推送"
```

---

### Task 5: provider 注册 + starter 扩展 + 样例多实例 profile

**Files:**
- Modify: `small-rpc-spring-boot-starter/src/main/java/io/github/upowerman/spring/boot/Rpc2ProviderAutoConfiguration.java`
- Modify: `small-rpc-spring-boot-starter/src/main/java/io/github/upowerman/spring/boot/Rpc2Properties.java`（`provider.address` 新键）
- Modify: `small-rpc-spring-boot-starter/pom.xml`（test scope 加 rpc-registry-zookeeper/rpc-registry-redis？**不**——见 Step 4 说明）
- Create: `rpc-examples/rpc-example-server/src/main/resources/application-zookeeper.yml`、`application-redis.yml`
- Create: `rpc-examples/rpc-example-client/src/main/resources/application-zookeeper.yml`、`application-redis.yml`
- Test: `small-rpc-spring-boot-starter/src/test/java/io/github/upowerman/spring/boot/ProviderRegistrationTest.java`（新）
- Modify: `README.md` 演示章节（多实例步骤）

**Interfaces:**
- Consumes: `Registry`（Task 2）、`ZookeeperRegistry`/`RedisRegistry`（Task 3/4，SPI 名 `zookeeper`/`redis`）
- Produces: provider 侧注册（`RpcServer.start()` 后对每个服务接口 `registry.register(iface, new ServiceInstance(selfAddress))`）；`small-rpc.provider.address`（空 = 自动探测本机 IP）；样例 profile。

- [x] **Step 1: 写 provider 注册的失败测试**

用 `ServerSocket(0)` 取空闲端口 + `SpringApplicationBuilder(...).web(WebApplicationType.NONE)` 起上下文（模板见既有 `Rpc2AutoConfigurationTest`），`small-rpc.registry.type=local`，断言：上下文启动后 `LocalServiceRegistry` 里该服务接口已注册本实例地址（通过注入的 `Registry` bean 调 `subscribe` 立即收到的快照断言）。

Run: `mvn test -q -Dgpg.skip=true -pl small-rpc-spring-boot-starter -am` → 预期失败（未注册）。

- [x] **Step 2: 实现 provider 注册**

`Rpc2ProviderAutoConfiguration`：注入 `Registry`（`@ConditionalOnMissingBean(Registry.class)` 提供者用 `SpiLoader.of(Registry.class).getExtension(type)` + `init(param)`，**与 consumer 侧同源**——注意两处 AutoConfiguration 可能同时生效，bean 定义需 `@ConditionalOnMissingBean` 防重复 init）；`rpc2Server` bean 在 `server.start()` 后对每个已注册接口调 `registry.register(iface.getName(), new ServiceInstance(selfAddress))`。`selfAddress` 解析：`small-rpc.provider.address` 非空则用之，否则 `InetAddress.getLocalHost().getHostAddress() + ":" + rpc2Port`（**多网卡环境用本键覆盖**，Review Focus 5）。

Run: 同 Step 1 → 全绿。

- [x] **Step 3: 样例 profile 与演示步骤**

`application-zookeeper.yml`（server）：`small-rpc.registry.type: zookeeper` + `param.zk.connect: localhost:2181`；client 同 + `small-rpc.consumer.enabled: true`、`provider.enabled: false`。`application-redis.yml` 同形（`param.redis.host/port`）。README 写清多实例演示：两台 server（`--server.port=8090 --small-rpc.provider.rpc2-port=7081` 与 `8092/7082`）→ client → kill 一台 → 观察调用仍成功。

- [x] **Step 4: starter 不引注册中心实现依赖（口径钉死）**

`small-rpc-spring-boot-starter` **不得** compile 依赖 `rpc-registry-zookeeper`/`rpc-registry-redis`（保持 starter thin：由使用方按需引入实现模块，SPI 在 classpath 上发现）。样例的 server/client pom 各自加 `rpc-registry-zookeeper` + `rpc-registry-redis` 依赖（compile），使两个 profile 都能跑。

- [x] **Step 5: Commit**

```bash
git add small-rpc-spring-boot-starter/src small-rpc-spring-boot-starter/pom.xml rpc-examples/rpc-example-server/src/main/resources rpc-examples/rpc-example-client/src/main/resources rpc-examples/rpc-example-server/pom.xml rpc-examples/rpc-example-client/pom.xml README.md
git commit -m "feat(rpc2): provider 注册进 Registry + starter registry.type 扩展 + 样例 ZK/Redis profile"
```

---

### Task 6: 全量回归 + 多实例端到端验收 + README 全量重写

**Files:**
- Modify: `README.md`（2.0 全量重写：8 模块表、SPI 三特性、Registry 订阅/降级、三种消费方式、多实例演示）
- Modify: `docs/superpowers/plans/2026-09-24-small-rpc-2-phase3.md`（逐项勾选）

**Interfaces:** Consumes 前五个任务的产物；Produces 验收证据与文档。

- [x] **Step 1: 根聚合全量回归**

Run: `mvn test -q -Dgpg.skip=true` → BUILD SUCCESS；记录测试总数（含 Assume 跳过数，用 `find . -path "*/surefire-reports/*.txt" -exec grep -h "Tests run" {} \;` 聚合）。

- [x] **Step 2: local 直连回归**（P2 行为不回退）

起 server/client（默认 profile）→ `curl "http://127.0.0.1:8091/rpc2/hello?name=p3"` → 200 + JSON 正确；7080 无监听；端口释放。

- [x] **Step 3: ZK 多实例验收**

两台 server（7081/7082，`--spring.profiles.active=zookeeper`）+ client → 连续 curl 20 次，日志显示调用分布在两台 → `kill` 7081 → 等 ZK session 超时（≤10s）→ 再 curl 20 次**全部成功**且全落 7082。记录证据（日志片段/curl 结果统计）。

- [x] **Step 4: Redis 多实例验收**

同 Step 3 用 `--spring.profiles.active=redis`，kill 后等待 ≤ poll-interval + 余量（默认 3s，留 5s）→ 后续调用全落存活实例。

- [x] **Step 5: 降级验收（spec §3 的核心承诺）**

client 已订阅（ZK profile）→ **停掉 ZK 容器**（`docker stop <zk容器>`）→ curl 仍 200（走缓存）→ `docker start <zk容器>` → 等待重连 → 日志可见推送恢复（缓存更新）。**注意：验收后必须把容器恢复运行**（用户环境依赖）。

- [x] **Step 6: README 全量重写 + 计划勾选 + Commit**

```bash
git add README.md docs/superpowers/plans/2026-09-24-small-rpc-2-phase3.md
git commit -m "docs(rpc2): README 2.0 全量重写 + P3 计划勾选"
```

---

## 附：控制器预读发现（派发前修正）

1. **`ServiceInstance` 已有 `(address)` 与 `(address, weight, startTime)` 构造器**——T2/T3/T4 直接可用，无需扩字段（weight/startTime 仍是 3.0 预留）。
2. **`RpcServer` 无「已注册服务」getter**，但 `Rpc2ProviderAutoConfiguration` 自己遍历 `@RpcService` bean，天然知道注册了什么——T5 **不需要**给 RpcServer 加 API。
3. **`Rpc2Properties.registry` 已存在**（`type` 默认 local + `param` Map）——T5 只加 `provider.address` 一个键。
4. **`ReferenceBeanPostProcessor` 已是三参构造**且含 `resolveDirectory` / `resolveLoadBalanceName` 两个包级静态方法（P2 D-1/D-2 产物）——T2 只换类型与目录实现，不动结构。
5. **`registry.type: none` 不做**（补篇 §6 曾提）：local 类型注册进本进程内存 registry 无害且语义统一，纯直连场景用 `@RpcReference.address`。少一个分支少一处配置歧义。
6. **T3 与 T4 串行执行**（不并行）：两者都要改根 `pom.xml` 的 `<modules>`（且 SDD 禁止并行实现者）——T3 完成后才派 T4。
