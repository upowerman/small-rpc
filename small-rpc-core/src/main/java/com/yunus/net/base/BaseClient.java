package com.yunus.net.base;

import com.yunus.invoker.reference.RpcReferenceBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端抽象类
 *
 * @author gaoyunfeng
 */
public abstract class BaseClient {

    protected static final Logger logger = LoggerFactory.getLogger(BaseClient.class);


    protected volatile RpcReferenceBean RpcReferenceBean;

    public void init(com.yunus.invoker.reference.RpcReferenceBean RpcReferenceBean) {
        this.RpcReferenceBean = RpcReferenceBean;
    }


    /**
     * 异步发送绑定requestId 和 响应response
     *
     * @param address
     * @param RpcRequest
     * @return
     * @throws Exception
     */
    public abstract void asyncSend(String address, RpcRequest RpcRequest) throws Exception;

}

