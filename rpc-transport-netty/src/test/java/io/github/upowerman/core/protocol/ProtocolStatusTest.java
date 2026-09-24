package io.github.upowerman.core.protocol;

import io.github.upowerman.core.result.Status;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ProtocolStatusTest {

    @Test
    public void serverSideStatusesRoundTrip() {
        assertEquals(ProtocolStatus.SUCCESS, ProtocolStatus.toCode(Status.SUCCESS));
        assertEquals(ProtocolStatus.SERVICE_NOT_FOUND, ProtocolStatus.toCode(Status.SERVICE_NOT_FOUND));
        assertEquals(ProtocolStatus.METHOD_NOT_FOUND, ProtocolStatus.toCode(Status.METHOD_NOT_FOUND));
        assertEquals(ProtocolStatus.SERIALIZATION_ERROR, ProtocolStatus.toCode(Status.SERIALIZATION_ERROR));
        assertEquals(ProtocolStatus.SERVER_ERROR, ProtocolStatus.toCode(Status.SERVER_ERROR));

        assertEquals(Status.SUCCESS, ProtocolStatus.fromCode(ProtocolStatus.SUCCESS));
        assertEquals(Status.SERVICE_NOT_FOUND, ProtocolStatus.fromCode(ProtocolStatus.SERVICE_NOT_FOUND));
        assertEquals(Status.METHOD_NOT_FOUND, ProtocolStatus.fromCode(ProtocolStatus.METHOD_NOT_FOUND));
        assertEquals(Status.SERIALIZATION_ERROR, ProtocolStatus.fromCode(ProtocolStatus.SERIALIZATION_ERROR));
        assertEquals(Status.SERVER_ERROR, ProtocolStatus.fromCode(ProtocolStatus.SERVER_ERROR));
    }

    /** 线上字节值是跨版本契约：钉死字面量，避免常量被改动而往返测试仍绿 */
    @Test
    public void wireByteValuesArePinned() {
        assertEquals((byte) 0, ProtocolStatus.SUCCESS);
        assertEquals((byte) 1, ProtocolStatus.SERVICE_NOT_FOUND);
        assertEquals((byte) 2, ProtocolStatus.METHOD_NOT_FOUND);
        assertEquals((byte) 3, ProtocolStatus.SERIALIZATION_ERROR);
        assertEquals((byte) 4, ProtocolStatus.SERVER_ERROR);
    }

    @Test
    public void localOnlyStatusesAreNotRepresentable() {
        try {
            ProtocolStatus.toCode(Status.TIMEOUT);
            fail("TIMEOUT is caller-local, must not enter a frame");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            ProtocolStatus.toCode(Status.NETWORK_ERROR);
            fail("NETWORK_ERROR is caller-local, must not enter a frame");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void unknownCodeThrowsProtocolException() {
        try {
            ProtocolStatus.fromCode((byte) 99);
            fail("expected ProtocolException");
        } catch (ProtocolException expected) {
            // ok
        }
    }

    /** 诊断信息按无符号字节呈现：对端新版本发来 200 时报 "200"，不报 "-56" */
    @Test
    public void unknownCodeIsReportedUnsigned() {
        try {
            ProtocolStatus.fromCode((byte) 200);
            fail("expected ProtocolException");
        } catch (ProtocolException expected) {
            assertTrue("expected unsigned rendering, got: " + expected.getMessage(),
                    expected.getMessage().contains("200"));
        }
    }
}
