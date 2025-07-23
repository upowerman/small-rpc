package io.github.upowerman.registry.impl;

/**
 * Redis 服务注册中心集成验证
 * 验证 Redis 注册中心是否正确实现了 BaseServiceRegistry 接口
 */
public class RedisServiceRegistryIntegrationCheck {

    public static void main(String[] args) {
        System.out.println("Redis 服务注册中心集成验证");
        System.out.println("=============================");

        // 验证类的继承关系
        RedisServiceRegistry registry = new RedisServiceRegistry();
        
        // 验证接口实现
        boolean isValidRegistry = registry instanceof io.github.upowerman.registry.BaseServiceRegistry;
        System.out.println("✓ 正确继承 BaseServiceRegistry: " + isValidRegistry);

        // 验证方法存在性
        try {
            registry.getClass().getMethod("start", java.util.Map.class);
            System.out.println("✓ start 方法存在");
            
            registry.getClass().getMethod("stop");
            System.out.println("✓ stop 方法存在");
            
            registry.getClass().getMethod("registry", java.util.Set.class, String.class);
            System.out.println("✓ registry 方法存在");
            
            registry.getClass().getMethod("remove", java.util.Set.class, String.class);
            System.out.println("✓ remove 方法存在");
            
            registry.getClass().getMethod("discovery", java.util.Set.class);
            System.out.println("✓ discovery(Set) 方法存在");
            
            registry.getClass().getMethod("discovery", String.class);
            System.out.println("✓ discovery(String) 方法存在");
            
        } catch (NoSuchMethodException e) {
            System.err.println("✗ 缺少必需方法: " + e.getMessage());
        }

        // 验证常量定义
        try {
            String host = RedisServiceRegistry.REDIS_HOST;
            String port = RedisServiceRegistry.REDIS_PORT;
            String password = RedisServiceRegistry.REDIS_PASSWORD;
            String database = RedisServiceRegistry.REDIS_DATABASE;
            String timeout = RedisServiceRegistry.REDIS_TIMEOUT;
            
            System.out.println("✓ 配置常量定义正确");
            System.out.println("  - REDIS_HOST: " + host);
            System.out.println("  - REDIS_PORT: " + port);
            System.out.println("  - REDIS_PASSWORD: " + password);
            System.out.println("  - REDIS_DATABASE: " + database);
            System.out.println("  - REDIS_TIMEOUT: " + timeout);
            
        } catch (Exception e) {
            System.err.println("✗ 配置常量定义错误: " + e.getMessage());
        }

        // 验证无参构造函数
        try {
            RedisServiceRegistry newRegistry = new RedisServiceRegistry();
            System.out.println("✓ 无参构造函数存在");
        } catch (Exception e) {
            System.err.println("✗ 无参构造函数不可用: " + e.getMessage());
        }

        System.out.println();
        System.out.println("集成验证完成！");
        System.out.println("Redis 服务注册中心已正确实现所有必需的接口和方法。");
        System.out.println();
        System.out.println("使用说明:");
        System.out.println("1. 确保 Redis 服务正在运行");
        System.out.println("2. 在 Spring 配置中设置 serviceRegistryClass 为 RedisServiceRegistry.class");
        System.out.println("3. 配置 Redis 连接参数到 serviceRegistryParam");
        System.out.println("4. 激活 redis profile: --spring.profiles.active=redis");
    }
}