package io.github.upowerman.invoker.route.impl;

import io.github.upowerman.invoker.route.AbstractRpcLoadBalance;

import java.util.Random;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 轮训负载均衡
 *
 * @author gaoyunfeng
 */
public class RpcLoadBalanceRoundStrategy extends AbstractRpcLoadBalance {

    private final ConcurrentMap<String, Integer> routeCountEachJob = new ConcurrentHashMap<String, Integer>();
    private long cacheValidTime = 0;

    private Integer count(String serviceKey) {
        if (System.currentTimeMillis() > cacheValidTime) {
            routeCountEachJob.clear();
            cacheValidTime = System.currentTimeMillis() + 24 * 60 * 60 * 1000;
        }
        Integer count = routeCountEachJob.get(serviceKey);
        // 初始化时主动Random一次，缓解首次压力
        count = (count == null || count > 1000000) ? (new Random().nextInt(100)) : ++count;
        routeCountEachJob.put(serviceKey, count);
        return count;
    }

    @Override
    public String route(String serviceKey, TreeSet<String> addressSet) {
        String[] addressArr = addressSet.toArray(new String[0]);
        return addressArr[count(serviceKey) % addressArr.length];
    }

}
