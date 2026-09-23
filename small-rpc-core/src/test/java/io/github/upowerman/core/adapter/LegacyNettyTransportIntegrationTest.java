package io.github.upowerman.core.adapter;

import io.github.upowerman.core.cluster.FailoverClusterInvoker;
import io.github.upowerman.core.directory.PullServiceDirectory;
import io.github.upowerman.core.filter.Filter;
import io.github.upowerman.core.filter.TraceFilter;
import io.github.upowerman.core.invoker.RemoteInvoker;
import io.github.upowerman.core.loadbalance.RandomLoadBalancer;
import io.github.upowerman.core.proxy.RpcProxyFactory;
import io.github.upowerman.core.serialize.LegacyHessianSerializer;
import io.github.upowerman.core.transport.Transport;
import io.github.upowerman.invoker.RpcInvokerFactory;
import io.github.upowerman.net.base.NetEnum;
import io.github.upowerman.provider.RpcProviderFactory;
import io.github.upowerman.registry.impl.LocalServiceRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

public class LegacyNettyTransportIntegrationTest {

    public interface EchoService {
        EchoDTO echo(EchoDTO dto);
    }

    public static class EchoDTO implements Serializable {
        private static final long serialVersionUID = 1L;
        private String msg;

        public EchoDTO() {
        }

        public EchoDTO(String msg) {
            this.msg = msg;
        }

        public String getMsg() {
            return msg;
        }

        public void setMsg(String msg) {
            this.msg = msg;
        }
    }

    public static class EchoServiceImpl implements EchoService {
        @Override
        public EchoDTO echo(EchoDTO dto) {
            return new EchoDTO("echo:" + dto.getMsg());
        }
    }

    private static final int PORT = 18080;

    private RpcProviderFactory providerFactory;

    @Before
    public void startProvider() throws Exception {
        providerFactory = new RpcProviderFactory();
        providerFactory.setNetType(NetEnum.NETTY);
        providerFactory.setSerializer(new io.github.upowerman.serialize.HessianSerializer());
        providerFactory.setPort(PORT);
        // 校验配置并补齐线程池默认参数（core=60/max=300），不调用则池参数为 0
        providerFactory.initConfig();
        providerFactory.addService(EchoService.class.getName(), null, new EchoServiceImpl());
        providerFactory.start();
    }

    @After
    public void stopProvider() throws Exception {
        if (providerFactory != null) {
            providerFactory.stop();
        }
    }

    @Test
    public void newChainCallsRealNettyServer() throws Exception {
        LocalServiceRegistry registry = new LocalServiceRegistry();
        Map<String, String> param = new HashMap<String, String>();
        param.put(LocalServiceRegistry.DIRECT_ADDRESS, "127.0.0.1:" + PORT);
        registry.start(param);

        RpcInvokerFactory invokerFactory = new RpcInvokerFactory();
        Transport transport = new LegacyNettyTransport(
                new io.github.upowerman.serialize.HessianSerializer(), invokerFactory, null);

        FailoverClusterInvoker cluster = new FailoverClusterInvoker(
                new PullServiceDirectory(registry, null), new RandomLoadBalancer(),
                new RemoteInvoker(transport, EchoService.class), 1, 5000);

        EchoService echoService = new RpcProxyFactory<EchoService>(EchoService.class,
                Collections.<Filter>singletonList(new TraceFilter()), cluster).getProxy();

        assertEquals("echo:world", echoService.echo(new EchoDTO("world")).getMsg());
        registry.stop();
    }
}