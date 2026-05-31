# Zookeeper 服务注册中心使用指南

本文档介绍如何在 Small-RPC 框架中使用 Zookeeper 作为服务注册与发现中心。

## 概述

Zookeeper 注册中心提供了以下能力：
- 服务提供方注册临时节点，进程退出自动摘除
- 服务消费方按服务名发现可用实例
- 支持多实例场景下的负载均衡

## 配置方式

通过 `small-rpc.registry.type` 指定注册中心类型：

```properties
small-rpc.registry.type=zookeeper
small-rpc.zookeeper.connect-string=localhost:2181
small-rpc.zookeeper.namespace=small-rpc
small-rpc.zookeeper.base-path=/services
small-rpc.zookeeper.session-timeout=60000
small-rpc.zookeeper.connection-timeout=15000
```

参数说明：
- `small-rpc.zookeeper.connect-string`：ZK 地址，支持集群（如 `host1:2181,host2:2181`）
- `small-rpc.zookeeper.namespace`：命名空间，避免与其他业务节点冲突
- `small-rpc.zookeeper.base-path`：服务节点根路径
- `small-rpc.zookeeper.session-timeout`：会话超时毫秒
- `small-rpc.zookeeper.connection-timeout`：连接超时毫秒

## 运行示例

1. 启动 Zookeeper：
```bash
docker run -d --name zookeeper -p 2181:2181 zookeeper:3.9
```

2. 启动 provider：
```bash
cd small-rpc-simple/small-rpc-sample-springboot-server
mvn spring-boot:run -Dspring-boot.run.arguments="--small-rpc.registry.type=zookeeper"
```

3. 启动 consumer：
```bash
cd small-rpc-simple/small-rpc-sample-springboot-client
mvn spring-boot:run -Dspring-boot.run.arguments="--small-rpc.registry.type=zookeeper"
```

4. 测试调用：
```bash
curl http://localhost:8091/hello?name=World
```
