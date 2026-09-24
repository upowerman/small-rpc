package io.github.upowerman.spring.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/** small-rpc.* 配置项 */
@ConfigurationProperties(prefix = "small-rpc")
public class Rpc2Properties {

    private Provider provider = new Provider();
    private Registry registry = new Registry();

    /** 负载均衡扩展名（SPI 名）；@RpcReference.loadBalance 未指定时用它，空 = 接口 @Spi 默认扩展 */
    private String loadBalance = "";

    public static class Provider {
        /** 2.0 RpcServer 监听端口 */
        private int rpc2Port = 7081;
        /** 注册到注册中心的实例地址（host:port），空则自动探测本机 IP */
        private String address = "";
        public int getRpc2Port() { return rpc2Port; }
        public void setRpc2Port(int rpc2Port) { this.rpc2Port = rpc2Port; }
        public String getAddress() { return address; }
        public void setAddress(String address) { this.address = address; }
    }

    public static class Registry {
        /** 注册中心扩展名（SPI 名）：local（zk/redis 属 P3） */
        private String type = "local";
        /** 注册中心启动参数（local 直连场景放 DIRECT_ADDRESS） */
        private Map<String, String> param = new HashMap<String, String>();
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public Map<String, String> getParam() { return param; }
        public void setParam(Map<String, String> param) { this.param = param; }
    }

    public Provider getProvider() { return provider; }
    public void setProvider(Provider provider) { this.provider = provider; }
    public Registry getRegistry() { return registry; }
    public void setRegistry(Registry registry) { this.registry = registry; }
    public String getLoadBalance() { return loadBalance; }
    public void setLoadBalance(String loadBalance) { this.loadBalance = loadBalance; }
}
