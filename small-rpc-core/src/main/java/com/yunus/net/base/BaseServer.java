package com.yunus.net.base;

import com.yunus.provider.RpcProviderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author gaoyunfeng
 */
public abstract class BaseServer {

    protected static final Logger logger = LoggerFactory.getLogger(BaseServer.class);

    /**
     * 启动服务时的回调类
     */
    private BaseCallback startCallback;
    /**
     * 停止服务时的回调类
     */
    private BaseCallback stopCallback;

    /**
     * 设置服务开始以后回调类
     *
     * @param callback
     */
    public void setStartCallback(BaseCallback callback) {
        this.startCallback = callback;
    }

    /**
     * 设置服务结束时的回调类
     *
     * @param callback
     */
    public void setStopCallback(BaseCallback callback) {
        this.stopCallback = callback;
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
    public void onStart() {
        if (startCallback != null) {
            try {
                startCallback.run();
            } catch (Exception e) {
                logger.error("netty 启动时回调函数执行失败", e);
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
    public void onStop() {
        if (stopCallback != null) {
            try {
                stopCallback.run();
            } catch (Exception e) {
                logger.error("netty 停止时回调函数执行失败", e);
            }
        }
    }
}
