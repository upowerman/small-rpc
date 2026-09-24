package io.github.upowerman.core.testsupport;

import java.io.Serializable;

/** 测试用 DTO：与既有 1.x 集成测试的 EchoDTO 同构（Hessian 需要无参构造 + getter/setter） */
public class EchoDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String msg;

    public EchoDTO() {
    }

    public EchoDTO(String msg) {
        this.msg = msg;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }
}
