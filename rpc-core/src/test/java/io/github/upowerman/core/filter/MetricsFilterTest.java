package io.github.upowerman.core.filter;

import io.github.upowerman.core.cluster.FailoverClusterInvoker;
import io.github.upowerman.core.directory.CachingServiceDirectory;
import io.github.upowerman.core.directory.ServiceInstance;
import io.github.upowerman.core.invoker.RemoteInvoker;
import io.github.upowerman.core.loadbalance.RandomLoadBalancer;
import io.github.upowerman.core.metrics.RpcMetrics;
import io.github.upowerman.core.provider.ReflectiveInvoker;
import io.github.upowerman.core.proxy.RpcProxyFactory;
import io.github.upowerman.core.registry.Registry;
import io.github.upowerman.core.registry.ServiceListener;
import io.github.upowerman.core.transport.InMemoryTransport;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/** C8：MetricsFilter 挂在消费端链上，成功/失败调用都按 service#method 计数。 */
public class MetricsFilterTest {

    public interface EchoService {
        String echo(String msg);
    }

    public static class EchoServiceImpl implements EchoService {
        @Override
        public String echo(String msg) {
            if ("boom".equals(msg)) {
                throw new IllegalStateException("biz error");
            }
            return "echo:" + msg;
        }
    }

    @Before
    public void reset() {
        RpcMetrics.getInstance().reset();
    }

    private static Registry stubRegistry(String... addrs) {
        final List<ServiceInstance> instances = new ArrayList<ServiceInstance>();
        for (String addr : addrs) {
            instances.add(new ServiceInstance(addr));
        }
        return new Registry() {
            @Override
            public void init(Map<String, String> param) {
            }

            @Override
            public void destroy() {
            }

            @Override
            public void register(String service, ServiceInstance instance) {
            }

            @Override
            public void unregister(String service, ServiceInstance instance) {
            }

            @Override
            public void subscribe(String service, ServiceListener listener) {
                listener.onChange(new ArrayList<ServiceInstance>(instances));
            }

            @Override
            public void unsubscribe(String service, ServiceListener listener) {
            }
        };
    }

    private EchoService proxyWithMetrics() {
        InMemoryTransport transport = new InMemoryTransport();
        transport.register("127.0.0.1:7080",
                new ReflectiveInvoker(EchoService.class, new EchoServiceImpl()));
        CachingServiceDirectory directory = new CachingServiceDirectory(stubRegistry("127.0.0.1:7080"));
        directory.subscribe(EchoService.class.getName());
        FailoverClusterInvoker cluster = new FailoverClusterInvoker(
                directory, new RandomLoadBalancer(),
                new RemoteInvoker(transport, EchoService.class), 0, 1000);
        return new RpcProxyFactory<EchoService>(EchoService.class,
                Arrays.<Filter>asList(new TraceFilter(), new MetricsFilter()), cluster).getProxy();
    }

    @Test
    public void successAndFailureAreCountedPerMethod() {
        EchoService echo = proxyWithMetrics();
        echo.echo("a");
        echo.echo("b");
        try {
            echo.echo("boom");
        } catch (Exception expected) {
            // 业务失败也必须被计数
        }

        List<RpcMetrics.Stat> stats = RpcMetrics.getInstance().snapshot();
        assertEquals(1, stats.size());
        RpcMetrics.Stat stat = stats.get(0);
        assertEquals(EchoService.class.getName() + "#echo", stat.key());
        assertEquals(3L, stat.total());
        assertEquals(2L, stat.success());
        assertEquals(1L, stat.failure());
    }
}
