package com.yunus.net.base;

import java.io.Serializable;

/**
 * @author gaoyunfeng
 */
public class RpcResponse implements Serializable {
    private static final long serialVersionUID = 42L;

    private String requestId;
    private String errorMsg;
    private Object result;


    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public Object getResult() {
        return result;
    }

    public void setResult(Object result) {
        this.result = result;
    }

    @Override
    public String toString() {
        return "XxlRpcResponse{" +
                "requestId='" + requestId + '\'' +
                ", errorMsg='" + errorMsg + '\'' +
                ", result=" + result +
                '}';
    }

}
