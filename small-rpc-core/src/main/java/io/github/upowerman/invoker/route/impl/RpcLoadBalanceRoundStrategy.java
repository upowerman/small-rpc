package io.github.upowerman.invoker.route.impl;

import io.github.upowerman.invoker.route.AbstractRpcLoadBalance;

import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 轮训负载均衡
 *
 * @author gaoyunfeng
 */
public class RpcLoadBalanceRoundStrategy extends AbstractRpcLoadBalance {

    private final ConcurrentMap<String, AtomicInteger> routeCountEachJob = new ConcurrentHashMap<>();
    private volatile long cacheValidTime = 0;

    private int count(String serviceKey) {
        if (System.currentTimeMillis() > cacheValidTime) {
            routeCountEachJob.clear();
            cacheValidTime = System.currentTimeMillis() + 24 * 60 * 60 * 1000;
        }
        AtomicInteger counter = routeCountEachJob.computeIfAbsent(serviceKey,
                k -> new AtomicInteger(ThreadLocalRandom.current().nextInt(100)));
        int count = counter.incrementAndGet();
        // 防止溢出，重置计数器
        if (count > 1000000) {
            counter.set(ThreadLocalRandom.current().nextInt(100));
            count = counter.incrementAndGet();
        }
        return count;
    }

    @Override
    public String route(String serviceKey, TreeSet<String> addressSet) {
        String[] addressArr = addressSet.toArray(new String[0]);
        return addressArr[count(serviceKey) % addressArr.length];
    }

}
