package com.yunus.sample.service;

import com.yunus.annotation.RpcService;
import com.yunus.dto.HelloDTO;
import com.yunus.service.HelloService;
import org.springframework.stereotype.Service;

/**
 * @author gaoyunfeng
 */
@Service
@RpcService
public class HelloServiceImpl implements HelloService {
    /**
     * hello
     *
     * @param name
     * @return
     */
    @Override
    public HelloDTO hello(String name) {
        return new HelloDTO(name, "hello world");
    }
}
