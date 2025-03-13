package io.github.upowerman.registry.impl;

import io.github.upowerman.registry.BaseServiceRegistry;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * @author gaoyunfeng
 */
public class LocalServiceRegistry extends BaseServiceRegistry {

    /**
     * 指定rpc 地址的key
     */
    public static final String DIRECT_ADDRESS = "DIRECT_ADDRESS";

    /**
     * 直连 地址
     */
    private TreeSet<String> directAddress;


    /**
     * @param param 忽略传入参数
     */
    @Override
    public void start(Map<String, String> param) {
        directAddress = new TreeSet<String>();
        String address = param.get(DIRECT_ADDRESS);
        if(!StringUtils.isEmpty(address)){
            directAddress.add(address);
        }
    }

    @Override
    public void stop() {
        directAddress.clear();
    }


    @Override
    public boolean registry(Set<String> keys, String value) {
        // 直连没有注册功能 直接忽略
        return false;
    }

    @Override
    public boolean remove(Set<String> keys, String value) {
        // 直连没有移除功能 忽略
        return false;
    }

    @Override
    public Map<String, TreeSet<String>> discovery(Set<String> keys) {
        if (keys == null || keys.size() == 0) {
            return null;
        }
        Map<String, TreeSet<String>> registryDataTmp = new HashMap<String, TreeSet<String>>();
        for (String key : keys) {
            registryDataTmp.put(key, directAddress);
        }
        return registryDataTmp;
    }

    @Override
    public TreeSet<String> discovery(String key) {
        return directAddress;
    }

}
