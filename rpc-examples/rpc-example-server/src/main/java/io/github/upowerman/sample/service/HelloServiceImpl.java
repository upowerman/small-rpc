package io.github.upowerman.sample.service;

import io.github.upowerman.annotation.RpcService;
import io.github.upowerman.dto.HelloDTO;
import io.github.upowerman.service.HelloService;
import org.springframework.stereotype.Service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;

/**
 * @author gaoyunfeng
 */
@Service
@RpcService
public class HelloServiceImpl implements HelloService {

    private static final Logger logger = LoggerFactory.getLogger(HelloServiceImpl.class);

    @Value("${small-rpc.provider.rpc2-port:7081}")
    private int rpc2Port;

    /**
     * hello
     *
     * @param name name
     * @return HelloDTO
     */
    @Override
    public HelloDTO hello(String name) {
        logger.info("[server:{}] hello called: name={}", rpc2Port, name);
        return new HelloDTO(name, "hello world");
    }
}
