# Redis 服务注册中心使用指南

本文档介绍如何在 Small-RPC 框架中使用 Redis 作为服务注册与发现中心。

## 概述

Redis 服务注册中心提供了以下功能：
- **服务注册**: 服务提供方自动注册服务到 Redis
- **服务发现**: 服务消费方从 Redis 发现可用的服务实例
- **健康检查**: 基于 TTL 的服务健康监控机制
- **负载均衡**: 支持多个服务实例的负载均衡
- **故障恢复**: 自动清理不健康的服务实例

## 快速开始

### 1. 启动 Redis 服务

确保 Redis 服务正在运行：
```bash
# 使用 Docker 启动 Redis
docker run -d --name redis -p 6379:6379 redis:latest

# 或者使用本地 Redis 服务
redis-server
```

### 2. 配置服务提供方

#### 激活 Redis Profile
```bash
java -jar server.jar --spring.profiles.active=redis
```

#### 配置 Redis 连接参数 (application-redis.properties)
```properties
# Redis 连接配置
small-rpc.redis.host=localhost
small-rpc.redis.port=6379
small-rpc.redis.password=
small-rpc.redis.database=0
small-rpc.redis.timeout=2000

# RPC 服务端口
small-rpc.provider.port=8080
```

#### Java 配置类
```java
@Configuration
@Profile("redis")
public class RpcRedisProviderConfig {

    @Value("${small-rpc.provider.port}")
    private int port;

    @Value("${small-rpc.redis.host:localhost}")
    private String redisHost;

    @Value("${small-rpc.redis.port:6379}")
    private int redisPort;

    @Bean
    public RpcSpringProviderFactory rpcSpringProviderFactory() {
        RpcSpringProviderFactory providerFactory = new RpcSpringProviderFactory();
        providerFactory.setPort(port);
        providerFactory.setServiceRegistryClass(RedisServiceRegistry.class);

        // 配置 Redis 注册中心参数
        Map<String, String> registryParams = new HashMap<>();
        registryParams.put(RedisServiceRegistry.REDIS_HOST, redisHost);
        registryParams.put(RedisServiceRegistry.REDIS_PORT, String.valueOf(redisPort));
        // 更多配置...

        providerFactory.setServiceRegistryParam(registryParams);
        return providerFactory;
    }
}
```

### 3. 配置服务消费方

#### 配置 Redis 连接参数 (application-redis.properties)
```properties
# Redis 连接配置  
small-rpc.redis.host=localhost
small-rpc.redis.port=6379
small-rpc.redis.password=
small-rpc.redis.database=0
small-rpc.redis.timeout=2000

# 客户端端口
server.port=8081
```

#### Java 配置类
```java
@Configuration
@Profile("redis")
public class RpcRedisInvokerConfig {

    @Value("${small-rpc.redis.host:localhost}")
    private String redisHost;

    @Value("${small-rpc.redis.port:6379}")
    private int redisPort;

    @Bean
    public RpcSpringInvokerFactory rpcInvokerFactory() {
        RpcSpringInvokerFactory invokerFactory = new RpcSpringInvokerFactory();
        invokerFactory.setServiceRegistryClass(RedisServiceRegistry.class);

        // 配置 Redis 注册中心参数
        Map<String, String> registryParams = new HashMap<>();
        registryParams.put(RedisServiceRegistry.REDIS_HOST, redisHost);
        registryParams.put(RedisServiceRegistry.REDIS_PORT, String.valueOf(redisPort));
        // 更多配置...

        invokerFactory.setServiceRegistryParam(registryParams);
        return invokerFactory;
    }
}
```

### 4. 运行示例

#### 启动服务提供方
```bash
cd small-rpc-simple/small-rpc-sample-springboot-server
mvn spring-boot:run -Dspring-boot.run.profiles=redis
```

#### 启动服务消费方
```bash
cd small-rpc-simple/small-rpc-sample-springboot-client
mvn spring-boot:run -Dspring-boot.run.profiles=redis
```

#### 测试服务调用
```bash
curl http://localhost:8081/hello?name=World
```

## 配置参数详解

### Redis 连接配置

| 参数 | 配置键 | 默认值 | 描述 |
|------|--------|--------|------|
| Redis 主机 | `small-rpc.redis.host` | localhost | Redis 服务器地址 |
| Redis 端口 | `small-rpc.redis.port` | 6379 | Redis 服务器端口 |
| Redis 密码 | `small-rpc.redis.password` | (空) | Redis 认证密码 |
| Redis 数据库 | `small-rpc.redis.database` | 0 | Redis 数据库索引 |
| 连接超时 | `small-rpc.redis.timeout` | 2000 | 连接超时时间(毫秒) |

