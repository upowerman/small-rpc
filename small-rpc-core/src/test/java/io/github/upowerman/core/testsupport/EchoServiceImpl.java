package io.github.upowerman.core.testsupport;

/**
 * 正常回显 echo:&lt;msg&gt;；msg 以 "boom" 开头则抛异常，
 * 供 SERVER_ERROR 路径测试（免去给接口加第二个方法）。
 */
public class EchoServiceImpl implements EchoService {

    @Override
    public EchoDTO echo(EchoDTO dto) {
        if (dto.getMsg() != null && dto.getMsg().startsWith("boom")) {
            throw new IllegalStateException("boom happened");
        }
        return new EchoDTO("echo:" + dto.getMsg());
    }
}
