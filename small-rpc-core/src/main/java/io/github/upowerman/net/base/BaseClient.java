package io.github.upowerman.net.base;

import io.github.upowerman.invoker.reference.RpcReferenceBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端抽象类
 *
 * @author gaoyunfeng
 */
public abstract class BaseClient {

    protected static final Logger logger = LoggerFactory.getLogger(BaseClient.class);


    protected volatile RpcReferenceBean rpcReferenceBean;

    public void init(RpcReferenceBean rpcReferenceBean) {
        this.rpcReferenceBean = rpcReferenceBean;
    }


    /**
     * 异步发送绑定requestId 和 响应response
     *
     * @param address
     * @param request
     * @return
     * @throws Exception
     */
    public abstract void asyncSend(String address, RpcRequest request) throws Exception;

}

