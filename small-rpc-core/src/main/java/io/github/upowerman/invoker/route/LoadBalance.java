package io.github.upowerman.invoker.route;

import io.github.upowerman.invoker.route.impl.RpcLoadBalanceRandomStrategy;
import io.github.upowerman.invoker.route.impl.RpcLoadBalanceRoundStrategy;

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

    LoadBalance(AbstractRpcLoadBalance rpcInvokerRouter) {
        this.rpcInvokerRouter = rpcInvokerRouter;
    }


    public static LoadBalance match(String name, LoadBalance defaultRouter) {
        for (LoadBalance item : LoadBalance.values()) {
            if (item.name().equals(name)) {
                return item;
            }
        }
        return defaultRouter;
    }
}
