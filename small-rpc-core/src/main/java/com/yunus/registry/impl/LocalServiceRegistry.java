package com.yunus.registry.impl;

import com.yunus.registry.BaseServiceRegistry;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * @author gaoyunfeng
 */
public class LocalServiceRegistry extends BaseServiceRegistry {

    /**
     * 注册表 interface#version  treeSet 注册信息ß
     */
    private Map<String, TreeSet<String>> registryData;


    /**
     * @param param 忽略传入参数
     */
    @Override
    public void start(Map<String, String> param) {
        registryData = new HashMap<String, TreeSet<String>>();
    }

    @Override
    public void stop() {
        registryData.clear();
    }


    @Override
    public boolean registry(Set<String> keys, String value) {
        if (keys == null || keys.size() == 0 || value == null || value.trim().length() == 0) {
            return false;
        }
        for (String key : keys) {
            TreeSet<String> values = registryData.get(key);
            if (values == null) {
                values = new TreeSet<String>();
                registryData.put(key, values);
            }
            values.add(value);
        }
        return true;
    }

    @Override
    public boolean remove(Set<String> keys, String value) {
        if (keys == null || keys.size() == 0 || value == null || value.trim().length() == 0) {
            return false;
        }
        for (String key : keys) {
            TreeSet<String> values = registryData.get(key);
            if (values != null) {
                values.remove(value);
            }
        }
        return true;
    }

    @Override
    public Map<String, TreeSet<String>> discovery(Set<String> keys) {
        if (keys == null || keys.size() == 0) {
            return null;
        }
        Map<String, TreeSet<String>> registryDataTmp = new HashMap<String, TreeSet<String>>();
        for (String key : keys) {
            TreeSet<String> valueSetTmp = discovery(key);
            if (valueSetTmp != null) {
                registryDataTmp.put(key, valueSetTmp);
            }
        }
        return registryDataTmp;
    }

    @Override
    public TreeSet<String> discovery(String key) {
        return registryData.get(key);
    }

}
