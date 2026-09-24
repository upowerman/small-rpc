package io.github.upowerman.sample.service;

import io.github.upowerman.annotation.RpcService;
import io.github.upowerman.dto.HelloDTO;
import io.github.upowerman.service.HelloService;
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
     * @param name name
     * @return HelloDTO
     */
    @Override
    public HelloDTO hello(String name) {

        return new HelloDTO(name, "hello world");
    }
}
