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

    @GetMapping("/hello")
    public HelloDTO hello(String name) {
        return helloService.hello(name);
    }
}