### 高级配置

#### 连接池配置
```java
// 在 RedisServiceRegistry 中可以配置更多连接池参数
JedisPoolConfig poolConfig = new JedisPoolConfig();
poolConfig.setMaxTotal(10);        // 最大连接数
poolConfig.setMaxIdle(5);          // 最大空闲连接数
poolConfig.setMinIdle(1);          // 最小空闲连接数
poolConfig.setTestOnBorrow(true);  // 获取连接时测试
```

#### 健康检查配置
```java
// 服务健康检查间隔（秒）
private static final int HEALTH_CHECK_INTERVAL = 30;
// 服务 TTL（秒）  
private static final int SERVICE_TTL = 60;
```

## Redis 数据结构

### 服务注册数据
```bash
# 服务地址存储 (Redis Set)
rpc:services:com.example.HelloService#1.0 = ["192.168.1.100:8080", "192.168.1.101:8080"]

# 健康检查键 (Redis String with TTL)
rpc:health:com.example.HelloService#1.0:192.168.1.100:8080 = "1642147200000"
rpc:health:com.example.HelloService#1.0:192.168.1.101:8080 = "1642147200000"
```

### Redis 命令示例
```bash
# 查看所有已注册的服务
redis-cli KEYS "rpc:services:*"

# 查看某个服务的所有实例
redis-cli SMEMBERS "rpc:services:com.example.HelloService#1.0"

# 查看服务健康状态
redis-cli KEYS "rpc:health:*"

# 查看某个实例的健康状态和 TTL
redis-cli TTL "rpc:health:com.example.HelloService#1.0:192.168.1.100:8080"
```

## 特性说明

### 1. 自动服务注册
- 服务启动时自动注册到 Redis
- 支持多个服务实例注册到同一个服务名
- 服务停止时自动注销

### 2. 健康检查机制
- 每 30 秒发送一次心跳
- 服务实例 TTL 为 60 秒
- 自动清理不健康的服务实例

### 3. 服务发现
- 实时从 Redis 查询可用服务实例
- 过滤不健康的服务实例
- 支持负载均衡算法

### 4. 故障恢复
- 服务实例故障后自动从注册中心移除
- 新服务实例启动后自动加入服务发现

## 监控和调试

### 启用调试日志
```properties
# 启用 Redis 注册中心调试日志
logging.level.io.github.upowerman.registry=DEBUG
```

### 监控指标
- 服务注册成功/失败次数
- 心跳更新成功/失败次数
- 服务发现查询次数
- 不健康实例清理次数

## 最佳实践

### 1. 生产环境配置
```properties
# 使用 Redis 集群或哨兵模式
small-rpc.redis.host=redis-cluster.example.com
small-rpc.redis.port=6379

# 配置密码认证
small-rpc.redis.password=your-secure-password

# 使用独立数据库
small-rpc.redis.database=1
```

### 2. 高可用部署
- 使用 Redis 主从或集群模式
- 配置 Redis 持久化
- 监控 Redis 服务状态

### 3. 性能优化
- 合理配置连接池大小
- 调整心跳间隔和 TTL
- 使用 Redis pipeline 优化批量操作

## 故障排除

### 常见问题

#### 1. 连接 Redis 失败
```
错误: Redis 连接失败
解决: 检查 Redis 服务是否启动，网络连接是否正常
```

#### 2. 服务发现为空
```
错误: 服务发现返回空结果
解决: 检查服务是否已注册，健康检查是否正常
```

#### 3. 心跳更新失败
```
错误: 心跳更新失败，服务实例被标记为不健康
解决: 检查 Redis 连接，确认网络稳定性
```

### 调试命令
```bash
# 检查 Redis 连接
redis-cli ping

# 查看服务注册状态
redis-cli --scan --pattern "rpc:*"

# 监控 Redis 操作
redis-cli monitor
```

## 与其他注册中心对比

| 特性 | LocalServiceRegistry | RedisServiceRegistry |
|------|---------------------|---------------------|
| 部署复杂度 | 简单 | 中等 |
| 高可用性 | 无 | 支持 |
| 服务发现 | 静态配置 | 动态发现 |
| 健康检查 | 无 | 支持 |
| 扩展性 | 差 | 好 |
| 生产适用 | 否 | 是 |

Redis 服务注册中心为 Small-RPC 框架提供了生产级别的服务注册与发现能力，支持高可用、自动故障恢复和负载均衡等企业级特性。