package io.github.upowerman.core.result;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class DefaultResultTest {

    @Test
    public void successCarriesValue() {
        Result result = DefaultResult.success("hello");
        assertSame(Status.SUCCESS, result.status());
        assertEquals("hello", result.value());
        assertNull(result.exception());
    }

    @Test
    public void failureCarriesStatusAndException() {
        RuntimeException ex = new RuntimeException("boom");
        Result result = DefaultResult.failure(Status.TIMEOUT, ex);
        assertSame(Status.TIMEOUT, result.status());
        assertNull(result.value());
        assertSame(ex, result.exception());
    }

    @Test
    public void failureWithoutException() {
        Result result = DefaultResult.failure(Status.SERVICE_NOT_FOUND);
        assertSame(Status.SERVICE_NOT_FOUND, result.status());
        assertNull(result.value());
        assertNull(result.exception());
    }
}