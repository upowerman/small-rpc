package com.yunus.net.base;

import com.yunus.provider.RpcProviderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author gaoyunfeng
 */
public abstract class BaseServer {

    protected static final Logger logger = LoggerFactory.getLogger(BaseServer.class);


    private BaseCallback startedCallback;
    private BaseCallback stopedCallback;

    /**
     * 设置服务开始以后回调类
     *
     * @param startedCallback
     */
    public void setStartedCallback(BaseCallback startedCallback) {
        this.startedCallback = startedCallback;
    }

    /**
     * 设置服务结束时的回调类
     *
     * @param stopedCallback
     */
    public void setStopedCallback(BaseCallback stopedCallback) {
        this.stopedCallback = stopedCallback;
    }


    /**
     * 开启服务
     *
     * @param rpcProviderFactory
     * @throws Exception
     */
    public abstract void start(final RpcProviderFactory rpcProviderFactory) throws Exception;

    /**
     * 开启以后回调
     */
    public void onStarted() {
        if (startedCallback != null) {
            try {
                startedCallback.run();
            } catch (Exception e) {
                logger.error(">>>>>>>>>>> rpc-server startedCallback error.", e);
            }
        }
    }

    /**
     * 停止服务
     *
     * @throws Exception
     */
    public abstract void stop() throws Exception;

    /**
     * 停止时回调
     */
    public void onStoped() {
        if (stopedCallback != null) {
            try {
                stopedCallback.run();
            } catch (Exception e) {
                logger.error(">>>>>>>>>>> rpc-server stopedCallback error.", e);
            }
        }
    }
}
