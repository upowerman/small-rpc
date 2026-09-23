package io.github.upowerman.core.adapter;

import io.github.upowerman.core.RpcConstants;
import io.github.upowerman.core.invocation.GenericInvocation;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import io.github.upowerman.core.transport.PendingRequests;
import io.github.upowerman.invoker.RpcInvokerFactory;
import io.github.upowerman.net.base.ConnectClient;
import io.github.upowerman.net.base.RpcRequest;
import io.github.upowerman.serialize.BaseSerializer;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * C1 回归：传输层拥有 in-flight 条目生命周期——请求完成、失败或超时后，
 * PendingRequests 表项与 1.x RpcInvokerFactory 的 future 池都必须被移除，不再泄漏。
 */
public class LegacyConnectionTest {

    /** 可控桩：send 可配置为抛异常；从不回响应 */
    static class StubConnectClient extends ConnectClient {
        volatile boolean throwOnSend;

        @Override
        public void init(String address, BaseSerializer serializer, RpcInvokerFactory rpcInvokerFactory) {
        }

        @Override
        public void close() {
        }

        @Override
        public boolean isValidate() {
            return true;
        }

        @Override
        public void send(RpcRequest request) throws Exception {
            if (throwOnSend) {
                throw new RuntimeException("boom");
            }
        }
    }

    private Invocation invocation(Long timeoutMillis) {
        Map<String, Object> attachments = new HashMap<String, Object>();
        if (timeoutMillis != null) {
            attachments.put(RpcConstants.ATTACH_TIMEOUT, timeoutMillis);
        }
        return new GenericInvocation("com.test.EchoService", "echo",
                new Class<?>[0], new Object[0], attachments);
    }

    private LegacyConnection connection(ConnectClient client, RpcInvokerFactory factory,
                                        PendingRequests pending, long defaultTimeoutMillis) {
        return new LegacyConnection(client, factory, pending, defaultTimeoutMillis, "127.0.0.1:1", null);
    }

    /** 反射读取 1.x future 池大小，证明 BridgingFuture 已从 1.x 池中移除 */
    private static int invokerPoolSize(RpcInvokerFactory factory) throws Exception {
        Field field = RpcInvokerFactory.class.getDeclaredField("futureResponsePool");
        field.setAccessible(true);
        ConcurrentMap<?, ?> pool = (ConcurrentMap<?, ?>) field.get(factory);
        return pool.size();
    }

    @Test
    public void pendingEntryIsEvictedAfterTimeout() throws Exception {
        PendingRequests pending = new PendingRequests();
        RpcInvokerFactory factory = new RpcInvokerFactory();
        LegacyConnection conn = connection(new StubConnectClient(), factory, pending, 5000L);

        CompletableFuture<Result> future = conn.request(invocation(60L));
        Result result = future.get(2, TimeUnit.SECONDS);

        assertSame(Status.TIMEOUT, result.status());
        assertEquals(0, pending.size());
        assertEquals(0, invokerPoolSize(factory));
    }

    @Test
    public void pendingEntryIsEvictedAfterSendFailure() throws Exception {
        PendingRequests pending = new PendingRequests();
        RpcInvokerFactory factory = new RpcInvokerFactory();
        StubConnectClient client = new StubConnectClient();
        client.throwOnSend = true;
        LegacyConnection conn = connection(client, factory, pending, 5000L);

        CompletableFuture<Result> future = conn.request(invocation(null));
        Result result = future.get(2, TimeUnit.SECONDS);

        assertSame(Status.NETWORK_ERROR, result.status());
        assertEquals(0, pending.size());
        assertEquals(0, invokerPoolSize(factory));
    }

    @Test
    public void defaultTimeoutBoundsSilentRequestAndEvicts() throws Exception {
        PendingRequests pending = new PendingRequests();
        RpcInvokerFactory factory = new RpcInvokerFactory();
        // 未附超时且静默上游 → 走连接默认超时（60ms），到期同样必须结算并清空两张表
        LegacyConnection conn = connection(new StubConnectClient(), factory, pending, 60L);

        CompletableFuture<Result> future = conn.request(invocation(null));
        Result result = future.get(2, TimeUnit.SECONDS);

        assertSame(Status.TIMEOUT, result.status());
        assertEquals(0, pending.size());
        assertEquals(0, invokerPoolSize(factory));
    }
}
