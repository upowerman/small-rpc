package io.github.upowerman.service;

import io.github.upowerman.dto.HelloDTO;

/**
 * 示例接口
 *
 * @author gaoyunfeng
 */
public interface HelloService {
    /**
     * hello
     *
     * @param name name
     * @return HelloDTO
     */
    HelloDTO hello(String name);
}
