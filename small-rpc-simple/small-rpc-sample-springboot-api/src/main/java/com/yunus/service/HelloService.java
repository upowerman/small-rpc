package com.yunus.service;

import com.yunus.dto.HelloDTO;

/**
 * 示例接口
 *
 * @author gaoyunfeng
 */
public interface HelloService {
    /**
     * hello
     *
     * @param name
     * @return
     */
    HelloDTO hello(String name);
}
