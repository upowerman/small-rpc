package io.github.upowerman.core.server;

import io.github.upowerman.core.RpcConstants;
import io.github.upowerman.core.invocation.GenericInvocation;
import io.github.upowerman.core.provider.ReflectiveInvoker;
import io.github.upowerman.core.serialize.HessianSerializer;
import io.github.upowerman.core.serialize.SerializerRegistry;
import io.github.upowerman.core.transport.NettyTransport;
import io.github.upowerman.core.invoker.RemoteInvoker;
import org.junit.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * B5：优雅停机排空——请求在途时发起 shutdown，已受理请求仍应完成并回包。
 */
public class RpcServerGracefulShutdownTest {

    public interface SlowService {
        String slow(String msg);
    }

    public static class SlowServiceImpl implements SlowService {
        @Override
        public String slow(String msg) {
            try {
                Thread.sleep(600);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "done:" + msg;
        }
    }

    private static int freePort() throws IOException {
        ServerSocket socket = new ServerSocket(0);
        try {
            return socket.getLocalPort();
        } finally {
            socket.close();
        }
    }

    @Test
    public void inFlightRequestSurvivesShutdownDrain() throws Exception {
        int port = freePort();
        SerializerRegistry serializers = new SerializerRegistry().register(new HessianSerializer());
        RpcServer server = new RpcServer(port, serializers, 2, 0, 5000L);
        server.register(SlowService.class.getName(),
                new ReflectiveInvoker(SlowService.class, new SlowServiceImpl()));
        server.start();
        NettyTransport transport = new NettyTransport(new HessianSerializer());
        try {
            Map<String, Object> attachments = new HashMap<String, Object>();
            attachments.put(RpcConstants.ATTACH_ADDRESS, "127.0.0.1:" + port);
            attachments.put(RpcConstants.ATTACH_TIMEOUT, 5000L);
            CompletableFuture<io.github.upowerman.core.result.Result> future =
                    new RemoteInvoker(transport, SlowService.class).invoke(
                            new GenericInvocation(SlowService.class.getName(), "slow",
                                    new Class<?>[]{String.class}, new Object[]{"drain"},
                                    attachments));

            // 等请求确实到达服务端并进入业务线程（远小于 slow 的 600ms）
            Thread.sleep(200);
            server.shutdown();

            io.github.upowerman.core.result.Result result = future.get(5, TimeUnit.SECONDS);
            assertTrue("排空后已受理请求应成功，实际状态 " + result.status()
                    + (result.exception() != null ? " / " + result.exception() : ""),
                    result.status() == io.github.upowerman.core.result.Status.SUCCESS);
            assertEquals("done:drain", result.value());
        } finally {
            transport.shutdown();
            server.shutdown();
        }
    }
}
