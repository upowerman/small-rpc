package com.yunus.net.netty;

import com.yunus.invoker.RpcInvokerFactory;
import com.yunus.net.base.ConnectClient;
import com.yunus.net.base.RpcRequest;
import com.yunus.serialize.BaseSerializer;

public class NettyConnectClient extends ConnectClient {
    @Override
    public void init(String address, BaseSerializer serializer, RpcInvokerFactory RpcInvokerFactory) throws Exception {

    }

    @Override
    public void close() {

    }

    @Override
    public boolean isValidate() {
        return false;
    }

    @Override
    public void send(RpcRequest request) throws Exception {

    }
}
