package io.github.upowerman.serialize;

import com.caucho.hessian.io.HessianInput;
import com.caucho.hessian.io.HessianOutput;
import io.github.upowerman.exception.RpcException;

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
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            HessianOutput ho = new HessianOutput(os);
            ho.writeObject(obj);
            ho.flush();
            return os.toByteArray();
        } catch (IOException e) {
            throw new RpcException(e);
        }
    }

    @Override
    public <T> Object deserialize(byte[] bytes, Class<T> clazz) {
        try (ByteArrayInputStream is = new ByteArrayInputStream(bytes)) {
            HessianInput hi = new HessianInput(is);
            return hi.readObject();
        } catch (IOException e) {
            throw new RpcException(e);
        }
    }
}
