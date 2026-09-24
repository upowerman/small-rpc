package io.github.upowerman.core.directory;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** 直连目录：恒返回配置地址的单元素列表；空白地址在构造时大声失败。 */
public class StaticServiceDirectoryTest {

    @Test
    public void listReturnsSingleInstanceOfConfiguredAddress() {
        StaticServiceDirectory directory = new StaticServiceDirectory("127.0.0.1:7081");

        List<ServiceInstance> instances = directory.list("io.github.upowerman.service.HelloService");

        assertEquals(1, instances.size());
        assertEquals("127.0.0.1:7081", instances.get(0).getAddress());
    }

    @Test
    public void listIsIndependentOfServiceName() {
        StaticServiceDirectory directory = new StaticServiceDirectory("127.0.0.1:7081");
        assertEquals("127.0.0.1:7081", directory.list("a.B").get(0).getAddress());
        assertEquals("127.0.0.1:7081", directory.list("c.D").get(0).getAddress());
    }

    @Test
    public void subscribeIsNoop() {
        new StaticServiceDirectory("127.0.0.1:7081").subscribe("a.B");
    }

    @Test
    public void blankAddressFailsLoudly() {
        try {
            new StaticServiceDirectory("   ");
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("address"));
        }
        try {
            new StaticServiceDirectory(null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("address"));
        }
    }
}
