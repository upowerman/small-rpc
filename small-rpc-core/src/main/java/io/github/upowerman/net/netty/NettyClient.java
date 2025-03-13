package io.github.upowerman.net.netty;

import io.github.upowerman.net.base.BaseClient;
import io.github.upowerman.net.base.ConnectClient;
import io.github.upowerman.net.base.RpcRequest;

/**
 * @author gaoyunfeng
 */
public class NettyClient extends BaseClient {


    private Class<? extends ConnectClient> connectClientImpl = NettyConnectClient.class;

    @Override
    public void asyncSend(String address, RpcRequest request) throws Exception {
        ConnectClient.asyncSend(request, address, connectClientImpl, rpcReferenceBean);
    }
}
