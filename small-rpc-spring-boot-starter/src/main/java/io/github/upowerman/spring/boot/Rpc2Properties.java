package io.github.upowerman.spring.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/** small-rpc.* 配置项 */
@ConfigurationProperties(prefix = "small-rpc")
public class Rpc2Properties {

    private Provider provider = new Provider();
    private Consumer consumer = new Consumer();
    private Registry registry = new Registry();

    /** 负载均衡扩展名（SPI 名）；@RpcReference.loadBalance 未指定时用它，空 = 接口 @Spi 默认扩展 */
    private String loadBalance = "";

    public static class Provider {
        /** 是否启用服务提供端（纯消费端应用请务必设为 false，防端口占用） */
        private boolean enabled = true;
        /** 2.0 RpcServer 监听端口 */
        private int rpc2Port = 7081;
        /** 注册到注册中心的实例地址（host:port），空则自动探测本机 IP */
        private String address = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getRpc2Port() { return rpc2Port; }
        public void setRpc2Port(int rpc2Port) { this.rpc2Port = rpc2Port; }
        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
    }

    public static class Consumer {
        /** 是否启用服务消费端（纯提供端应用建议设为 false，防无用装配） */
        private boolean enabled = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class Registry {
        /** 注册中心扩展名（SPI 名）：local / zookeeper / redis */
        private String type = "local";
        /** 注册中心启动参数通用字典（向下兼容） */
        private Map<String, String> param = new HashMap<String, String>();

        /** ZooKeeper 注册中心配置 */
        private ZookeeperProperties zookeeper = new ZookeeperProperties();
        /** Redis 注册中心配置 */
        private RedisProperties redis = new RedisProperties();
        /** 本地直连注册中心配置 */
        private LocalProperties local = new LocalProperties();

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public Map<String, String> getParam() { return param; }
        public void setParam(Map<String, String> param) { this.param = param; }

        public ZookeeperProperties getZookeeper() { return zookeeper; }
        public void setZookeeper(ZookeeperProperties zookeeper) { this.zookeeper = zookeeper; }
        public RedisProperties getRedis() { return redis; }
        public void setRedis(RedisProperties redis) { this.redis = redis; }
        public LocalProperties getLocal() { return local; }
        public void setLocal(LocalProperties local) { this.local = local; }

        /**
         * 将强类型配置属性与通用 param 字典合并为底层 SPI 期望的扁平参数表。
         * 通用 param 字典中的同名键具备更高优先级（可覆盖强类型默认值）。
         */
        public Map<String, String> toMergedParams() {
            Map<String, String> merged = new HashMap<String, String>();
            if (zookeeper != null) {
                zookeeper.fillParams(merged);
            }
            if (redis != null) {
                redis.fillParams(merged);
            }
            if (local != null) {
                local.fillParams(merged);
            }
            if (param != null) {
                merged.putAll(param);
            }
            return merged;
        }
    }

    public static class ZookeeperProperties {
        /** ZooKeeper 服务器连接串（多个以英文逗号分隔，如 localhost:2181） */
        private String connect = "localhost:2181";
        /** ZooKeeper 根命名空间节点路径 */
        private String namespace = "small-rpc";
        /** ZooKeeper 会话超时时间（毫秒） */
        private int sessionTimeoutMs = 10000;
        /** ZooKeeper 连接建立超时时间（毫秒） */
        private int connectionTimeoutMs = 3000;

        public String getConnect() { return connect; }
        public void setConnect(String connect) { this.connect = connect; }
        public String getNamespace() { return namespace; }
        public void setNamespace(String namespace) { this.namespace = namespace; }
        public int getSessionTimeoutMs() { return sessionTimeoutMs; }
        public void setSessionTimeoutMs(int sessionTimeoutMs) { this.sessionTimeoutMs = sessionTimeoutMs; }
        public int getConnectionTimeoutMs() { return connectionTimeoutMs; }
        public void setConnectionTimeoutMs(int connectionTimeoutMs) { this.connectionTimeoutMs = connectionTimeoutMs; }

        void fillParams(Map<String, String> target) {
            if (connect != null) {
                target.put("zk.connect", connect.trim());
            }
            if (namespace != null) {
                target.put("zk.namespace", namespace.trim());
            }
            target.put("zk.session-timeout-ms", String.valueOf(sessionTimeoutMs));
            target.put("zk.connection-timeout-ms", String.valueOf(connectionTimeoutMs));
        }
    }

    public static class RedisProperties {
        /** Redis 服务器主机名或 IP 地址 */
        private String host = "localhost";
        /** Redis 服务器端口 */
        private int port = 6379;
        /** Redis 数据库索引（默认为 0） */
        private int database = 0;
        /** Redis 连接与读取超时时间（毫秒） */
        private int timeoutMs = 2000;
        /** Redis 认证密码（无密码留空） */
        private String password = "";
        /** Redis 注册表集合 Key 前缀 */
        private String keyPrefix = "small-rpc:registry:";
        /** Redis 注册表差量比对拉取周期（毫秒） */
        private long pollIntervalMs = 3000;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public int getDatabase() { return database; }
        public void setDatabase(int database) { this.database = database; }
        public int getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getKeyPrefix() { return keyPrefix; }
        public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
        public long getPollIntervalMs() { return pollIntervalMs; }
        public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }

        void fillParams(Map<String, String> target) {
            if (host != null) {
                target.put("redis.host", host.trim());
            }
            target.put("redis.port", String.valueOf(port));
            target.put("redis.database", String.valueOf(database));
            target.put("redis.timeout-ms", String.valueOf(timeoutMs));
            if (password != null && !password.trim().isEmpty()) {
                target.put("redis.password", password.trim());
            }
            if (keyPrefix != null) {
                target.put("redis.key-prefix", keyPrefix.trim());
            }
            target.put("redis.poll-interval-ms", String.valueOf(pollIntervalMs));
        }
    }

    public static class LocalProperties {
        /** 本地直连调试的目标服务实例地址（host:port） */
        private String directAddress = "";

        public String getDirectAddress() { return directAddress; }
        public void setDirectAddress(String directAddress) { this.directAddress = directAddress; }

        void fillParams(Map<String, String> target) {
            if (directAddress != null && !directAddress.trim().isEmpty()) {
                target.put("DIRECT_ADDRESS", directAddress.trim());
            }
        }
    }

    public Provider getProvider() { return provider; }
    public void setProvider(Provider provider) { this.provider = provider; }
    public Consumer getConsumer() { return consumer; }
    public void setConsumer(Consumer consumer) { this.consumer = consumer; }
    public Registry getRegistry() { return registry; }
    public void setRegistry(Registry registry) { this.registry = registry; }
    public String getLoadBalance() { return loadBalance; }
    public void setLoadBalance(String loadBalance) { this.loadBalance = loadBalance; }
}
