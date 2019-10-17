package com.yunus.invoker.route.impl;

import com.yunus.invoker.route.AbstractRpcLoadBalance;

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

    private ConcurrentMap<String, Integer> routeCountEachJob = new ConcurrentHashMap<String, Integer>();
    private long CACHE_VALID_TIME = 0;

    private int count(String serviceKey) {
        if (System.currentTimeMillis() > CACHE_VALID_TIME) {
            routeCountEachJob.clear();
            CACHE_VALID_TIME = System.currentTimeMillis() + 24 * 60 * 60 * 1000;
        }
        Integer count = routeCountEachJob.get(serviceKey);
        // 初始化时主动Random一次，缓解首次压力
        count = (count == null || count > 1000000) ? (new Random().nextInt(100)) : ++count;
        routeCountEachJob.put(serviceKey, count);
        return count;
    }

    @Override
    public String route(String serviceKey, TreeSet<String> addressSet) {
        String[] addressArr = addressSet.toArray(new String[addressSet.size()]);

        String finalAddress = addressArr[count(serviceKey) % addressArr.length];
        return finalAddress;
    }
}
