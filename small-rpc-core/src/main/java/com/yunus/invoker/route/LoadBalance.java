package com.yunus.invoker.route;

import com.yunus.invoker.route.impl.RpcLoadBalanceRandomStrategy;
import com.yunus.invoker.route.impl.RpcLoadBalanceRoundStrategy;

/**
 * 负载均衡策略
 *
 * @author gaoyunfeng
 */
public enum LoadBalance {
    /**
     * 随机
     */
    RANDOM(new RpcLoadBalanceRandomStrategy()),
    /**
     * 轮训
     */
    ROUND(new RpcLoadBalanceRoundStrategy());


    public final AbstractRpcLoadBalance rpcInvokerRouter;

    private LoadBalance(AbstractRpcLoadBalance rpcInvokerRouter) {
        this.rpcInvokerRouter = rpcInvokerRouter;
    }


    public static LoadBalance match(String name, LoadBalance defaultRouter) {
        for (LoadBalance item : LoadBalance.values()) {
            if (item.equals(name)) {
                return item;
            }
        }
        return defaultRouter;
    }
}
