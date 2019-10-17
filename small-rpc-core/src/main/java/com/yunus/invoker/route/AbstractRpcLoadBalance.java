package com.yunus.invoker.route;

import java.util.TreeSet;

/**
 * 负载均衡抽象类
 *
 * @author gaoyunfeng
 */
public abstract class AbstractRpcLoadBalance {
    /**
     * route 抽象方法
     *
     * @param serviceKey 服务名
     * @param addressSet 注册表
     * @return
     */
    public abstract String route(String serviceKey, TreeSet<String> addressSet);
}
