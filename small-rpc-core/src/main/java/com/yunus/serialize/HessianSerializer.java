package com.yunus.serialize;

import com.caucho.hessian.io.HessianInput;
import com.caucho.hessian.io.HessianOutput;
import com.yunus.exception.RpcException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * jdk自带序列化工具类
 *
 * @author gaoyunfeng
 */
public class HessianSerializer extends BaseSerializer {

    @Override
    public <T> byte[] serialize(T obj) {
        ByteArrayOutputStream os = new ByteArrayOutputStream();
        HessianOutput ho = new HessianOutput(os);
        try {
            ho.writeObject(obj);
            ho.flush();
            byte[] result = os.toByteArray();
            return result;
        } catch (IOException e) {
            throw new RpcException(e);
        } finally {
            try {
                ho.close();
            } catch (IOException e) {
                throw new RpcException(e);
            }
            try {
                os.close();
            } catch (IOException e) {
                throw new RpcException(e);
            }
        }
    }

    @Override
    public <T> Object deserialize(byte[] bytes, Class<T> clazz) {
        ByteArrayInputStream is = new ByteArrayInputStream(bytes);
        HessianInput hi = new HessianInput(is);
        try {
            return hi.readObject();
        } catch (IOException e) {
            throw new RpcException(e);
        } finally {
            try {
                hi.close();
            } catch (Exception e) {
                throw new RpcException(e);
            }
            try {
                is.close();
            } catch (IOException e) {
                throw new RpcException(e);
            }
        }
    }
}
