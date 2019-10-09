package com.yunus.net.netty;

import com.yunus.net.base.BaseClient;
import com.yunus.net.base.RpcRequest;

/**
 * @author gaoyunfeng
 */
public class NettyClient extends BaseClient {
    

    private Class<? extends ConnectClient> connectClientImpl = NettyConnectClient.class;

    @Override
    public void asyncSend(String address, RpcRequest request) throws Exception {
        ConnectClient.asyncSend(request, address, connectClientImpl, RpcReferenceBean);
    }
}
