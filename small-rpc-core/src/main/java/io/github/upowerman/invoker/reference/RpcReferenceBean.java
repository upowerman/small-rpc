package io.github.upowerman.invoker.reference;

import io.github.upowerman.exception.RpcException;
import io.github.upowerman.invoker.RpcInvokerFactory;
import io.github.upowerman.invoker.route.LoadBalance;
import io.github.upowerman.net.base.BaseClient;
import io.github.upowerman.net.base.NetEnum;
import io.github.upowerman.serialize.BaseSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;

/**
 * @author gaoyunfeng
 */
public class RpcReferenceBean {

    private static final Logger logger = LoggerFactory.getLogger(RpcReferenceBean.class);

    private final NetEnum netType;
    private final BaseSerializer serializer;
    private final LoadBalance loadBalance;

    private final Class<?> iface;
    private final String version;

    private long timeout = 1000;

    private final String address;

    private RpcInvokerFactory invokerFactory;

    BaseClient client = null;

    public RpcReferenceBean(NetEnum netType,
                            BaseSerializer serializer,
                            LoadBalance loadBalance,
                            Class<?> iface,
                            String version,
                            long timeout,
                            String address,
                            RpcInvokerFactory invokerFactory) {

        this.netType = netType;
        this.serializer = serializer;
        this.loadBalance = loadBalance;
        this.iface = iface;
        this.version = version;
        this.timeout = timeout;
        this.address = address;
        this.invokerFactory = invokerFactory;

        // valid
        if (this.netType == null) {
            throw new RpcException("rpc reference netType missing.");
        }
        if (this.serializer == null) {
            throw new RpcException("rpc reference serializer missing.");
        }
        if (this.loadBalance == null) {
            throw new RpcException("rpc reference loadBalance missing.");
        }
        if (this.iface == null) {
            throw new RpcException("rpc reference iface missing.");
        }
        if (this.timeout < 0) {
            this.timeout = 0;
        }
        if (this.invokerFactory == null) {
            this.invokerFactory = RpcInvokerFactory.getInstance();
        }
        initClient();
    }

    private void initClient() {
        try {
            client = netType.clientClass.newInstance();
            client.init(this);
        } catch (InstantiationException | IllegalAccessException e) {
            throw new RpcException(e);
        }
    }


    /**
     * 获取代理对象
     *
     * @return
     */
    public Object getObject() {
        return Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class[]{iface},
                new RpcReferenceInvocationHandler(this));
    }


    public Class<?> getObjectType() {
        return iface;
    }

    public BaseSerializer getSerializer() {
        return serializer;
    }

    public long getTimeout() {
        return timeout;
    }

    public RpcInvokerFactory getInvokerFactory() {
        return invokerFactory;
    }

    public LoadBalance getLoadBalance() {
        return loadBalance;
    }

    public String getVersion() {
        return version;
    }

    public String getAddress() {
        return address;
    }

    public BaseClient getClient() {
        return client;
    }
}
