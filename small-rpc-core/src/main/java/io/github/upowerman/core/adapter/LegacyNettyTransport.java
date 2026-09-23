package io.github.upowerman.core.adapter;

import io.github.upowerman.core.transport.Connection;
import io.github.upowerman.core.transport.Endpoint;
import io.github.upowerman.core.transport.PendingRequests;
import io.github.upowerman.core.transport.Transport;
import io.github.upowerman.invoker.RpcInvokerFactory;
import io.github.upowerman.invoker.route.LoadBalance;
import io.github.upowerman.net.base.BaseClient;
import io.github.upowerman.net.base.NetEnum;
import io.github.upowerman.serialize.BaseSerializer;
import io.github.upowerman.serialize.HessianSerializer;

/**
 * Transport 适配器：复用 1.x Netty 客户端与连接池。
 * 借 RpcReferenceBean 构造副作用创建可用的 NettyClient（其构造会 new + init client），
 * 一个实例可对多个 address 建连（1.x ConnectClient 按 address 池化）。
 */
public class LegacyNettyTransport implements Transport {

    private final BaseClient client;
    private final RpcInvokerFactory invokerFactory;
    private final PendingRequests pending;
    private final String version;

    public LegacyNettyTransport(BaseSerializer serializer, RpcInvokerFactory invokerFactory,
                                String version) {
        this.invokerFactory = invokerFactory;
        this.pending = new PendingRequests();
        this.version = version;
        io.github.upowerman.invoker.reference.RpcReferenceBean template =
                new io.github.upowerman.invoker.reference.RpcReferenceBean(
                        NetEnum.NETTY, serializer != null ? serializer : new HessianSerializer(),
                        LoadBalance.RANDOM, Object.class, version, 0L, null, invokerFactory);
        this.client = template.getClient();
    }

    @Override
    public Connection connect(Endpoint endpoint) {
        return new LegacyConnection(client, invokerFactory, pending,
                endpoint.address(), version);
    }
}