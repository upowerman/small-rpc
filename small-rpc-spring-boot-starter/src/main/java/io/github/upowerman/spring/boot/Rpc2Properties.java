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

    /** 序列化扩展名（SPI 名）：hessian / json；空 = 接口 @Spi 默认扩展（hessian）。服务端按帧 codec 自动识别，两端都可用同一配置切换 */
    private String serializer = "";

    public static class Provider {
        /** 是否启用服务提供端（纯消费端应用请务必设为 false，防端口占用） */
        private boolean enabled = true;
        /** 2.0 RpcServer 监听端口 */
        private int rpc2Port = 7081;
        /** 注册到注册中心的实例地址（host:port），空则自动探测本机 IP */
        private String address = "";
        /** 实例静态权重：加权负载均衡（weightedrandom）按权重比例分流量；1 = 等权 */
        private int weight = 1;
        /** 预热窗口毫秒：&gt;0 时启动后窗口内流量从 0 线性爬坡到满权重；0 = 关闭预热 */
        private long warmupMs = 0;
        /** 业务线程池线程数（请求的反序列化与反射执行在此进行，不占 Netty IO 线程） */
        private int businessThreads = 8;
        /** 业务线程池有界队列容量；0 = 无界。&gt;0 时打满后由提交线程执行（CallerRuns 背压），不拒绝请求 */
        private int businessQueueCapacity = 0;
        /** 优雅停机排空上限（毫秒）：停机时等待在途请求完成的时间；0 = 不等待直接关闭 */
        private long shutdownDrainMs = 10000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getRpc2Port() { return rpc2Port; }
        public void setRpc2Port(int rpc2Port) { this.rpc2Port = rpc2Port; }
        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
        public int getWeight() { return weight; }
        public void setWeight(int weight) { this.weight = weight; }
        public long getWarmupMs() { return warmupMs; }
        public void setWarmupMs(long warmupMs) { this.warmupMs = warmupMs; }
        public int getBusinessThreads() { return businessThreads; }
        public void setBusinessThreads(int businessThreads) { this.businessThreads = businessThreads; }
        public int getBusinessQueueCapacity() { return businessQueueCapacity; }
        public void setBusinessQueueCapacity(int businessQueueCapacity) { this.businessQueueCapacity = businessQueueCapacity; }
        public long getShutdownDrainMs() { return shutdownDrainMs; }
        public void setShutdownDrainMs(long shutdownDrainMs) { this.shutdownDrainMs = shutdownDrainMs; }
    }

    public static class Consumer {
        /** 是否启用服务消费端（纯提供端应用建议设为 false，防无用装配） */
        private boolean enabled = true;
        /** 全局默认单次调用超时毫秒；@RpcReference.timeout 未设置（-1）时生效 */
        private long timeoutMs = 3000;
        /** 全局默认失败重试次数（额外尝试数，0 = 不重试）；@RpcReference.retries 未设置（-1）时生效 */
        private int retries = 1;
        /** 客户端在途请求上限；0 = 不限。超限以 CLIENT_BUSY 快速失败（3.0 治理背压） */
        private int maxInFlight = 0;
        /** 消费端治理配置（3.0：默认整体关闭，opt-in） */
        private Governance governance = new Governance();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }
        public int getRetries() { return retries; }
        public void setRetries(int retries) { this.retries = retries; }
        public int getMaxInFlight() { return maxInFlight; }
        public void setMaxInFlight(int maxInFlight) { this.maxInFlight = maxInFlight; }
        public Governance getGovernance() { return governance; }
        public void setGovernance(Governance governance) { this.governance = governance; }
    }

    /** 3.0 消费端治理：熔断 + 限流，默认全部关闭 */
    public static class Governance {
        private CircuitBreaker circuitBreaker = new CircuitBreaker();
        private RateLimit rateLimit = new RateLimit();

        public CircuitBreaker getCircuitBreaker() { return circuitBreaker; }
        public void setCircuitBreaker(CircuitBreaker circuitBreaker) { this.circuitBreaker = circuitBreaker; }
        public RateLimit getRateLimit() { return rateLimit; }
        public void setRateLimit(RateLimit rateLimit) { this.rateLimit = rateLimit; }

        public static class CircuitBreaker {
            /** 熔断开关（opt-in：false = 不装配熔断 Filter） */
            private boolean enabled = false;
            /** 错误率跳闸阈值 [0,1]；0 = 该条件停用 */
            private double errorRateThreshold = 0.5;
            /** 慢调用率跳闸阈值 [0,1]；0 = 该条件停用 */
            private double slowCallRateThreshold = 0.5;
            /** 慢调用阈值毫秒；0 = 按调用超时预算 80% 推导（预算也不可得则该条件停用） */
            private long slowRtMs = 0;
            /** 窗口内最少样本数（防冷启动误跳） */
            private int minCalls = 20;
            /** 统计滑动窗口毫秒 */
            private long windowMs = 10000;
            /** 熔断打开持续时间毫秒 */
            private long openMs = 10000;
            /** 半开探测名额 */
            private int halfOpenProbes = 1;

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public double getErrorRateThreshold() { return errorRateThreshold; }
            public void setErrorRateThreshold(double errorRateThreshold) { this.errorRateThreshold = errorRateThreshold; }
            public double getSlowCallRateThreshold() { return slowCallRateThreshold; }
            public void setSlowCallRateThreshold(double slowCallRateThreshold) { this.slowCallRateThreshold = slowCallRateThreshold; }
            public long getSlowRtMs() { return slowRtMs; }
            public void setSlowRtMs(long slowRtMs) { this.slowRtMs = slowRtMs; }
            public int getMinCalls() { return minCalls; }
            public void setMinCalls(int minCalls) { this.minCalls = minCalls; }
            public long getWindowMs() { return windowMs; }
            public void setWindowMs(long windowMs) { this.windowMs = windowMs; }
            public long getOpenMs() { return openMs; }
            public void setOpenMs(long openMs) { this.openMs = openMs; }
            public int getHalfOpenProbes() { return halfOpenProbes; }
            public void setHalfOpenProbes(int halfOpenProbes) { this.halfOpenProbes = halfOpenProbes; }
        }

        public static class RateLimit {
            /** 限流开关（opt-in） */
            private boolean enabled = false;
            /** 每资源令牌桶速率（每秒）；0 = 不限速 */
            private int qps = 0;
            /** 每资源并发上限（信号量）；0 = 不限并发 */
            private int maxConcurrent = 0;

            public boolean isEnabled() { return enabled; }
            public void setEnabled(boolean enabled) { this.enabled = enabled; }
            public int getQps() { return qps; }
            public void setQps(int qps) { this.qps = qps; }
            public int getMaxConcurrent() { return maxConcurrent; }
            public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }
        }
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
    public String getSerializer() { return serializer; }
    public void setSerializer(String serializer) { this.serializer = serializer; }
}
