package io.github.upowerman.core.filter;

import io.github.upowerman.core.cluster.FailoverClusterInvoker;
import io.github.upowerman.core.directory.CachingServiceDirectory;
import io.github.upowerman.core.directory.ServiceInstance;
import io.github.upowerman.core.governance.CircuitBreakerRule;
import io.github.upowerman.core.governance.DefaultCircuitBreaker;
import io.github.upowerman.core.invoker.RemoteInvoker;
import io.github.upowerman.core.loadbalance.RandomLoadBalancer;
import io.github.upowerman.core.metrics.RpcMetrics;
import io.github.upowerman.core.provider.ReflectiveInvoker;
import io.github.upowerman.core.proxy.RpcProxyFactory;
import io.github.upowerman.core.registry.Registry;
import io.github.upowerman.core.registry.ServiceListener;
import io.github.upowerman.core.transport.InMemoryTransport;
import io.github.upowerman.exception.RpcException;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** 熔断 Filter：错误率跳闸后 CIRCUIT_OPEN 快速失败；半开恢复后正常服务 */
public class CircuitBreakerFilterTest {

    public interface EchoService {
        String echo(String msg);
    }

    /** 可切换故障的实现：volatile 开关控制 echo 是否抛错 */
    public static class FlakyEchoService implements EchoService {
        public volatile boolean broken;

        @Override
        public String echo(String msg) {
            if (broken) {
                throw new IllegalStateException("injected failure");
            }
            return "echo:" + msg;
        }
    }

    private final AtomicLong clock = new AtomicLong(0);

    @Before
    public void reset() {
        RpcMetrics.getInstance().reset();
    }

    private EchoService proxy(FlakyEchoService impl) {
        InMemoryTransport transport = new InMemoryTransport();
        transport.register("127.0.0.1:7080", new ReflectiveInvoker(EchoService.class, impl));
        CachingServiceDirectory directory = new CachingServiceDirectory(stubRegistry("127.0.0.1:7080"));
        directory.subscribe(EchoService.class.getName());
        FailoverClusterInvoker cluster = new FailoverClusterInvoker(
                directory, new RandomLoadBalancer(),
                new RemoteInvoker(transport, EchoService.class), 0, 1000);
        DefaultCircuitBreaker breaker = new DefaultCircuitBreaker(new java.util.function.LongSupplier() {
            @Override public long getAsLong() { return clock.get(); }
        });
        CircuitBreakerRule rule = new CircuitBreakerRule(0.5, 0, 0, 3, 10_000, 1000, 1);
        return new RpcProxyFactory<EchoService>(EchoService.class,
                Arrays.<Filter>asList(new TraceFilter(), new MetricsFilter(),
                        new CircuitBreakerFilter(breaker, rule, 1000)),
                cluster).getProxy();
    }

    @Test
    public void circuitOpensWithFastFailAndRecovers() {
        FlakyEchoService impl = new FlakyEchoService();
        EchoService echo = proxy(impl);

        impl.broken = true;
        for (int i = 0; i < 3; i++) {
            try {
                echo.echo("x");
                fail("broken impl should throw");
            } catch (RpcException expected) {
                // 底层失败经 Failover 上浮
            }
        }

        impl.broken = false;                            // 服务恢复
        try {
            echo.echo("still-open");                    // 熔断未到期：应 CIRCUIT_OPEN 快速失败
            fail("circuit should be open");
        } catch (RpcException e) {
            assertTrue("消息应指向熔断: " + e.getMessage(),
                    e.getMessage() != null && e.getMessage().contains("circuit open"));
        }

        clock.set(1000);                                // open-ms 到期 → 半开探测（已恢复 → 成功）
        assertEquals("echo:recovered", echo.echo("recovered"));
    }

    @Test
    public void metricsRecordCircuitOpenRejection() {
        FlakyEchoService impl = new FlakyEchoService();
        EchoService echo = proxy(impl);
        String key = EchoService.class.getName() + "#echo";
        impl.broken = true;
        for (int i = 0; i < 4; i++) {
            try {
                echo.echo("x");
            } catch (RpcException expected) {
                // 3 次底层失败 + 1 次 CIRCUIT_OPEN 拒绝
            }
        }
        List<RpcMetrics.Stat> stats = RpcMetrics.getInstance().snapshot();
        assertEquals(Long.valueOf(1L), findStat(stats, key).rejections().get("circuit-open"));
    }

    private static RpcMetrics.Stat findStat(List<RpcMetrics.Stat> stats, String key) {
        for (RpcMetrics.Stat s : stats) {
            if (s.key().equals(key)) {
                return s;
            }
        }
        throw new AssertionError("missing stat " + key);
    }

    private static Registry stubRegistry(final String... addrs) {
        final List<ServiceInstance> instances = new ArrayList<ServiceInstance>();
        for (String addr : addrs) {
            instances.add(new ServiceInstance(addr));
        }
        return new Registry() {
            @Override public void init(Map<String, String> param) { }
            @Override public void destroy() { }
            @Override public void register(String service, ServiceInstance instance) { }
            @Override public void unregister(String service, ServiceInstance instance) { }
            @Override public void subscribe(String service, ServiceListener listener) {
                listener.onChange(new ArrayList<ServiceInstance>(instances));
            }
            @Override public void unsubscribe(String service, ServiceListener listener) { }
        };
    }
}
