# Maven POM 开源规范与依赖治理实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按照开源软件标准全面优化 Small-RPC 的 POM 配置与包依赖结构，补齐元数据、元数据处理器、替换细粒度 Netty 依赖、消除 Demo 冗余并配置统一插件管理与 release profile。

**Architecture:** 
1. 根 POM 作为全局治理中心：补充开源元数据、统一 `<pluginManagement>` 锁定构建插件、配置 `release` 编译分发 profile 并勘误 BOM 注释；
2. Starter 规范化：引入 `spring-boot-configuration-processor` 自动生成配置元数据；
3. 传输层解耦精简：用 Netty 细粒度核心组件替换 `netty-all`；
4. 示例模块规范：移除自定义 `<repositories>`，移除重复依赖声明，增加跳过发布配置。

**Tech Stack:** Maven 3.9+, Java 8, Netty 4.1.108.Final, Spring Boot 2.7.18.

**Spec:** 本计划基于用户对 POM 审查六项治理决议的执行要求。

## Global Constraints
- 保证构建 100% 成功，所有现有测试用例继续全绿。
- 不引入未在 parent 锁定的随意第三方版本。
- 绝不破坏 `feature/rpc2` 现有功能与架构约定。

## Review Focus
1. 细粒度 Netty 替换后，是否导致运行时缺少 class（例如 codec 或 common）？通过 `mvn test` 验证。
2. Starter 引入 `spring-boot-configuration-processor` 后，是否正确生成了 `spring-configuration-metadata.json`？
3. 示例模块移除 `rpc-core` 和 `rpc-transport-netty` 后，是否能正常编译打包？
4. `release` profile 激活时，Javadoc 是否会因为严格模式报错？（需配置 `<doclint>none</doclint>`）。
5. 根 POM 中的 BOM 注释是否修正为准确的 Maven 仲裁规则。

---

### Task 1: [P0] 根 POM 完善开源元数据

**Files:**
- Modify: `pom.xml:6-10`

- [ ] **Step 1: 在根 POM 增加开源元数据**

在 `<groupId>`、`<artifactId>`、`<version>`、`<packaging>` 之后补充：
```xml
    <name>Small RPC</name>
    <description>A lightweight, extensible, high-performance Java RPC framework.</description>
    <url>https://github.com/upowerman/small-rpc</url>

    <licenses>
        <license>
            <name>Apache License, Version 2.0</name>
            <url>https://www.apache.org/licenses/LICENSE-2.0.txt</url>
            <distribution>repo</distribution>
        </license>
    </licenses>

    <developers>
        <developer>
            <id>upowerman</id>
            <name>upowerman</name>
            <url>https://github.com/upowerman</url>
        </developer>
    </developers>

    <scm>
        <connection>scm:git:https://github.com/upowerman/small-rpc.git</connection>
        <developerConnection>scm:git:git@github.com:upowerman/small-rpc.git</developerConnection>
        <url>https://github.com/upowerman/small-rpc</url>
    </scm>
```

- [ ] **Step 2: 验证根 POM 语法**

运行：`mvn validate`
预期：BUILD SUCCESS

---

### Task 2: [P0] Starter 模块补充 `spring-boot-configuration-processor`

**Files:**
- Modify: `small-rpc-spring-boot-starter/pom.xml:28-30`

- [ ] **Step 1: 在 starter pom.xml 增加 optional 依赖**

在 `spring-boot-autoconfigure` 依赖项下方添加：
```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>
```

- [ ] **Step 2: 编译 starter 并验证元数据生成**

运行：`mvn clean compile -pl small-rpc-spring-boot-starter`
预期：
- BUILD SUCCESS
- `small-rpc-spring-boot-starter/target/classes/META-INF/spring-configuration-metadata.json` 文件成功生成且包含 `small-rpc` 前缀的配置项。

---

### Task 3: [P1] `rpc-transport-netty` 替换 `netty-all` 为细粒度模块

**Files:**
- Modify: `rpc-transport-netty/pom.xml:21-24`

- [ ] **Step 1: 替换 netty-all 依赖**

