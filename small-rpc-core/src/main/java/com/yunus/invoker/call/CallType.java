package com.yunus.invoker.call;

/**
 * 调用方式枚举
 *
 * @author gaoyunfeng
 */

public enum CallType {
    /**
     * 同步调用
     */
    SYNC,


    FUTURE,


    CALLBACK;


    public static CallType match(String name, CallType defaultCallType) {
        for (CallType item : CallType.values()) {
            if (item.name().equals(name)) {
                return item;
            }
        }
        return defaultCallType;
    }
}
