## 描述:
     small-rpc 是一款基于netty+hessian的精简版的RPC  
     由于未在生产实践使用，只适合学习使用
## 架构简图:
![image](https://github.com/gwself/small-rpc/blob/master/pic/first.png)

## 工程结构：
>           
            small-rpc
            ├── small-rpc-core --核心模块
            ├── small-rpc-sample --spring boot demo
            ├    ├── small-rpc-sample-springboot-api    -- 接口api jar
            ├    ├── small-rpc-sample-springboot-client -- 调用方hello world
            └──  └── small-rpc-sample-springboot-server -- 服务提供方hello world
  
## 使用示例:
      由于目前没有上传到maven仓库  需要自行打包引入项目使用
      1. 下载源码进行打包 mvn clean package
      2. 把上述包 small-rpc-core-1.x.jar 引入项目
      3. provider 方配置如下：
>
                    `
                    @Configuration
                    public class RpcProviderConfig {

                        private Logger logger = LoggerFactory.getLogger(RpcProviderConfig.class);

                        @Value("${small-rpc.provider.port}")
                        private int port;

                        @Bean
                        public RpcSpringProviderFactory rpcSpringProviderFactory() {
                            RpcSpringProviderFactory providerFactory = new RpcSpringProviderFactory();
                            providerFactory.setPort(port);
                            providerFactory.setCorePoolSize(10);
                            providerFactory.setMaxPoolSize(20);
                            providerFactory.setServiceRegistryClass(LocalServiceRegistry.class);
                            providerFactory.setServiceRegistryParam(Collections.EMPTY_MAP);
                            return providerFactory;
                        }
                    }
                    `
