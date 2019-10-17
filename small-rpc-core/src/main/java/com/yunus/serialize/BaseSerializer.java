package com.yunus.serialize;

/**
 * 序列化方式抽象类
 *
 * @author gaoyunfeng
 */
public abstract class BaseSerializer {
    /**
     * 序列化
     *
     * @param obj 序列化类
     * @param <T> 泛型
     * @return
     */
    public abstract <T> byte[] serialize(T obj);

    /**
     * 反序列化
     *
     * @param bytes 二进制数组
     * @param clazz 序列化成的类
     * @param <T>   泛型
     * @return
     */
    public abstract <T> Object deserialize(byte[] bytes, Class<T> clazz);


}
