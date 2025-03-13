package io.github.upowerman.invoker.route.impl;

import io.github.upowerman.invoker.route.AbstractRpcLoadBalance;

import java.util.Random;
import java.util.TreeSet;

/**
 * @author gaoyunfeng
 */
public class RpcLoadBalanceRandomStrategy extends AbstractRpcLoadBalance {

    private final Random random = new Random();

    @Override
    public String route(String serviceKey, TreeSet<String> addressSet) {
        String[] addressArr = addressSet.toArray(new String[0]);
        return addressArr[random.nextInt(addressSet.size())];
    }

}
