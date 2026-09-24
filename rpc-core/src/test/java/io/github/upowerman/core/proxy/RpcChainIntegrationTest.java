package io.github.upowerman.core.proxy;

import io.github.upowerman.core.cluster.FailoverClusterInvoker;
import io.github.upowerman.core.directory.PullServiceDirectory;
import io.github.upowerman.core.filter.Filter;
import io.github.upowerman.core.filter.TraceFilter;
import io.github.upowerman.core.invoker.RemoteInvoker;
import io.github.upowerman.core.loadbalance.RandomLoadBalancer;
import io.github.upowerman.core.provider.ReflectiveInvoker;
import io.github.upowerman.core.registry.BaseServiceRegistry;
import io.github.upowerman.core.transport.InMemoryTransport;
import io.github.upowerman.exception.RpcException;
import org.junit.Test;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class RpcChainIntegrationTest {

    public interface EchoService {
        String echo(String msg);
    }

    public static class EchoServiceImpl implements EchoService {
        @Override
        public String echo(String msg) {
            return "echo:" + msg;
        }
    }

    private static BaseServiceRegistry stubRegistry(String... addrs) {
        final TreeSet<String> set = new TreeSet<String>();
        Collections.addAll(set, addrs);
        return new BaseServiceRegistry() {
            @Override
            public void start(Map<String, String> param) {
            }

            @Override
            public void stop() {
                set.clear();
            }

            @Override
            public boolean registry(Set<String> keys, String value) {
                return false;
            }

            @Override
            public boolean remove(Set<String> keys, String value) {
                return false;
            }

            @Override
            public Map<String, TreeSet<String>> discovery(Set<String> keys) {
                return null;
            }

            @Override
            public TreeSet<String> discovery(String key) {
                return set;
            }
        };
    }

    /** Business → Proxy → TraceFilter → ClusterInvoker → Directory → LB → RemoteInvoker → InMemoryTransport → ReflectiveInvoker */
    @Test
    public void fullChainThroughInMemoryTransport() {
        // provider 端
        InMemoryTransport transport = new InMemoryTransport();
        transport.register("127.0.0.1:7080",
                new ReflectiveInvoker(EchoService.class, new EchoServiceImpl()));

        // consumer 端：BaseServiceRegistry 直连地址 → PullServiceDirectory
        BaseServiceRegistry registry = stubRegistry("127.0.0.1:7080");
        PullServiceDirectory directory = new PullServiceDirectory(registry, null);

        FailoverClusterInvoker cluster = new FailoverClusterInvoker(
                directory, new RandomLoadBalancer(),
                new RemoteInvoker(transport, EchoService.class), 2, 1000);

        EchoService echoService = new RpcProxyFactory<EchoService>(EchoService.class,
                Collections.<Filter>singletonList(new TraceFilter()), cluster).getProxy();

        assertEquals("echo:hello", echoService.echo("hello"));
        registry.stop();
    }

    @Test
    public void objectMethodsAreRejected() {
        InMemoryTransport transport = new InMemoryTransport();
        transport.register("127.0.0.1:7080",
                new ReflectiveInvoker(EchoService.class, new EchoServiceImpl()));
        FailoverClusterInvoker cluster = new FailoverClusterInvoker(
                new PullServiceDirectory(stubRegistry(), null),
                new RandomLoadBalancer(),
                new RemoteInvoker(transport, EchoService.class), 0, 1000);
        EchoService echoService = new RpcProxyFactory<EchoService>(EchoService.class,
                Collections.<Filter>emptyList(), cluster).getProxy();

        try {
            echoService.toString();
            fail("expected RpcException");
        } catch (RpcException expected) {
            // ok
        }
    }

    @Test
    public void serviceNotFoundSurfacesAsRpcException() {
        BaseServiceRegistry registry = stubRegistry();
        FailoverClusterInvoker cluster = new FailoverClusterInvoker(
                new PullServiceDirectory(registry, null),
                new RandomLoadBalancer(),
                new RemoteInvoker(new InMemoryTransport(), EchoService.class), 0, 300);
        EchoService echoService = new RpcProxyFactory<EchoService>(EchoService.class,
                Collections.<Filter>emptyList(), cluster).getProxy();

        try {
            echoService.echo("x");
            fail("expected RpcException");
        } catch (RpcException expected) {
            assertEquals(RpcException.class, expected.getClass());
        }
    }
}