package io.github.upowerman.core.testsupport;

import java.util.concurrent.CompletableFuture;

/** 测试用服务接口：core 既有测试都用类内嵌接口，本夹具供 Task 3/5 共享 */
public interface EchoService {

    EchoDTO echo(EchoDTO dto);

    /** A2 异步签名：走真实序列化往返，验证 CompletableFuture 值可传输 */
    CompletableFuture<EchoDTO> echoAsync(EchoDTO dto);
}
