package io.github.upowerman.spring.boot;

import io.github.upowerman.core.registry.Registry;
import io.github.upowerman.core.spi.SpiLoader;

import java.util.Map;

final class RegistrySupport {

    private RegistrySupport() {
    }

    static Registry obtainRegistry(Rpc2Properties properties) {
        String type = properties.getRegistry().getType();
        Map<String, String> param = properties.getRegistry().toMergedParams();
        validateParams(type, param);
        try {
            Registry registry = SpiLoader.of(Registry.class).getExtension(type);
            registry.init(param);
            return registry;
        } catch (RuntimeException e) {
            String msg = String.format("未能加载注册中心实现 '%s'。请检查 small-rpc.registry.type 配置是否正确，"
                            + "或是否已在 pom.xml 中引入对应注册中心依赖（如 <dependency><groupId>io.github.upowerman</groupId><artifactId>rpc-registry-%s</artifactId></dependency>）。"
                            + "底层错误: %s",
                    type, type, e.getMessage());
            throw new IllegalStateException(msg, e);
        }
    }

    static void validateParams(String type, Map<String, String> param) {
        if (type == null || type.trim().isEmpty()) {
            throw new IllegalStateException("small-rpc.registry.type 不能为空，可选值: local / zookeeper / redis");
        }
        String normalized = type.trim().toLowerCase();
        if ("zookeeper".equals(normalized)) {
            String connect = param != null ? param.get("zk.connect") : null;
            if (connect == null || connect.trim().isEmpty()) {
                throw new IllegalStateException("ZooKeeper 注册中心缺少必要连接地址，请配置 small-rpc.registry.zookeeper.connect (例如: localhost:2181)");
            }
        } else if ("redis".equals(normalized)) {
            String host = param != null ? param.get("redis.host") : null;
            if (host == null || host.trim().isEmpty()) {
                throw new IllegalStateException("Redis 注册中心缺少主机配置，请配置 small-rpc.registry.redis.host (例如: localhost)");
            }
            String portStr = param != null ? param.get("redis.port") : null;
            if (portStr != null && !portStr.trim().isEmpty()) {
                try {
                    int port = Integer.parseInt(portStr.trim());
                    if (port <= 0 || port > 65535) {
                        throw new IllegalStateException("Redis 注册中心端口非法 (必须在 1~65535 之间): " + port);
                    }
                } catch (NumberFormatException e) {
                    throw new IllegalStateException("Redis 注册中心端口格式不合法: " + portStr, e);
                }
            }
        }
    }
}
