package io.github.upowerman.core.proxy;

import io.github.upowerman.core.cluster.FailoverClusterInvoker;
import io.github.upowerman.core.directory.PullServiceDirectory;
import io.github.upowerman.core.filter.Filter;
import io.github.upowerman.core.filter.TraceFilter;
import io.github.upowerman.core.invoker.RemoteInvoker;
import io.github.upowerman.core.loadbalance.RandomLoadBalancer;
import io.github.upowerman.core.provider.ReflectiveInvoker;
import io.github.upowerman.core.transport.InMemoryTransport;
import io.github.upowerman.exception.RpcException;
import io.github.upowerman.registry.impl.LocalServiceRegistry;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
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

    /** Business → Proxy → TraceFilter → ClusterInvoker → Directory → LB → RemoteInvoker → InMemoryTransport → ReflectiveInvoker */
    @Test
    public void fullChainThroughInMemoryTransport() {
        // provider 端
        InMemoryTransport transport = new InMemoryTransport();
        transport.register("127.0.0.1:7080",
                new ReflectiveInvoker(EchoService.class, new EchoServiceImpl()));

        // consumer 端：LocalServiceRegistry 直连地址 → PullServiceDirectory
        LocalServiceRegistry registry = new LocalServiceRegistry();
        Map<String, String> param = new HashMap<String, String>();
        param.put(LocalServiceRegistry.DIRECT_ADDRESS, "127.0.0.1:7080");
        registry.start(param);
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
                new PullServiceDirectory(new LocalServiceRegistry(), null),
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
        LocalServiceRegistry registry = new LocalServiceRegistry();
        registry.start(new HashMap<String, String>());
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
            // ok
        }
        registry.stop();
    }
}