将 `rpc-transport-netty/pom.xml` 中的：
```xml
        <dependency>
            <groupId>io.netty</groupId>
            <artifactId>netty-all</artifactId>
        </dependency>
```
替换为 5 个细粒度组件：
```xml
        <dependency>
            <groupId>io.netty</groupId>
            <artifactId>netty-transport</artifactId>
        </dependency>
        <dependency>
            <groupId>io.netty</groupId>
            <artifactId>netty-buffer</artifactId>
        </dependency>
        <dependency>
            <groupId>io.netty</groupId>
            <artifactId>netty-codec</artifactId>
        </dependency>
        <dependency>
            <groupId>io.netty</groupId>
            <artifactId>netty-handler</artifactId>
        </dependency>
        <dependency>
            <groupId>io.netty</groupId>
            <artifactId>netty-common</artifactId>
        </dependency>
```

- [ ] **Step 2: 验证编译与单测**

运行：`mvn clean test -pl rpc-transport-netty`
预期：BUILD SUCCESS，所有测试全绿通过。

---

### Task 4: [P1] `rpc-examples` 规范化：移除自定义 repositories、移除冗余依赖、配置跳过部署

**Files:**
- Modify: `rpc-examples/pom.xml`
- Modify: `rpc-examples/rpc-example-server/pom.xml`
- Modify: `rpc-examples/rpc-example-client/pom.xml`

- [ ] **Step 1: 清理 `rpc-examples/pom.xml`**

删除 `<repositories>` 块，增加 `<properties><maven.deploy.skip>true</maven.deploy.skip></properties>`。

- [ ] **Step 2: 清理 `rpc-example-server/pom.xml` 冗余依赖**

删除重复声明的 `rpc-core` 和 `rpc-transport-netty`。

- [ ] **Step 3: 清理 `rpc-example-client/pom.xml` 冗余依赖**

删除重复声明的 `rpc-core` 和 `rpc-transport-netty`。

- [ ] **Step 4: 验证 examples 编译**

运行：`mvn clean compile -pl rpc-examples/rpc-example-api,rpc-examples/rpc-example-server,rpc-examples/rpc-example-client`
预期：BUILD SUCCESS。

---

### Task 5: [P2] 根 POM `<build><pluginManagement>` 锁定核心插件版本，子模块清理重复版本

**Files:**
- Modify: `pom.xml`
- Modify: `rpc-examples/rpc-example-server/pom.xml`
- Modify: `rpc-examples/rpc-example-client/pom.xml`

- [ ] **Step 1: 在根 POM 声明 `<build><pluginManagement>`**

锁定以下核心插件版本：
- `maven-compiler-plugin`: 3.11.0 (绑定 source/target/encoding)
- `maven-surefire-plugin`: 3.2.5
- `maven-jar-plugin`: 3.3.0
- `spring-boot-maven-plugin`: ${spring-boot.version}

- [ ] **Step 2: 子模块清理版本号**

在 `rpc-example-server/pom.xml` 和 `rpc-example-client/pom.xml` 的 `spring-boot-maven-plugin` 中移除 `<version>${spring-boot.version}</version>`。

- [ ] **Step 3: 验证构建插件继承**

运行：`mvn clean compile`
预期：所有模块成功编译。

---

### Task 6: [P2] 根 POM 配置 `release` profile 与勘误注释

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: 配置 `release` profile**

在根 POM 中添加 `<profiles>`，包含 `maven-source-plugin` (3.3.0)、`maven-javadoc-plugin` (3.6.3，带 `<doclint>none</doclint>`)、`maven-gpg-plugin` (3.1.0)。

- [ ] **Step 2: 勘误 Netty BOM 顺序注释**

将“import 的 BOM 之间后者覆盖前者”勘误为符合 Maven 标准的“First-Declared Wins（先声明者优先生效）”。

- [ ] **Step 3: 验证 source 插件生成**

运行：`mvn package -DskipTests -Prelease -Dgpg.skip=true`
预期：BUILD SUCCESS，各子模块 `target/` 目录下生成 `-sources.jar` 与 `-javadoc.jar`。

---

### Task 7: [Verification] 全工程完整验证与单测回归

- [ ] **Step 1: 全工程执行测试**

运行：`mvn clean test`
预期：12 个模块全部 SUCCESS，所有单元测试保持 100% 通过。

- [ ] **Step 2: 重新运行 dependency:analyze**

运行：`mvn dependency:analyze`
预期：`rpc-transport-netty` 的 `io.netty:netty-all` 警告消除。
