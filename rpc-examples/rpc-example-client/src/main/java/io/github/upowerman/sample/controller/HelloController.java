package io.github.upowerman.sample.controller;

import io.github.upowerman.annotation.RpcReference;
import io.github.upowerman.dto.HelloDTO;
import io.github.upowerman.service.HelloService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @author gaoyunfeng
 */
@RestController
@RequestMapping("/")
public class HelloController {

    @RpcReference
    private HelloService helloService;

    /**
     * 3.0 治理演示：带降级兜底的服务引用。
     * 熔断打开 / 限流 / 在途上限 / 下游失败时返回 fallback 文案而非异常。
     */
    public static class HelloFallback implements HelloService {
        @Override
        public HelloDTO hello(String name) {
            return new HelloDTO(name, "degraded: provider unavailable");
        }
    }

    @RpcReference(fallback = HelloFallback.class)
    private HelloService protectedHelloService;

    @GetMapping("/hello")
    public HelloDTO hello(String name) {
        return helloService.hello(name);
    }

    @GetMapping("/rpc2/hello")
    public HelloDTO rpc2Hello(String name) {
        return helloService.hello(name);
    }

    @GetMapping("/rpc2/protected-hello")
    public HelloDTO protectedHello(String name) {
        return protectedHelloService.hello(name);
    }
}
