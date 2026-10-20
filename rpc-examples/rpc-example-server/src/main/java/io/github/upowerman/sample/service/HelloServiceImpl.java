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
     * <p>
     * 3.0 治理演示的故障注入约定：
     * {@code name=boom} 抛业务异常（触发消费端熔断统计）；{@code name=slow...} 阻塞 4s
     * （超过默认 3000ms 调用超时 → TIMEOUT，同时计入慢调用）。
     *
     * @param name name
     * @return HelloDTO
     */
    @Override
    public HelloDTO hello(String name) {
        logger.info("[server:{}] hello called: name={}", rpc2Port, name);
        if ("boom".equals(name)) {
            throw new IllegalStateException("injected failure: " + name);
        }
        if (name != null && name.startsWith("slow")) {
            try {
                Thread.sleep(4000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return new HelloDTO(name, "hello world");
    }
}
