package com.yunus.invoker.route.impl;

import com.yunus.invoker.route.AbstractRpcLoadBalance;

import java.util.Random;
import java.util.TreeSet;

/**
 * @author gaoyunfeng
 */
public class RpcLoadBalanceRandomStrategy extends AbstractRpcLoadBalance {

    private Random random = new Random();

    @Override
    public String route(String serviceKey, TreeSet<String> addressSet) {
        String[] addressArr = addressSet.toArray(new String[0]);
        return addressArr[random.nextInt(addressSet.size())];
    }

}
