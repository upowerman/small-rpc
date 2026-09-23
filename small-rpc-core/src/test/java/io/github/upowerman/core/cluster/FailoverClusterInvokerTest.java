package io.github.upowerman.core.cluster;

import io.github.upowerman.core.RpcConstants;
import io.github.upowerman.core.directory.ServiceDirectory;
import io.github.upowerman.core.directory.ServiceInstance;
import io.github.upowerman.core.invocation.GenericInvocation;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.loadbalance.LoadBalancer;
import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class FailoverClusterInvokerTest {

    static ServiceDirectory dir(final ServiceInstance... instances) {
        return new ServiceDirectory() {
            @Override
            public List<ServiceInstance> list(String service) {
                return Arrays.asList(instances);
            }

            @Override
            public void subscribe(String service) {
            }
        };
    }

    /** 依次返回预设结果，并统计调用次数 */
    static Invoker scriptedInvoker(final Queue<Result> script, final AtomicInteger count,
                                   final List<String> receivedAddresses) {
        return new Invoker() {
            @Override
            public Class<?> interfaceClass() {
                return Object.class;
            }

            @Override
            public CompletableFuture<Result> invoke(Invocation invocation) {
                count.incrementAndGet();
                receivedAddresses.add((String) invocation.attachments().get(RpcConstants.ATTACH_ADDRESS));
                return CompletableFuture.completedFuture(script.poll());
            }
        };
    }

    static LoadBalancer firstLb() {
        return new LoadBalancer() {
            @Override
            public ServiceInstance select(List<ServiceInstance> instances, Invocation invocation) {
                return instances.get(0);
            }
        };
    }

    static Invocation invocation() {
        return new GenericInvocation("com.test.EchoService", "echo",
                new Class<?>[0], new Object[0], new HashMap<String, Object>());
    }

    @Test
    public void emptyDirectoryGivesServiceNotFound() throws Exception {
        FailoverClusterInvoker cluster = new FailoverClusterInvoker(
                dir(), firstLb(), scriptedInvoker(new ConcurrentLinkedQueue<Result>(),
                new AtomicInteger(), new ArrayList<String>()), 2, 1000);

        Result result = cluster.invoke(invocation()).get(2, TimeUnit.SECONDS);
        assertSame(Status.SERVICE_NOT_FOUND, result.status());
    }

    @Test
    public void successPassesValueAndWritesAddress() throws Exception {
        Queue<Result> script = new ConcurrentLinkedQueue<Result>();
        script.add(DefaultResult.success("ok"));
        List<String> addresses = new ArrayList<>();
        FailoverClusterInvoker cluster = new FailoverClusterInvoker(
                dir(new ServiceInstance("127.0.0.1:1")), firstLb(),
                scriptedInvoker(script, new AtomicInteger(), addresses), 2, 1000);

        Result result = cluster.invoke(invocation()).get(2, TimeUnit.SECONDS);

        assertEquals("ok", result.value());
        assertEquals(Arrays.asList("127.0.0.1:1"), addresses);
    }

    @Test
    public void retriesNetworkErrorThenSucceeds() throws Exception {
        Queue<Result> script = new ConcurrentLinkedQueue<Result>();
        script.add(DefaultResult.failure(Status.NETWORK_ERROR));
        script.add(DefaultResult.success("ok"));
        AtomicInteger count = new AtomicInteger();
        FailoverClusterInvoker cluster = new FailoverClusterInvoker(
                dir(new ServiceInstance("127.0.0.1:1")), firstLb(),
                scriptedInvoker(script, count, new ArrayList<String>()), 2, 1000);

        Result result = cluster.invoke(invocation()).get(2, TimeUnit.SECONDS);

        assertEquals("ok", result.value());
        assertEquals(2, count.get());
    }

    @Test
    public void stopsRetryingOnNonRetryableStatus() throws Exception {
        Queue<Result> script = new ConcurrentLinkedQueue<Result>();
        script.add(DefaultResult.failure(Status.METHOD_NOT_FOUND));
        AtomicInteger count = new AtomicInteger();
        FailoverClusterInvoker cluster = new FailoverClusterInvoker(
                dir(new ServiceInstance("127.0.0.1:1")), firstLb(),
                scriptedInvoker(script, count, new ArrayList<String>()), 3, 1000);

        Result result = cluster.invoke(invocation()).get(2, TimeUnit.SECONDS);

        assertSame(Status.METHOD_NOT_FOUND, result.status());
        assertEquals(1, count.get());
    }

    @Test
    public void retriesExhaustedReturnsLastFailure() throws Exception {
        Queue<Result> script = new ConcurrentLinkedQueue<Result>();
        script.add(DefaultResult.failure(Status.NETWORK_ERROR));
        script.add(DefaultResult.failure(Status.NETWORK_ERROR));
        script.add(DefaultResult.failure(Status.NETWORK_ERROR));
        AtomicInteger count = new AtomicInteger();
        FailoverClusterInvoker cluster = new FailoverClusterInvoker(
                dir(new ServiceInstance("127.0.0.1:1")), firstLb(),
                scriptedInvoker(script, count, new ArrayList<String>()), 2, 1000);

        Result result = cluster.invoke(invocation()).get(2, TimeUnit.SECONDS);

        assertSame(Status.NETWORK_ERROR, result.status());
        assertEquals(3, count.get());
    }

    @Test
    public void silentUpstreamTimesOut() throws Exception {
        AtomicInteger count = new AtomicInteger();
        Invoker silent = new Invoker() {
            @Override
            public Class<?> interfaceClass() {
                return Object.class;
            }

            @Override
            public CompletableFuture<Result> invoke(Invocation invocation) {
                count.incrementAndGet();
                return new CompletableFuture<Result>(); // 永不完成
            }
        };
        FailoverClusterInvoker cluster = new FailoverClusterInvoker(
                dir(new ServiceInstance("127.0.0.1:1")), firstLb(), silent, 0, 50L);

        Result result = cluster.invoke(invocation()).get(2, TimeUnit.SECONDS);

        assertSame(Status.TIMEOUT, result.status());
        assertEquals(1, count.get()); // retries=0，不重试
    }

    @Test
    public void timeoutFromAttachmentsOverridesDefault() throws Exception {
        // attachments 指定 50ms，默认 10000ms —— 应在 attachments 超时生效
        HashMap<String, Object> attachments = new HashMap<>();
        attachments.put(RpcConstants.ATTACH_TIMEOUT, 50L);
        Invocation inv = new GenericInvocation("com.test.EchoService", "echo",
                new Class<?>[0], new Object[0], attachments);
        Invoker silent = new Invoker() {
            @Override
            public Class<?> interfaceClass() {
                return Object.class;
            }

            @Override
            public CompletableFuture<Result> invoke(Invocation i) {
                return new CompletableFuture<Result>();
            }
        };
        FailoverClusterInvoker cluster = new FailoverClusterInvoker(
                dir(new ServiceInstance("127.0.0.1:1")), firstLb(), silent, 0, 10000L);

        Result result = cluster.invoke(inv).get(2, TimeUnit.SECONDS);

        assertSame(Status.TIMEOUT, result.status());
    }
}
