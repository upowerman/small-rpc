package io.github.upowerman.core.adapter;

import io.github.upowerman.core.transport.Connection;
import io.github.upowerman.core.transport.Endpoint;
import io.github.upowerman.core.transport.PendingRequests;
import io.github.upowerman.core.transport.Transport;
import io.github.upowerman.exception.RpcException;
import io.github.upowerman.invoker.RpcInvokerFactory;
import io.github.upowerman.net.base.ConnectClient;
import io.github.upowerman.net.netty.NettyConnectClient;
import io.github.upowerman.serialize.BaseSerializer;
import io.github.upowerman.serialize.HessianSerializer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Transport 适配器：复用 1.x Netty 客户端实现，但<b>不使用</b> 1.x 的进程级静态连接池
 * （ConnectClient.connectClientMap 仅按 address 键控，会把响应路由到创建该连接的那个
 * RpcInvokerFactory，导致 1.x 与 2.0 共址时串话、响应被静默丢弃）。
 * <p>
 * 每个本类实例维护自己的 {@code Map<String, ConnectClient>} 连接池（按 address 键控），
 * 通过公开 API {@link NettyConnectClient#init(String, BaseSerializer, RpcInvokerFactory)}
 * 建连、{@link NettyConnectClient#send} 发送，保证响应经由本实例自己的
 * {@link PendingRequests} + {@link RpcInvokerFactory} 路由回来，且序列化器就是构造时传入的那个。
 * 连接池随 {@link #close()} 一并释放。
 *
 * @deprecated P1 起由 2.0 自研协议栈替代（{@link io.github.upowerman.core.transport.NettyTransport} /
 * {@link io.github.upowerman.core.server.RpcServer}）。P2 拆多模块时移除本类及 1.x 桥接。
 */
@Deprecated
public class LegacyNettyTransport implements Transport {

    /** 兜底默认单请求超时（毫秒）。当调用未显式携带 ATTACH_TIMEOUT 时使用。 */
    public static final long DEFAULT_REQUEST_TIMEOUT_MILLIS = 30000L;

    private final BaseSerializer serializer;
    private final RpcInvokerFactory invokerFactory;
    private final PendingRequests pending;
    private final String version;
    private final long defaultTimeoutMillis;

    private final ConcurrentMap<String, ConnectClient> clients = new ConcurrentHashMap<String, ConnectClient>();
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<String, Object>();
    private volatile boolean closed;

    public LegacyNettyTransport(BaseSerializer serializer, RpcInvokerFactory invokerFactory,
                                String version) {
        this(serializer, invokerFactory, version, DEFAULT_REQUEST_TIMEOUT_MILLIS);
    }

    public LegacyNettyTransport(BaseSerializer serializer, RpcInvokerFactory invokerFactory,
                                String version, long defaultRequestTimeoutMillis) {
        // 每个 transport 拥有自己的 factory：响应经其 futureResponsePool 按 requestId 路由回来。
        // 与 1.x 进程级静态池/单例 factory 隔离，避免两个 2.0 实例或 1.x+2.0 共址时串话。
        this.invokerFactory = invokerFactory != null ? invokerFactory : new RpcInvokerFactory();
        this.pending = new PendingRequests();
        this.version = version;
        this.serializer = serializer != null ? serializer : new HessianSerializer();
        // 兜底超时绝不允许非正：非正只会让挂起请求永不超时（PendingRequests 以正值调度）
        this.defaultTimeoutMillis = defaultRequestTimeoutMillis > 0
                ? defaultRequestTimeoutMillis
                : DEFAULT_REQUEST_TIMEOUT_MILLIS;
    }

    @Override
    public Connection connect(Endpoint endpoint) {
        return new LegacyConnection(clientFor(endpoint.address()), invokerFactory, pending,
                defaultTimeoutMillis, endpoint.address(), version);
    }

    /**
     * 关闭本实例持有的全部连接并释放事件循环线程。幂等。
     */
    public void close() {
        closed = true;
        for (ConnectClient client : clients.values()) {
            try {
                client.close();
            } catch (Exception ignored) {
                // 关闭失败不阻断其余连接释放
            }
        }
        clients.clear();
    }

    /**
     * 取得指定地址的可用连接：复用存活连接，否则新建（每个地址一把锁，双检）。
     * 仅由本类与包内 LegacyConnection 使用。
     */
    ConnectClient clientFor(String address) {
        ConnectClient client = clients.get(address);
        if (client != null && client.isValidate()) {
            return client;
        }
        Object lock = locks.get(address);
        if (lock == null) {
            locks.putIfAbsent(address, new Object());
            lock = locks.get(address);
        }
        synchronized (lock) {
            client = clients.get(address);
            if (client != null && client.isValidate()) {
                return client;
            }
            if (client != null) {
                client.close();
            }
            if (closed) {
                throw new RpcException("transport already closed");
            }
            ConnectClient newClient = new NettyConnectClient();
            // 失败时 NettyConnectClient.init 内部会自行 close 释放
            try {
                newClient.init(address, serializer, invokerFactory);
            } catch (Exception e) {
                throw new RpcException("failed to connect to " + address, e);
            }
            clients.put(address, newClient);
            return newClient;
        }
    }
}
