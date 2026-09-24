package io.github.upowerman.core.testsupport;

/** 测试用服务接口：core 既有测试都用类内嵌接口，本夹具供 Task 3/5 共享 */
public interface EchoService {

    EchoDTO echo(EchoDTO dto);
}
