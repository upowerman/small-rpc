package io.github.upowerman.core.proxy;

import io.github.upowerman.core.cluster.FailoverClusterInvoker;
import io.github.upowerman.core.directory.CachingServiceDirectory;
import io.github.upowerman.core.directory.ServiceInstance;
import io.github.upowerman.core.filter.Filter;
import io.github.upowerman.core.filter.TraceFilter;
import io.github.upowerman.core.invoker.RemoteInvoker;
import io.github.upowerman.core.loadbalance.RandomLoadBalancer;
import io.github.upowerman.core.provider.ReflectiveInvoker;
import io.github.upowerman.core.registry.Registry;
import io.github.upowerman.core.registry.ServiceListener;
import io.github.upowerman.core.transport.InMemoryTransport;
import io.github.upowerman.exception.RpcException;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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

    private static Registry stubRegistry(final String... addrs) {
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
                instances.clear();
            }

            @Override
            public void register(String service, ServiceInstance instance) {
                instances.add(instance);
            }

            @Override
            public void unregister(String service, ServiceInstance instance) {
                instances.remove(instance);
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

    /** Business → Proxy → TraceFilter → ClusterInvoker → Directory → LB → RemoteInvoker → InMemoryTransport → ReflectiveInvoker */
    @Test
    public void fullChainThroughInMemoryTransport() {
        // provider 端
        InMemoryTransport transport = new InMemoryTransport();
        transport.register("127.0.0.1:7080",
                new ReflectiveInvoker(EchoService.class, new EchoServiceImpl()));

        // consumer 端：Registry 直连地址 → CachingServiceDirectory
        Registry registry = stubRegistry("127.0.0.1:7080");
        CachingServiceDirectory directory = new CachingServiceDirectory(registry);
        directory.subscribe(EchoService.class.getName());

        FailoverClusterInvoker cluster = new FailoverClusterInvoker(
                directory, new RandomLoadBalancer(),
                new RemoteInvoker(transport, EchoService.class), 2, 1000);

        EchoService echoService = new RpcProxyFactory<EchoService>(EchoService.class,
                Collections.<Filter>singletonList(new TraceFilter()), cluster).getProxy();

        assertEquals("echo:hello", echoService.echo("hello"));
        registry.destroy();
    }

    @Test
    public void objectMethodsAreRejected() {
        InMemoryTransport transport = new InMemoryTransport();
        transport.register("127.0.0.1:7080",
                new ReflectiveInvoker(EchoService.class, new EchoServiceImpl()));
        CachingServiceDirectory directory = new CachingServiceDirectory(stubRegistry());
        directory.subscribe(EchoService.class.getName());
        FailoverClusterInvoker cluster = new FailoverClusterInvoker(
                directory,
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
        Registry registry = stubRegistry();
        CachingServiceDirectory directory = new CachingServiceDirectory(registry);
        directory.subscribe(EchoService.class.getName());
        FailoverClusterInvoker cluster = new FailoverClusterInvoker(
                directory,
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