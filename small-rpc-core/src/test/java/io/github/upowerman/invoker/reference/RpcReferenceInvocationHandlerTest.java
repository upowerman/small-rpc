package io.github.upowerman.invoker.reference;

import io.github.upowerman.invoker.RpcInvokerFactory;
import io.github.upowerman.invoker.route.LoadBalance;
import io.github.upowerman.net.base.BaseClient;
import io.github.upowerman.net.base.NetEnum;
import io.github.upowerman.net.base.RpcRequest;
import io.github.upowerman.net.base.RpcResponse;
import io.github.upowerman.serialize.SerializeEnum;
import org.junit.Assert;
import org.junit.Test;

import java.lang.reflect.Field;

public class RpcReferenceInvocationHandlerTest {

    interface VersionedService {
        String hello(String name);
    }

    @Test
    public void shouldPassVersionIntoRpcRequest() throws Exception {
        RpcInvokerFactory invokerFactory = new RpcInvokerFactory();
        RpcReferenceBean referenceBean = new RpcReferenceBean(
                NetEnum.NETTY,
                SerializeEnum.HESSIAN.getSerializer(),
                LoadBalance.ROUND,
                VersionedService.class,
                "v1",
                1000,
                "127.0.0.1:9999",
                invokerFactory
        );

        FakeClient fakeClient = new FakeClient();
        fakeClient.init(referenceBean);
        Field clientField = RpcReferenceBean.class.getDeclaredField("client");
        clientField.setAccessible(true);
        clientField.set(referenceBean, fakeClient);

        VersionedService service = (VersionedService) referenceBean.getObject();
        String result = service.hello("copilot");

        Assert.assertEquals("ok", result);
        Assert.assertNotNull(fakeClient.lastRequest);
        Assert.assertEquals("v1", fakeClient.lastRequest.getVersion());
    }

    static class FakeClient extends BaseClient {
        private RpcRequest lastRequest;

        @Override
        public void asyncSend(String address, RpcRequest request) {
            this.lastRequest = request;
            RpcResponse response = new RpcResponse();
            response.setRequestId(request.getRequestId());
            response.setResult("ok");
            rpcReferenceBean.getInvokerFactory().notifyInvokerFuture(request.getRequestId(), response);
        }
    }
}
