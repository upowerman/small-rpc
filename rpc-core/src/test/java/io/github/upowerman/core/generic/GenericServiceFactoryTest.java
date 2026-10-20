package io.github.upowerman.core.generic;

import io.github.upowerman.core.directory.ServiceInstance;
import io.github.upowerman.core.provider.ReflectiveInvoker;
import io.github.upowerman.core.registry.Registry;
import io.github.upowerman.core.registry.ServiceListener;
import io.github.upowerman.core.transport.InMemoryTransport;
import io.github.upowerman.exception.RpcException;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** C7：泛化调用——不构建接口代理，直接按服务名+方法名+类型名经完整消费端链路调用。 */
public class GenericServiceFactoryTest {

    public interface CalcService {
        int add(int a, int b);

        String join(String prefix, long suffix);
    }

    public static class CalcServiceImpl implements CalcService {
        @Override
        public int add(int a, int b) {
            return a + b;
        }

        @Override
        public String join(String prefix, long suffix) {
            return prefix + "-" + suffix;
        }
    }

    private static Registry stubRegistry(String... addrs) {
        final List<ServiceInstance> instances = new ArrayList<ServiceInstance>();
        for (String addr : addrs) {
            instances.add(new ServiceInstance(addr));
        }
        return new Registry() {
            @Override
            public void init(Map<String, String> param) {
            }

            @Override
            public void destroy() {
            }

            @Override
            public void register(String service, ServiceInstance instance) {
            }

            @Override
            public void unregister(String service, ServiceInstance instance) {
            }

            @Override
            public void subscribe(String service, ServiceListener listener) {
                listener.onChange(new ArrayList<ServiceInstance>(instances));
            }

            @Override
            public void unsubscribe(String service, ServiceListener listener) {
            }
        };
    }

    private static InMemoryTransport provider() {
        InMemoryTransport transport = new InMemoryTransport();
        transport.register("127.0.0.1:7080",
                new ReflectiveInvoker(CalcService.class, new CalcServiceImpl()));
        return transport;
    }

    @Test
    public void invokeWithoutInterfaceProxyThroughFullChain() {
        GenericService generic = GenericServiceFactory.create(provider(), stubRegistry("127.0.0.1:7080"));
        Object result = generic.$invoke(CalcService.class.getName(), "add",
                new String[]{"int", "int"}, new Object[]{1, 2});
        assertEquals(3, result);
    }

    @Test
    public void asyncInvokeCompletesWithBusinessValue() throws Exception {
        GenericService generic = GenericServiceFactory.create(provider(), stubRegistry("127.0.0.1:7080"));
        CompletableFuture<Object> future = generic.$invokeAsync(CalcService.class.getName(), "join",
                new String[]{"java.lang.String", "long"}, new Object[]{"id", 42L});
        assertEquals("id-42", future.get(3, TimeUnit.SECONDS));
    }

    @Test
    public void missingMethodSurfacesAsRpcException() {
        GenericService generic = GenericServiceFactory.create(provider(), stubRegistry("127.0.0.1:7080"));
        try {
            generic.$invoke(CalcService.class.getName(), "nope",
                    new String[0], new Object[0]);
            fail("expected RpcException");
        } catch (RpcException expected) {
            // ok
        }
    }

    @Test
    public void unknownTypeNameFailsLoudlyWithMethodNameContext() {
        try {
            DefaultGenericService.resolveParameterTypes("com.test.Svc", "m",
                    new String[]{"no.such.Type"});
            fail("expected RpcException");
        } catch (RpcException expected) {
            assertTrue("报错应含类型名与方法上下文: " + expected.getMessage(),
                    expected.getMessage().contains("no.such.Type")
                            && expected.getMessage().contains("com.test.Svc#m"));
        }
    }

    @Test
    public void primitiveAndArrayTypeNamesResolve() {
        Class<?>[] types = DefaultGenericService.resolveParameterTypes("s", "m",
                new String[]{"int", "long[]", "java.lang.String"});
        assertEquals(int.class, types[0]);
        assertEquals(long[].class, types[1]);
        assertEquals(String.class, types[2]);
    }
}
