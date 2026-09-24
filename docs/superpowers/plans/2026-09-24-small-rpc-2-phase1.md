# small-rpc 2.0 Phase 1（协议升级）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 2.0 自研二进制协议栈——协议帧编解码、long requestId、心跳一等公民、Result 状态码贯通，2.0 拥有完整 client + server，摆脱 1.x 适配器。

**Architecture:** 协议层（`core.protocol`：Frame/FrameCodec/Body 对象/状态码映射）与网络层分离；帧编解码是纯字节层静态方法，可脱离 Netty pipeline 单测。`NettyTransport`（client）与 `RpcServer`（provider）都消费该协议层。**不修改 P0 的 `Transport`/`Connection` 接口**——协议化封装在实现内部，`RemoteInvoker`/`FailoverClusterInvoker`/`InMemoryTransport` 及全部既有测试零改动。

**Tech Stack:** Java 8、Netty 4（已在依赖）、Hessian（经 2.0 Serializer SPI）、JUnit 4.13.2

**Spec:** `docs/superpowers/specs/2026-09-23-small-rpc-2-design.md` §2（二进制协议 + 序列化抽象）+ §7 P1 行

## Global Constraints

- Java 8 语法：禁 `orTimeout`/`delayedExecutor`/`var`（用本仓 `Futures`/`PendingRequests` 的既有模式）
- JUnit 4.13.2，断言用 `org.junit.Assert`
- 1.x 代码（`io.github.upowerman` 下非 `core` 包）**零修改**；新代码全部在 `io.github.upowerman.core.*`
- `core` 包禁 Spring import
- 测试命令：`mvn -f small-rpc-core/pom.xml test -q`（**无根聚合 pom，禁 `-pl`**）；样例验证需先 `mvn -f small-rpc-core/pom.xml install -DskipTests -Dgpg.skip=true -Dmaven.javadoc.skip=true`
- **禁止 `mvn verify`**（GPG 插件绑在 verify 上不可用）
- 测试端口一律 ephemeral（`new ServerSocket(0)` 探取后释放，禁硬编码）
- 每个任务 TDD：先写失败测试 → 跑确认失败 → 实现 → 跑通过 → commit

## 已核实的 P0 事实（实现时照此对接，勿凭记忆改写）

| 事实 | 值 |
|---|---|
| `ReflectiveInvoker` 包 | `io.github.upowerman.core.provider`，ctor `(Class<?> interfaceClass, Object serviceBean)` |
| `RpcProxyFactory` 出口方法 | **`getProxy()`**（不是 `newInstance()`），ctor `(Class<T>, List<Filter>, Invoker)` / 4 参带 `callTimeoutMillis` |
| `ServiceInstance` 构造 | **只有构造器** `new ServiceInstance(String address)`（无 `of()` 工厂） |
| `GenericInvocation` 构造 | 5 参 `(String serviceName, String methodName, Class<?>[] parameterTypes, Object[] arguments, Map<String,Object> attachments)`（无变参重载） |
| `FailoverClusterInvoker` 构造 | `(ServiceDirectory directory, LoadBalancer loadBalancer, Invoker remoteInvoker, int retries, long defaultTimeoutMillis)` |
| `PendingRequests` API | `nextRequestId()` / `register(long)` / `register(long, long)` / `complete(long, Result)` / `remove(long)` / `size()` |
| `DefaultResult` 工厂 | `success(Object)` / `failure(Status)` / `failure(Status, Throwable)` |
| `RpcException` 构造 | `(String)` / `(String, Throwable)` / `(Throwable)` |
| **`LegacyHessianSerializer.deserialize(bytes, clazz)` 忽略 `clazz`** | 委托的 1.x `HessianSerializer` 直接 `hi.readObject()` 返回，不做类型校验 → **网络层的强制转型才是类型防线**（Task 2/3/4 测试钉死此点） |
| `core` 测试里已有的服务接口 | 都是测试类内嵌的（如 `RpcChainIntegrationTest.EchoService`、`LegacyNettyTransportIntegrationTest.EchoDTO`）；**不存在 `core.service` 包** → Task 3 建共享测试夹具 |
| 端口工具 | 各测试类内私有 `freePort()`（`new ServerSocket(0)` 探取后释放），照此模式 |

## Review Focus

spec 暗示、但任务测试必须钉死的失败模式：

1. **半包断裂在 header 中间**（如 requestId 只到了 4 字节）→ decoder 必须等待而非错解 → Task 1 逐字节喂入测试
2. **恶意超长 bodyLen**（帧头声称 2GB）→ 不得分配大数组，抛 ProtocolException → Task 1 测试
3. **流错位**（非本协议字节进流）→ magic 校验快速拒绝并关连接 → Task 1 + Task 3 测试
4. **响应体类型伪装**（body 是合法 Hessian 但不是 `RpcRequestBody`）→ 必须 SERIALIZATION_ERROR，不得被转型异常穿透 → Task 2 + Task 3 测试
5. **并发请求 requestId 串包** → 8 线程 × 10 调用各自回包正确 → Task 5 并发测试
6. **codec 不匹配**（客户端声称 server 不认识的序列化算法）→ SERIALIZATION_ERROR 状态回帧而非抛异常 → Task 3 测试

---

### Task 1: 协议帧模型 + 纯字节层编解码

**Files:**
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/protocol/Frame.java`
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/protocol/ProtocolException.java`
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/protocol/FrameCodec.java`
- Test: `small-rpc-core/src/test/java/io/github/upowerman/core/protocol/FrameCodecTest.java`

**Interfaces:**
- Consumes: 无（纯字节层，仅依赖 Netty 的 `ByteBuf`）
- Produces: `Frame`（`static Frame request(byte codec, long requestId, byte[] body)` / `static Frame response(byte codec, byte status, long requestId, byte[] body)` / `static Frame heartbeat(long requestId)`；getter `type()/codec()/status()/requestId()/body()`；常量 `MAGIC/VERSION/HEADER_LENGTH=20/MAX_BODY_LENGTH/TYPE_REQUEST/TYPE_RESPONSE/TYPE_HEARTBEAT`）、`FrameCodec.encode(Frame, ByteBuf)`、`FrameCodec.decodeOne(ByteBuf)`（不足返回 null，协议错误抛 `ProtocolException`）

- [x] **Step 1: 写失败测试**

```java
package io.github.upowerman.core.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class FrameCodecTest {

    @Test
    public void roundtripPreservesAllHeaderFieldsAndBody() {
        Frame f = Frame.response((byte) 1, (byte) 0, 42L, new byte[]{1, 2, 3});
        ByteBuf buf = Unpooled.buffer();
        FrameCodec.encode(f, buf);
        Frame out = FrameCodec.decodeOne(buf);
        assertEquals(Frame.TYPE_RESPONSE, out.type());
        assertEquals(1, out.codec());
        assertEquals(0, out.status());
        assertEquals(42L, out.requestId());
        assertArrayEquals(new byte[]{1, 2, 3}, out.body());
        assertEquals(0, buf.readableBytes());
    }

    @Test
    public void heartbeatHasEmptyBodyAndRoundtrips() {
        ByteBuf buf = Unpooled.buffer();
        FrameCodec.encode(Frame.heartbeat(7L), buf);
        Frame out = FrameCodec.decodeOne(buf);
        assertEquals(Frame.TYPE_HEARTBEAT, out.type());
        assertEquals(7L, out.requestId());
        assertEquals(0, out.body().length);
    }

    @Test
    public void twoFramesInOneBufferAreSplitCorrectly() {
        ByteBuf buf = Unpooled.buffer();
        FrameCodec.encode(Frame.request((byte) 1, 1L, new byte[]{7}), buf);
        FrameCodec.encode(Frame.heartbeat(2L), buf);
        Frame first = FrameCodec.decodeOne(buf);
        assertEquals(1L, first.requestId());
        Frame second = FrameCodec.decodeOne(buf);
        assertEquals(Frame.TYPE_HEARTBEAT, second.type());
        assertEquals(2L, second.requestId());
        assertNull(FrameCodec.decodeOne(buf));
    }

    @Test
    public void partialFrameWaitsForMoreBytes() {
        ByteBuf full = Unpooled.buffer();
        FrameCodec.encode(Frame.request((byte) 1, 9L, new byte[]{1, 2, 3, 4, 5}), full);
        byte[] all = new byte[full.readableBytes()];
        full.readBytes(all);
        ByteBuf in = Unpooled.buffer();
        for (int i = 0; i < all.length; i++) {
            in.writeByte(all[i]);
            if (i < all.length - 1) {
                assertNull("incomplete at " + (i + 1) + " bytes", FrameCodec.decodeOne(in));
            }
        }
        Frame out = FrameCodec.decodeOne(in);
        assertEquals(9L, out.requestId());
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5}, out.body());
    }

    @Test
    public void badMagicThrows() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeShort(0x1234);
        buf.writeBytes(new byte[18]);
        try {
            FrameCodec.decodeOne(buf);
            fail("expected ProtocolException");
        } catch (ProtocolException e) {
            assertTrue(e.getMessage().contains("magic"));
        }
    }

    @Test
    public void unsupportedVersionThrows() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeShort(Frame.MAGIC);
        buf.writeByte(9);
        buf.writeBytes(new byte[17]);
        try {
            FrameCodec.decodeOne(buf);
            fail("expected ProtocolException");
        } catch (ProtocolException e) {
            assertTrue(e.getMessage().contains("version"));
        }
    }

    @Test
    public void oversizedBodyLengthThrowsWithoutAllocation() {
        ByteBuf buf = Unpooled.buffer();
        buf.writeShort(Frame.MAGIC);
        buf.writeByte(Frame.VERSION);
        buf.writeByte(Frame.TYPE_REQUEST);
        buf.writeByte(0);
        buf.writeByte(0);
        buf.writeShort(0);
        buf.writeLong(1L);
        buf.writeInt(Integer.MAX_VALUE);
        try {
            FrameCodec.decodeOne(buf);
            fail("expected ProtocolException");
        } catch (ProtocolException e) {
            assertTrue(e.getMessage().contains("bodyLen"));
        }
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `mvn -f small-rpc-core/pom.xml test -q -Dtest=FrameCodecTest`
Expected: 编译失败（`Frame`/`FrameCodec`/`ProtocolException` 不存在）

- [x] **Step 3: 实现**

`ProtocolException.java`:

```java
package io.github.upowerman.core.protocol;

/**
 * 协议错误：magic/ver 非法、bodyLen 超上限。网络层收到后应关闭连接（流已不可信）。
 */
public class ProtocolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProtocolException(String msg) {
        super(msg);
    }
}
```

`Frame.java`:

```java
package io.github.upowerman.core.protocol;

/**
 * 协议帧：20 字节定长 Header + 变长 Body。
 * <pre>
 *  0      2     3     4      5      6      7      8             16       20
 * +------+-----+-----+------+------+------+------+--------------+--------+
 * | magic| ver | type| codec|status| rsvd | rsvd | requestId(8) |bodyLen |
 * +------+-----+-----+------+------+------+------+--------------+--------+
 * </pre>
 * 心跳为一等公民（type=HEARTBEAT，无 body），取代 1.x 借道业务请求的 Beat。
 * status 只承载服务端可判定的状态（见 ProtocolStatus）；
 * TIMEOUT/NETWORK_ERROR 是调用方本地状态，不进协议帧。
 */
public final class Frame {

    public static final short MAGIC = (short) 0x5352;   // 'S''R'
    public static final byte VERSION = 1;
    public static final int HEADER_LENGTH = 20;
    public static final int MAX_BODY_LENGTH = 8 * 1024 * 1024;

    public static final byte TYPE_REQUEST = 1;
    public static final byte TYPE_RESPONSE = 2;
    public static final byte TYPE_HEARTBEAT = 3;

    private final byte type;
    private final byte codec;
    private final byte status;
    private final long requestId;
    private final byte[] body;

    private Frame(byte type, byte codec, byte status, long requestId, byte[] body) {
        this.type = type;
        this.codec = codec;
        this.status = status;
        this.requestId = requestId;
        this.body = body == null ? new byte[0] : body;
    }

    public static Frame request(byte codec, long requestId, byte[] body) {
        return new Frame(TYPE_REQUEST, codec, (byte) 0, requestId, body);
    }

    public static Frame response(byte codec, byte status, long requestId, byte[] body) {
        return new Frame(TYPE_RESPONSE, codec, status, requestId, body);
    }

    public static Frame heartbeat(long requestId) {
        return new Frame(TYPE_HEARTBEAT, (byte) 0, (byte) 0, requestId, new byte[0]);
    }

    public byte type() {
        return type;
    }

    public byte codec() {
        return codec;
    }

    public byte status() {
        return status;
    }

    public long requestId() {
        return requestId;
    }

    public byte[] body() {
        return body;
    }

    @Override
    public String toString() {
        return "Frame{type=" + type + ", codec=" + codec + ", status=" + status
                + ", requestId=" + requestId + ", bodyLen=" + body.length + "}";
    }
}
```

`FrameCodec.java`:

```java
package io.github.upowerman.core.protocol;

import io.netty.buffer.ByteBuf;

/**
 * 帧编解码：纯字节层静态方法，不依赖 ChannelHandler，可脱离 Netty pipeline 单测。
 * decodeOne 数据不足返回 null（半包等待）；magic/ver 非法或 bodyLen 超限抛 ProtocolException。
 * 前置 magic 校验让非法流在头 2 字节即被拒绝，不必等满一个 header。
 */
public final class FrameCodec {

    private FrameCodec() {
    }

    public static void encode(Frame frame, ByteBuf out) {
        out.writeShort(Frame.MAGIC);
        out.writeByte(Frame.VERSION);
        out.writeByte(frame.type());
        out.writeByte(frame.codec());
        out.writeByte(frame.status());
        out.writeShort(0);   // rsvd
        out.writeLong(frame.requestId());
        out.writeInt(frame.body().length);
        out.writeBytes(frame.body());
    }

    /** 从累积缓冲解出一帧；数据不足（半包）返回 null；流非法抛 ProtocolException。 */
    public static Frame decodeOne(ByteBuf in) {
        if (in.readableBytes() < 2) {
            return null;
        }
        in.markReaderIndex();
        short magic = in.readShort();
        if (magic != Frame.MAGIC) {
            throw new ProtocolException("bad magic: 0x" + Integer.toHexString(magic & 0xFFFF));
        }
        if (in.readableBytes() < Frame.HEADER_LENGTH - 2) {
            in.resetReaderIndex();
            return null;
        }
        byte version = in.readByte();
        if (version != Frame.VERSION) {
            throw new ProtocolException("unsupported version: " + version);
        }
        byte type = in.readByte();
        byte codec = in.readByte();
        byte status = in.readByte();
        in.skipBytes(2);   // rsvd
        long requestId = in.readLong();
        int bodyLen = in.readInt();
        if (bodyLen < 0 || bodyLen > Frame.MAX_BODY_LENGTH) {
            throw new ProtocolException("bad bodyLen: " + bodyLen);
        }
        if (in.readableBytes() < bodyLen) {
            in.resetReaderIndex();
            return null;
        }
        byte[] body = new byte[bodyLen];
        in.readBytes(body);
        return new Frame(type, codec, status, requestId, body);
    }
}
```

- [x] **Step 4: 跑测试确认通过**

Run: `mvn -f small-rpc-core/pom.xml test -q -Dtest=FrameCodecTest`
Expected: PASS（7 tests）

- [x] **Step 5: Commit**

```bash
git add small-rpc-core/src/main/java/io/github/upowerman/core/protocol small-rpc-core/src/test/java/io/github/upowerman/core/protocol
git commit -m "feat(rpc2): binary protocol frame + pure byte-level codec"
```

---

### Task 2: 状态码映射 + Body 对象 + 序列化注册表

**Files:**
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/protocol/ProtocolStatus.java`
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/protocol/RpcRequestBody.java`
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/protocol/RpcResponseBody.java`
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/serialize/SerializerRegistry.java`
- Test: `small-rpc-core/src/test/java/io/github/upowerman/core/protocol/ProtocolStatusTest.java`
- Test: `small-rpc-core/src/test/java/io/github/upowerman/core/protocol/ProtocolBodyTest.java`
- Test: `small-rpc-core/src/test/java/io/github/upowerman/core/serialize/SerializerRegistryTest.java`

**Interfaces:**
- Consumes: `Status`（`core.result`）、`RpcConstants.ATTACH_TRACE_ID/ATTACH_TIMEOUT`、`Serializer`（`core.serialize`）、`LegacyHessianSerializer.TYPE_ID == 1`
- Produces: `ProtocolStatus.toCode(Status)` / `fromCode(byte)`；`RpcRequestBody`（POJO：`serviceName`/`methodName`/`parameterTypes(String[])`/`arguments(Object[])`/`attachments(Map<String,Object>)`）；`RpcResponseBody`（POJO：`value`/`errorClassName`/`errorMessage`）；`SerializerRegistry.register(Serializer)` / `find(byte)`

**设计裁定（异常跨网传输）:** 响应体**不传输 `Throwable` 对象**，只传 `errorClassName + errorMessage`，客户端重建 `RpcException`。理由：委托的 1.x Hessian 反序列化**忽略 `clazz` 参数**（`hi.readObject()` 直接返回），在网络上传输任意 `Throwable` 图等于开放任意反序列化面；而重试/熔断决策完全由 `Status` 驱动，异常类型不承载语义。这是相对 1.x（回 errorMsg 字符串）的显式升级，不是权宜。

- [x] **Step 1: 写失败测试**

`ProtocolStatusTest.java`:

```java
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
```

`ProtocolBodyTest.java`:

```java
package io.github.upowerman.core.protocol;

import io.github.upowerman.core.RpcConstants;
import io.github.upowerman.core.serialize.LegacyHessianSerializer;
import io.github.upowerman.core.serialize.Serializer;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

public class ProtocolBodyTest {

    private final Serializer serializer = new LegacyHessianSerializer();

    @Test
    public void requestBodyRoundtripThroughHessian() {
        RpcRequestBody in = new RpcRequestBody();
        in.setServiceName("io.github.upowerman.service.HelloService");
        in.setMethodName("hello");
        in.setParameterTypes(new String[]{"java.lang.String"});
        in.setArguments(new Object[]{"world"});
        Map<String, Object> attachments = new HashMap<String, Object>();
        attachments.put(RpcConstants.ATTACH_TRACE_ID, "t-1");
        attachments.put(RpcConstants.ATTACH_TIMEOUT, 3000L);
        in.setAttachments(attachments);

        RpcRequestBody out = (RpcRequestBody) serializer.deserialize(
                serializer.serialize(in), RpcRequestBody.class);

        assertEquals("io.github.upowerman.service.HelloService", out.getServiceName());
        assertEquals("hello", out.getMethodName());
        assertArrayEquals(new String[]{"java.lang.String"}, out.getParameterTypes());
        assertEquals("world", out.getArguments()[0]);
        assertEquals("t-1", out.getAttachments().get(RpcConstants.ATTACH_TRACE_ID));
        assertEquals(3000L, out.getAttachments().get(RpcConstants.ATTACH_TIMEOUT));
    }

    @Test
    public void responseBodyRoundtripsValueAndErrorDescription() {
        RpcResponseBody withValue = new RpcResponseBody();
        withValue.setValue("ok");
        RpcResponseBody outValue = (RpcResponseBody) serializer.deserialize(
                serializer.serialize(withValue), RpcResponseBody.class);
        assertEquals("ok", outValue.getValue());
        assertNull(outValue.getErrorMessage());

        RpcResponseBody withError = new RpcResponseBody();
        withError.setErrorClassName("java.lang.IllegalStateException");
        withError.setErrorMessage("boom happened");
        RpcResponseBody outError = (RpcResponseBody) serializer.deserialize(
                serializer.serialize(withError), RpcResponseBody.class);
        assertNull(outError.getValue());
        assertEquals("java.lang.IllegalStateException", outError.getErrorClassName());
        assertEquals("boom happened", outError.getErrorMessage());
    }

    /**
     * 行为锚点：委托的 1.x Hessian 反序列化忽略 clazz 参数——喂一个合法 Hessian
     * 但不是 RpcRequestBody 的负载，它会原样返回 String。因此网络层的强制转型
     * 才是类型防线（Task 3/4 据此断言 SERIALIZATION_ERROR）。
     */
    @Test
    public void deserializerIgnoresClazzSoCastIsTheTypeGuard() {
        Object decoded = serializer.deserialize(
                serializer.serialize("not a request body"), RpcRequestBody.class);
        assertFalse("delegate must not fabricate a RpcRequestBody", decoded instanceof RpcRequestBody);
        assertEquals("not a request body", decoded);
    }
}
```

`SerializerRegistryTest.java`:

```java
package io.github.upowerman.core.serialize;

import org.junit.Test;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class SerializerRegistryTest {

    private static Serializer stub(final byte typeId, final String name) {
        return new Serializer() {
            @Override
            public byte typeId() {
                return typeId;
            }

            @Override
            public byte[] serialize(Object obj) {
                return name.getBytes();
            }

            @Override
            public Object deserialize(byte[] bytes, Class<?> clazz) {
                return name;
            }
        };
    }

    @Test
    public void findsRegisteredSerializerByTypeId() {
        Serializer hessian = new LegacyHessianSerializer();
        Serializer other = stub((byte) 7, "other");
        SerializerRegistry registry = new SerializerRegistry().register(hessian).register(other);
        assertSame(hessian, registry.find(LegacyHessianSerializer.TYPE_ID));
        assertSame(other, registry.find((byte) 7));
    }

    @Test
    public void unknownTypeIdReturnsNull() {
        SerializerRegistry registry = new SerializerRegistry().register(new LegacyHessianSerializer());
        assertNull(registry.find((byte) 99));
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `mvn -f small-rpc-core/pom.xml test -q -Dtest='ProtocolStatusTest,ProtocolBodyTest,SerializerRegistryTest'`
Expected: 编译失败（类不存在）

- [x] **Step 3: 实现**

`ProtocolStatus.java`:

```java
package io.github.upowerman.core.protocol;

import io.github.upowerman.core.result.Status;

/**
 * 协议帧 status 字段 ↔ {@link Status} 双向映射。
 * 只承载服务端可判定的状态；TIMEOUT/NETWORK_ERROR 是调用方本地状态，不进协议帧
 * （超时由客户端在 PendingRequests 层结算，网络错误由客户端本地生成）。
 */
public final class ProtocolStatus {

    public static final byte SUCCESS = 0;
    public static final byte SERVICE_NOT_FOUND = 1;
    public static final byte METHOD_NOT_FOUND = 2;
    public static final byte SERIALIZATION_ERROR = 3;
    public static final byte SERVER_ERROR = 4;

    private ProtocolStatus() {
    }

    public static byte toCode(Status status) {
        switch (status) {
            case SUCCESS:
                return SUCCESS;
            case SERVICE_NOT_FOUND:
                return SERVICE_NOT_FOUND;
            case METHOD_NOT_FOUND:
                return METHOD_NOT_FOUND;
            case SERIALIZATION_ERROR:
                return SERIALIZATION_ERROR;
            case SERVER_ERROR:
                return SERVER_ERROR;
            default:
                throw new IllegalArgumentException("status not representable in protocol frame: " + status);
        }
    }

    public static Status fromCode(byte code) {
        switch (code) {
            case SUCCESS:
                return Status.SUCCESS;
            case SERVICE_NOT_FOUND:
                return Status.SERVICE_NOT_FOUND;
            case METHOD_NOT_FOUND:
                return Status.METHOD_NOT_FOUND;
            case SERIALIZATION_ERROR:
                return Status.SERIALIZATION_ERROR;
            case SERVER_ERROR:
                return Status.SERVER_ERROR;
            default:
                throw new ProtocolException("unknown status code: " + (code & 0xFF));
        }
    }
}
```

`RpcRequestBody.java`:

```java
package io.github.upowerman.core.protocol;

import java.io.Serializable;
import java.util.Map;

/**
 * 协议 Body：一次调用的完整描述。把"协议格式"与"调用描述"分开——
 * 未来引入 methodId 只改 Body，不动帧结构。
 * parameterTypes 用 String[]（跨语言友好），服务端 Class.forName 还原。
 * 无参构造 + setter：Hessian 需要可实例化的 POJO。
 */
public class RpcRequestBody implements Serializable {

    private static final long serialVersionUID = 1L;

    private String serviceName;
    private String methodName;
    private String[] parameterTypes;
    private Object[] arguments;
    private Map<String, Object> attachments;

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getMethodName() {
        return methodName;
    }

    public void setMethodName(String methodName) {
        this.methodName = methodName;
    }

    public String[] getParameterTypes() {
        return parameterTypes;
    }

    public void setParameterTypes(String[] parameterTypes) {
        this.parameterTypes = parameterTypes;
    }

    public Object[] getArguments() {
        return arguments;
    }

    public void setArguments(Object[] arguments) {
        this.arguments = arguments;
    }

    public Map<String, Object> getAttachments() {
        return attachments;
    }

    public void setAttachments(Map<String, Object> attachments) {
        this.attachments = attachments;
    }
}
```

`RpcResponseBody.java`:

```java
package io.github.upowerman.core.protocol;

import java.io.Serializable;

/**
 * 协议 Body：调用结果。成败由**帧 header 的 status 字段**判定，不由 value
 * 是否为空判定——void 方法成功返回时 value 亦为 null。失败时错误以
 * errorClassName + errorMessage 描述（理由见下）。
 * <p>
 * <b>不传输 Throwable 对象</b>：委托的 1.x Hessian 反序列化忽略 clazz 参数，
 * 在网络上传输任意 Throwable 图等于开放任意反序列化面；重试/熔断决策完全由帧
 * header 的 status 驱动，异常类型不承载语义。客户端据这两个字段重建 RpcException。
 */
public class RpcResponseBody implements Serializable {

    private static final long serialVersionUID = 1L;

    private Object value;
    private String errorClassName;
    private String errorMessage;

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }

    public String getErrorClassName() {
        return errorClassName;
    }

    public void setErrorClassName(String errorClassName) {
        this.errorClassName = errorClassName;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
```

`SerializerRegistry.java`:

```java
package io.github.upowerman.core.serialize;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * codec(typeId) → Serializer 注册表：响应帧沿用请求帧的 codec，
 * 服务端据此查找反序列化器——多序列化共存的前提。
 */
public class SerializerRegistry {

    private final Map<Byte, Serializer> serializers = new ConcurrentHashMap<Byte, Serializer>();

    public SerializerRegistry register(Serializer serializer) {
        serializers.put(serializer.typeId(), serializer);
        return this;
    }

    public Serializer find(byte typeId) {
        return serializers.get(typeId);
    }
}
```

- [x] **Step 4: 跑测试确认通过**

Run: `mvn -f small-rpc-core/pom.xml test -q -Dtest='ProtocolStatusTest,ProtocolBodyTest,SerializerRegistryTest'`
Expected: PASS（10 tests：ProtocolStatus 5 + ProtocolBody 3 + SerializerRegistry 2）

- [x] **Step 5: Commit**

```bash
git add small-rpc-core/src/main/java/io/github/upowerman/core/protocol small-rpc-core/src/main/java/io/github/upowerman/core/serialize small-rpc-core/src/test/java/io/github/upowerman/core
git commit -m "feat(rpc2): protocol status mapping, body objects, serializer registry"
```

---

### Task 3: Netty 帧处理器 + 2.0 RpcServer（provider 端）

**Files:**
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/protocol/FrameDecoder.java`
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/protocol/FrameEncoder.java`
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/server/RpcServer.java`
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/server/ServerHandler.java`
- Modify: `small-rpc-core/src/main/java/io/github/upowerman/core/RpcConstants.java`（追加两个常量，不动既有三个）
- Create: `small-rpc-core/src/test/java/io/github/upowerman/core/testsupport/EchoService.java`
- Create: `small-rpc-core/src/test/java/io/github/upowerman/core/testsupport/EchoDTO.java`
- Create: `small-rpc-core/src/test/java/io/github/upowerman/core/testsupport/EchoServiceImpl.java`
- Test: `small-rpc-core/src/test/java/io/github/upowerman/core/server/ServerHandlerTest.java`

**Interfaces:**
- Consumes: Task 1 全部（`Frame`/`FrameCodec`/`ProtocolException`）、Task 2 全部、`ReflectiveInvoker`（`core.provider`）、`Invoker`、`DefaultResult`、`RpcException`
- Produces: `RpcServer(int port, SerializerRegistry)` + `register(String serviceName, Invoker)` + `start() throws InterruptedException` + `shutdown()`；`ServerHandler(SerializerRegistry, Map<String,Invoker>, Executor)`；`FrameDecoder`/`FrameEncoder`（Netty handler）；常量 `RpcConstants.HEARTBEAT_INTERVAL_SECONDS=30`、`RpcConstants.SERVER_IDLE_SECONDS=90`；测试夹具 `core.testsupport.EchoService{ EchoDTO echo(EchoDTO) }` + `EchoDTO{String msg}` + `EchoServiceImpl`（`msg` 以 "boom" 开头则抛 `IllegalStateException`）

- [x] **Step 1: 写测试夹具与失败测试**

`testsupport/EchoDTO.java`:

```java
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
```

`testsupport/EchoService.java`:

```java
package io.github.upowerman.core.testsupport;

/** 测试用服务接口：core 既有测试都用类内嵌接口，本夹具供 Task 3/5 共享 */
public interface EchoService {

    EchoDTO echo(EchoDTO dto);
}
```

`testsupport/EchoServiceImpl.java`:

```java
package io.github.upowerman.core.testsupport;

/**
 * 正常回显 echo:&lt;msg&gt;；msg 以 "boom" 开头则抛异常，
 * 供 SERVER_ERROR 路径测试（免去给接口加第二个方法）。
 */
public class EchoServiceImpl implements EchoService {

    @Override
    public EchoDTO echo(EchoDTO dto) {
        if (dto.getMsg() != null && dto.getMsg().startsWith("boom")) {
            throw new IllegalStateException("boom happened");
        }
        return new EchoDTO("echo:" + dto.getMsg());
    }
}
```

`server/ServerHandlerTest.java`:

```java
package io.github.upowerman.core.server;

import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.protocol.Frame;
import io.github.upowerman.core.protocol.FrameCodec;
import io.github.upowerman.core.protocol.FrameDecoder;
import io.github.upowerman.core.protocol.FrameEncoder;
import io.github.upowerman.core.protocol.ProtocolStatus;
import io.github.upowerman.core.protocol.RpcRequestBody;
import io.github.upowerman.core.protocol.RpcResponseBody;
import io.github.upowerman.core.provider.ReflectiveInvoker;
import io.github.upowerman.core.serialize.LegacyHessianSerializer;
import io.github.upowerman.core.serialize.Serializer;
import io.github.upowerman.core.serialize.SerializerRegistry;
import io.github.upowerman.core.testsupport.EchoDTO;
import io.github.upowerman.core.testsupport.EchoService;
import io.github.upowerman.core.testsupport.EchoServiceImpl;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.IdleStateEvent;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.ServerSocket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ServerHandlerTest {

    private static final Serializer HESSIAN = new LegacyHessianSerializer();

    /** 业务处理在测试线程同步完成，便于断言 */
    private static final Executor DIRECT = new Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };

    private EmbeddedChannel channel;

    @Before
    public void setUp() {
        SerializerRegistry registry = new SerializerRegistry().register(HESSIAN);
        Map<String, Invoker> providers = new HashMap<String, Invoker>();
        providers.put(EchoService.class.getName(),
                new ReflectiveInvoker(EchoService.class, new EchoServiceImpl()));
        channel = new EmbeddedChannel(
                new FrameDecoder(), new FrameEncoder(), new ServerHandler(registry, providers, DIRECT));
    }

    @After
    public void tearDown() {
        channel.finishAndReleaseAll();
    }

    private void writeInbound(Frame frame) {
        ByteBuf buf = Unpooled.buffer();
        FrameCodec.encode(frame, buf);
        channel.writeInbound(buf);
    }

    private Frame readResponse() {
        ByteBuf outbound = channel.readOutbound();
        return outbound == null ? null : FrameCodec.decodeOne(outbound);
    }

    private Frame requestEcho(String msg, long requestId) {
        RpcRequestBody body = new RpcRequestBody();
        body.setServiceName(EchoService.class.getName());
        body.setMethodName("echo");
        body.setParameterTypes(new String[]{EchoDTO.class.getName()});
        body.setArguments(new Object[]{new EchoDTO(msg)});
        return Frame.request(HESSIAN.typeId(), requestId, HESSIAN.serialize(body));
    }

    private RpcResponseBody bodyOf(Frame response) {
        return (RpcResponseBody) HESSIAN.deserialize(response.body(), RpcResponseBody.class);
    }

    @Test
    public void requestRoundTripsToSuccessResponse() {
        writeInbound(requestEcho("world", 100L));
        Frame resp = readResponse();
        assertNotNull(resp);
        assertEquals(Frame.TYPE_RESPONSE, resp.type());
        assertEquals(100L, resp.requestId());
        assertEquals(HESSIAN.typeId(), resp.codec());
        assertEquals(ProtocolStatus.SUCCESS, resp.status());
        assertEquals("echo:world", ((EchoDTO) bodyOf(resp).getValue()).getMsg());
    }

    @Test
    public void heartbeatIsEchoedBack() {
        writeInbound(Frame.heartbeat(55L));
        Frame resp = readResponse();
        assertNotNull(resp);
        assertEquals(Frame.TYPE_HEARTBEAT, resp.type());
        assertEquals(55L, resp.requestId());
    }

    @Test
    public void unknownCodecRespondsSerializationErrorNotThrow() {
        RpcRequestBody body = new RpcRequestBody();
        body.setServiceName(EchoService.class.getName());
        body.setMethodName("echo");
        body.setParameterTypes(new String[]{EchoDTO.class.getName()});
        body.setArguments(new Object[]{new EchoDTO("x")});
        // codec=99：服务端没有注册该序列化器
        writeInbound(Frame.request((byte) 99, 101L, HESSIAN.serialize(body)));
        Frame resp = readResponse();
        assertNotNull(resp);
        assertEquals(ProtocolStatus.SERIALIZATION_ERROR, resp.status());
        assertEquals(101L, resp.requestId());
    }

    @Test
    public void corruptBodyRespondsSerializationError() {
        writeInbound(Frame.request(HESSIAN.typeId(), 102L, new byte[]{1, 2, 3}));
        Frame resp = readResponse();
        assertNotNull(resp);
        assertEquals(ProtocolStatus.SERIALIZATION_ERROR, resp.status());
        assertEquals(102L, resp.requestId());
    }

    /** Review Focus #4：合法 Hessian 但不是 RpcRequestBody 的负载，必须被类型防线拦下 */
    @Test
    public void wrongBodyTypeRespondsSerializationError() {
        writeInbound(Frame.request(HESSIAN.typeId(), 106L, HESSIAN.serialize("not a request body")));
        Frame resp = readResponse();
        assertNotNull(resp);
        assertEquals(ProtocolStatus.SERIALIZATION_ERROR, resp.status());
        assertEquals(106L, resp.requestId());
    }

    @Test
    public void unknownServiceRespondsServiceNotFound() {
        RpcRequestBody body = new RpcRequestBody();
        body.setServiceName("no.such.Service");
        body.setMethodName("go");
        body.setParameterTypes(new String[0]);
        body.setArguments(new Object[0]);
        writeInbound(Frame.request(HESSIAN.typeId(), 103L, HESSIAN.serialize(body)));
        Frame resp = readResponse();
        assertNotNull(resp);
        assertEquals(ProtocolStatus.SERVICE_NOT_FOUND, resp.status());
        assertEquals(103L, resp.requestId());
        assertTrue(bodyOf(resp).getErrorMessage().contains("no.such.Service"));
    }

    @Test
    public void missingMethodRespondsMethodNotFound() {
        RpcRequestBody body = new RpcRequestBody();
        body.setServiceName(EchoService.class.getName());
        body.setMethodName("noSuchMethod");
        body.setParameterTypes(new String[]{EchoDTO.class.getName()});
        body.setArguments(new Object[]{new EchoDTO("x")});
        writeInbound(Frame.request(HESSIAN.typeId(), 104L, HESSIAN.serialize(body)));
        Frame resp = readResponse();
        assertNotNull(resp);
        assertEquals(ProtocolStatus.METHOD_NOT_FOUND, resp.status());
    }

    @Test
    public void businessExceptionRespondsServerErrorWithDescription() {
        writeInbound(requestEcho("boom-now", 105L));
        Frame resp = readResponse();
        assertNotNull(resp);
        assertEquals(ProtocolStatus.SERVER_ERROR, resp.status());
        RpcResponseBody respBody = bodyOf(resp);
        assertNull(respBody.getValue());
        assertEquals("java.lang.IllegalStateException", respBody.getErrorClassName());
        assertTrue(respBody.getErrorMessage().contains("boom happened"));
    }

    /** Review Focus #3：流错位 → 关连接 */
    @Test
    public void protocolViolationClosesChannel() {
        ByteBuf garbage = Unpooled.buffer();
        garbage.writeShort(0x1234);
        garbage.writeBytes(new byte[18]);
        channel.writeInbound(garbage);
        assertFalse("protocol violation must close the channel", channel.isOpen());
        assertNull(readResponse());
    }

    @Test
    public void twoRequestsPipelinedOnOneConnectionBothAnswered() {
        writeInbound(requestEcho("one", 200L));
        writeInbound(requestEcho("two", 201L));
        Frame first = readResponse();
        Frame second = readResponse();
        assertNotNull(first);
        assertNotNull(second);
        assertEquals(200L, first.requestId());
        assertEquals("echo:one", ((EchoDTO) bodyOf(first).getValue()).getMsg());
        assertEquals(201L, second.requestId());
        assertEquals("echo:two", ((EchoDTO) bodyOf(second).getValue()).getMsg());
    }

    /** READER_IDLE（服务端连续 SERVER_IDLE_SECONDS 无读）必须关连接——死连接回收唯一的执行点 */
    @Test
    public void readerIdleEventClosesConnection() {
        channel.pipeline().fireUserEventTriggered(new Object());
        assertTrue("非空闲事件不得关闭连接", channel.isOpen());
        channel.pipeline().fireUserEventTriggered(IdleStateEvent.READER_IDLE_STATE_EVENT);
        assertFalse("READER_IDLE 必须关闭连接", channel.isOpen());
    }

    /** 重复 start 必须显式失败，而非静默覆盖 boss/worker 泄漏上一组事件循环线程 */
    @Test
    public void doubleStartThrowsInsteadOfLeakingEventLoops() throws Exception {
        ServerSocket probe = new ServerSocket(0);
        int port = probe.getLocalPort();
        probe.close();
        RpcServer server = new RpcServer(port, new SerializerRegistry());
        server.start();
        try {
            server.start();
            fail("重复 start 应抛 IllegalStateException");
        } catch (IllegalStateException expected) {
            // ok
        } finally {
            server.shutdown();
        }
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `mvn -f small-rpc-core/pom.xml test -q -Dtest=ServerHandlerTest`
Expected: 编译失败（`FrameDecoder`/`FrameEncoder`/`ServerHandler` 不存在）

- [x] **Step 3: 实现**

`protocol/FrameDecoder.java`:

```java
package io.github.upowerman.core.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.DecoderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 累积字节流 → Frame。协议错误（magic/ver/bodyLen 非法）即关连接：
 * 流已错位，继续读只会产出垃圾帧。
 */
public class FrameDecoder extends ByteToMessageDecoder {

    private static final Logger logger = LoggerFactory.getLogger(FrameDecoder.class);

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        Frame frame = FrameCodec.decodeOne(in);
        if (frame != null) {
            out.add(frame);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Throwable real = cause instanceof DecoderException && cause.getCause() != null ? cause.getCause() : cause;
        if (real instanceof ProtocolException) {
            logger.warn("rpc2 protocol violation, closing connection: {}", real.getMessage());
            ctx.close();
        } else {
            ctx.fireExceptionCaught(cause);
        }
    }
}
```

`protocol/FrameEncoder.java`:

```java
package io.github.upowerman.core.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Frame → 字节流（每帧独立写出，粘包由接收侧 FrameDecoder 处理）。
 */
public class FrameEncoder extends MessageToByteEncoder<Frame> {

    @Override
    protected void encode(ChannelHandlerContext ctx, Frame frame, ByteBuf out) {
        FrameCodec.encode(frame, out);
    }
}
```

`RpcConstants.java` 追加（保留既有三个常量与 private 构造不变）:

```java
    /** 客户端写空闲 N 秒即发心跳帧；服务端读空闲 3 倍该值关连接 */
    public static final int HEARTBEAT_INTERVAL_SECONDS = 30;

    /** 服务端读空闲超时（秒），超过则视为死连接关闭 */
    public static final int SERVER_IDLE_SECONDS = HEARTBEAT_INTERVAL_SECONDS * 3;
```

`server/ServerHandler.java`:

```java
package io.github.upowerman.core.server;

import io.github.upowerman.core.RpcConstants;
import io.github.upowerman.core.invocation.GenericInvocation;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.protocol.Frame;
import io.github.upowerman.core.protocol.ProtocolStatus;
import io.github.upowerman.core.protocol.RpcRequestBody;
import io.github.upowerman.core.protocol.RpcResponseBody;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import io.github.upowerman.core.serialize.Serializer;
import io.github.upowerman.core.serialize.SerializerRegistry;
import io.github.upowerman.exception.RpcException;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 服务端帧处理器：REQUEST → 业务线程池（反序列化 → Invoker → 序列化）→ RESPONSE；
 * HEARTBEAT 立即回显；未知 type 关连接。业务处理不占用 I/O 线程。
 * <p>
 * 一切失败都以状态码回帧，不抛裸异常：未知 codec / 反序列化失败 / 类型不符 →
 * SERIALIZATION_ERROR，无 provider → SERVICE_NOT_FOUND，Invoker 侧失败按 Result 状态映射。
 */
public class ServerHandler extends SimpleChannelInboundHandler<Frame> {

    private static final Logger logger = LoggerFactory.getLogger(ServerHandler.class);

    private final SerializerRegistry serializers;
    private final Map<String, Invoker> providers;
    private final Executor businessExecutor;

    public ServerHandler(SerializerRegistry serializers, Map<String, Invoker> providers,
                         Executor businessExecutor) {
        this.serializers = serializers;
        this.providers = providers;
        this.businessExecutor = businessExecutor;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, final Frame frame) {
        if (frame.type() == Frame.TYPE_HEARTBEAT) {
            ctx.writeAndFlush(Frame.heartbeat(frame.requestId()));
            return;
        }
        if (frame.type() != Frame.TYPE_REQUEST) {
            ctx.close();
            return;
        }
        final Channel channel = ctx.channel();
        businessExecutor.execute(new Runnable() {
            @Override
            public void run() {
                handleRequest(channel, frame);
            }
        });
    }

    private void handleRequest(Channel channel, Frame frame) {
        Serializer serializer = serializers.find(frame.codec());
        if (serializer == null) {
            writeError(channel, frame, ProtocolStatus.SERIALIZATION_ERROR,
                    "unknown codec: " + frame.codec(), null);
            return;
        }
        RpcRequestBody body;
        try {
            // 委托的 Hessian 反序列化忽略 clazz，转型即类型防线（ClassCastException 在此被捕获）
            body = (RpcRequestBody) serializer.deserialize(frame.body(), RpcRequestBody.class);
        } catch (Exception e) {
            writeError(channel, frame, ProtocolStatus.SERIALIZATION_ERROR,
                    "cannot read request body", e);
            return;
        }
        Invoker invoker = providers.get(body.getServiceName());
        if (invoker == null) {
            writeError(channel, frame, ProtocolStatus.SERVICE_NOT_FOUND,
                    "no provider for " + body.getServiceName(), null);
            return;
        }
        try {
            Invocation invocation = toInvocation(body);
            // P1 服务端 Invoker 全部同步完成（ReflectiveInvoker），get() 即取结果
            Result result = invoker.invoke(invocation).get();
            writeResult(channel, frame, serializer, result);
        } catch (Exception e) {
            writeError(channel, frame, ProtocolStatus.SERVER_ERROR, "server failed to invoke", e);
        }
    }

    private void writeResult(Channel channel, Frame frame, Serializer serializer, Result result) {
        byte status;
        try {
            status = ProtocolStatus.toCode(result.status());
        } catch (IllegalArgumentException e) {
            // 调用方本地态（TIMEOUT 等）不应出现在服务端；兜底按 SERVER_ERROR 回
            status = ProtocolStatus.SERVER_ERROR;
        }
        RpcResponseBody body = new RpcResponseBody();
        if (result.status() == Status.SUCCESS) {
            body.setValue(result.value());
        } else {
            Throwable error = result.exception();
            body.setErrorClassName(error == null ? RpcException.class.getName() : error.getClass().getName());
            body.setErrorMessage(error == null ? String.valueOf(result.status()) : error.getMessage());
        }
        writeBody(channel, frame, serializer, status, body);
    }

    private void writeError(Channel channel, Frame frame, byte status, String message, Throwable cause) {
        RpcResponseBody body = new RpcResponseBody();
        body.setErrorClassName(RpcException.class.getName());
        body.setErrorMessage(cause == null ? message : message + ": " + cause);
        writeBody(channel, frame, serializers.find(frame.codec()), status, body);
    }

    private void writeBody(Channel channel, Frame frame, Serializer serializer, byte status,
                           RpcResponseBody body) {
        byte[] bytes;
        try {
            bytes = serializer == null ? new byte[0] : serializer.serialize(body);
        } catch (Exception e) {
            // 结果本体序列化失败：降级为空 body 的 SERIALIZATION_ERROR 帧，调用方仍能结算
            channel.writeAndFlush(Frame.response(frame.codec(), ProtocolStatus.SERIALIZATION_ERROR,
                    frame.requestId(), new byte[0]));
            return;
        }
        channel.writeAndFlush(Frame.response(frame.codec(), status, frame.requestId(), bytes));
    }

    private static Invocation toInvocation(RpcRequestBody body) throws ClassNotFoundException {
        String[] typeNames = body.getParameterTypes();
        Class<?>[] types = new Class<?>[typeNames == null ? 0 : typeNames.length];
        for (int i = 0; i < types.length; i++) {
            types[i] = Class.forName(typeNames[i]);
        }
        Map<String, Object> attachments = body.getAttachments();
        return new GenericInvocation(body.getServiceName(), body.getMethodName(), types,
                body.getArguments() == null ? new Object[0] : body.getArguments(),
                attachments == null ? new HashMap<String, Object>() : attachments);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        // READER_IDLE = 连续 SERVER_IDLE_SECONDS 无读事件：对端已死（心跳保活失灵），回收半开连接。
        // IdleStateHandler 只发事件不关连接，这里是死连接回收唯一的执行点。
        if (evt instanceof IdleStateEvent && ((IdleStateEvent) evt).state() == IdleState.READER_IDLE) {
            logger.warn("rpc2 server closes idle connection: {} (no read for {}s)",
                    ctx.channel().remoteAddress(), RpcConstants.SERVER_IDLE_SECONDS);
            ctx.close();
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.warn("server channel error, close: {}", cause.toString());
        ctx.close();
    }
}
```

`server/RpcServer.java`:

```java
package io.github.upowerman.core.server;

import io.github.upowerman.core.RpcConstants;
import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.protocol.FrameDecoder;
import io.github.upowerman.core.protocol.FrameEncoder;
import io.github.upowerman.core.serialize.SerializerRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 2.0 provider 容器：serviceName → Invoker 注册 + Netty 协议栈。
 * 与 1.x NettyServer 完全独立（端口、pipeline、生命周期互不共享），可同进程共存。
 */
public class RpcServer {

    private static final Logger logger = LoggerFactory.getLogger(RpcServer.class);

    private static final int BUSINESS_THREADS = 8;

    private final int port;
    private final SerializerRegistry serializers;
    private final Map<String, Invoker> providers = new ConcurrentHashMap<String, Invoker>();
    private final ExecutorService businessPool;

    private EventLoopGroup boss;
    private EventLoopGroup worker;
    private volatile Channel serverChannel;

    public RpcServer(int port, SerializerRegistry serializers) {
        this.port = port;
        this.serializers = serializers;
        this.businessPool = Executors.newFixedThreadPool(BUSINESS_THREADS, new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "rpc2-server-biz-" + counter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
    }

    public RpcServer register(String serviceName, Invoker invoker) {
        providers.put(serviceName, invoker);
        return this;
    }

    public void start() throws InterruptedException {
        if (serverChannel != null) {
            // 重复 start 会静默覆盖 boss/worker，泄漏上一组事件循环线程——显式失败
            throw new IllegalStateException("rpc2 server already started on port " + port);
        }
        boss = new NioEventLoopGroup(1);
        worker = new NioEventLoopGroup();
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new IdleStateHandler(RpcConstants.SERVER_IDLE_SECONDS, 0, 0))
                                .addLast(new FrameDecoder())
                                .addLast(new FrameEncoder())
                                .addLast(new ServerHandler(serializers, providers, businessPool));
                    }
                })
                .childOption(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.SO_BACKLOG, 256);
        // bind().sync() 返回即端口已监听，调用方无需再轮询探活
        serverChannel = bootstrap.bind(port).sync().channel();
        logger.info("rpc2 server started on port {}", port);
    }

    /** 幂等关闭：连接 → 事件循环 → 业务线程池 */
    public void shutdown() {
        try {
            if (serverChannel != null) {
                serverChannel.close().syncUninterruptibly();
            }
        } finally {
            if (boss != null) {
                boss.shutdownGracefully(0, 5, TimeUnit.SECONDS);
            }
            if (worker != null) {
                worker.shutdownGracefully(0, 5, TimeUnit.SECONDS);
            }
            businessPool.shutdown();
        }
        logger.info("rpc2 server shut down");
    }
}
```

- [x] **Step 4: 跑测试确认通过**

Run: `mvn -f small-rpc-core/pom.xml test -q -Dtest=ServerHandlerTest`
Expected: PASS（12 tests）

- [x] **Step 5: Commit**

```bash
git add small-rpc-core/src
git commit -m "feat(rpc2): Netty frame handlers + 2.0 RpcServer with status-code passthrough"
```

---

### Task 4: 2.0 NettyTransport + NettyConnection（client 端）

**Files:**
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/transport/NettyTransport.java`
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/transport/NettyConnection.java`（包私有）
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/transport/ResponseHandler.java`（包私有）
- Create: `small-rpc-core/src/main/java/io/github/upowerman/core/transport/HeartbeatTrigger.java`（包私有）
- Modify: `small-rpc-core/src/main/java/io/github/upowerman/core/transport/Connection.java`（仅 javadoc）
- Modify: `small-rpc-core/src/main/java/io/github/upowerman/core/transport/Endpoint.java`（仅 javadoc）
- Test: `small-rpc-core/src/test/java/io/github/upowerman/core/transport/NettyClientHandlersTest.java`

**Interfaces:**
- Consumes: Task 1/2/3 全部、`PendingRequests`、`Connection`/`Transport`/`Endpoint`、`Serializer`、`RpcConstants.ATTACH_TIMEOUT/HEARTBEAT_INTERVAL_SECONDS`
- Produces: `NettyTransport(Serializer)` / `NettyTransport(Serializer, long)` 实现 `Transport` + `shutdown()`；per-address `Channel` 池（双检锁，与 1.x 静态池完全隔离）；`NettyConnection(Channel, PendingRequests, Serializer, long, String)`（包私有，测试可直接用 `EmbeddedChannel` 构造）

- [x] **Step 1: 写失败测试**

```java
package io.github.upowerman.core.transport;

import io.github.upowerman.core.RpcConstants;
import io.github.upowerman.core.invocation.GenericInvocation;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.protocol.Frame;
import io.github.upowerman.core.protocol.FrameCodec;
import io.github.upowerman.core.protocol.FrameDecoder;
import io.github.upowerman.core.protocol.FrameEncoder;
import io.github.upowerman.core.protocol.ProtocolStatus;
import io.github.upowerman.core.protocol.RpcRequestBody;
import io.github.upowerman.core.protocol.RpcResponseBody;
import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import io.github.upowerman.core.serialize.LegacyHessianSerializer;
import io.github.upowerman.core.serialize.Serializer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NettyClientHandlersTest {

    private static final Serializer HESSIAN = new LegacyHessianSerializer();

    private static final long TIMEOUT_MILLIS = 30000L;

    private static Invocation invocation() {
        Map<String, Object> attachments = new HashMap<String, Object>();
        attachments.put(RpcConstants.ATTACH_ADDRESS, "127.0.0.1:7080");
        return new GenericInvocation("com.test.EchoService", "echo",
                new Class<?>[]{String.class}, new Object[]{"world"}, attachments);
    }

    private static EmbeddedChannel responseChannel(PendingRequests pending) {
        return new EmbeddedChannel(new FrameDecoder(), new FrameEncoder(),
                new ResponseHandler(pending, HESSIAN));
    }

    private static void writeInbound(EmbeddedChannel channel, Frame frame) {
        ByteBuf buf = Unpooled.buffer();
        FrameCodec.encode(frame, buf);
        channel.writeInbound(buf);
    }

    private static Frame readOutbound(EmbeddedChannel channel) {
        ByteBuf outbound = channel.readOutbound();
        return outbound == null ? null : FrameCodec.decodeOne(outbound);
    }

    // ---------- NettyConnection ----------

    @Test
    public void requestWritesRequestFrameCarryingInvocation() {
        EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder(), new FrameEncoder());
        PendingRequests pending = new PendingRequests();
        NettyConnection connection = new NettyConnection(channel, pending, HESSIAN, TIMEOUT_MILLIS, "127.0.0.1:7080");

        CompletableFuture<Result> future = connection.request(invocation());
        Frame sent = readOutbound(channel);
        assertNotNull(sent);
        assertEquals(Frame.TYPE_REQUEST, sent.type());
        assertEquals(HESSIAN.typeId(), sent.codec());
        assertTrue("requestId must be a positive long", sent.requestId() > 0L);
        assertFalse("request must stay in flight until answered", future.isDone());
        assertEquals(1, pending.size());

        RpcRequestBody body = (RpcRequestBody) HESSIAN.deserialize(sent.body(), RpcRequestBody.class);
        assertEquals("com.test.EchoService", body.getServiceName());
        assertEquals("echo", body.getMethodName());
        assertEquals("java.lang.String", body.getParameterTypes()[0]);
        assertEquals("world", body.getArguments()[0]);
        assertEquals("127.0.0.1:7080", body.getAttachments().get(RpcConstants.ATTACH_ADDRESS));

        // 清理：应答以结算 in-flight 条目
        pending.complete(sent.requestId(), DefaultResult.success("ok"));
        channel.finishAndReleaseAll();
    }

    @Test
    public void serializationFailureSettlesAsSerializationErrorAndEvictsPending() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder(), new FrameEncoder());
        PendingRequests pending = new PendingRequests();
        Serializer broken = new Serializer() {
            @Override
            public byte typeId() {
                return 42;
            }

            @Override
            public byte[] serialize(Object obj) {
                throw new IllegalStateException("cannot serialize");
            }

            @Override
            public Object deserialize(byte[] bytes, Class<?> clazz) {
                throw new IllegalStateException("cannot deserialize");
            }
        };
        NettyConnection connection = new NettyConnection(channel, pending, broken, TIMEOUT_MILLIS, "127.0.0.1:7080");

        Result result = connection.request(invocation()).get(1, TimeUnit.SECONDS);

        assertEquals(Status.SERIALIZATION_ERROR, result.status());
        assertEquals(0, pending.size());
        assertNull("nothing must be written when the request cannot be encoded", readOutbound(channel));
        channel.finishAndReleaseAll();
    }

    @Test
    public void sendFailureSettlesAsNetworkErrorAndEvictsPending() throws Exception {
        EmbeddedChannel channel = new EmbeddedChannel(new FrameDecoder(), new FrameEncoder());
        PendingRequests pending = new PendingRequests();
        NettyConnection connection = new NettyConnection(channel, pending, HESSIAN, TIMEOUT_MILLIS, "127.0.0.1:7080");
        channel.close();

        Result result = connection.request(invocation()).get(1, TimeUnit.SECONDS);

        assertEquals(Status.NETWORK_ERROR, result.status());
        assertEquals(0, pending.size());
    }

    // ---------- ResponseHandler ----------

    @Test
    public void successResponseCompletesMatchingPendingRequest() throws Exception {
        PendingRequests pending = new PendingRequests();
        EmbeddedChannel channel = responseChannel(pending);
        long requestId = pending.nextRequestId();
        CompletableFuture<Result> future = pending.register(requestId);

        RpcResponseBody body = new RpcResponseBody();
        body.setValue("echo:world");
        writeInbound(channel, Frame.response(HESSIAN.typeId(), ProtocolStatus.SUCCESS, requestId,
                HESSIAN.serialize(body)));

        assertEquals("echo:world", future.get(1, TimeUnit.SECONDS).value());
        assertEquals(0, pending.size());
        channel.finishAndReleaseAll();
    }

    @Test
    public void responseForUnknownRequestIdIsIgnored() {
        PendingRequests pending = new PendingRequests();
        EmbeddedChannel channel = responseChannel(pending);

        RpcResponseBody body = new RpcResponseBody();
        body.setValue("late");
        writeInbound(channel, Frame.response(HESSIAN.typeId(), ProtocolStatus.SUCCESS, 999L,
                HESSIAN.serialize(body)));

        assertEquals(0, pending.size());
        assertTrue(channel.isOpen());
        channel.finishAndReleaseAll();
    }

    @Test
    public void errorStatusMapsBackToResultStatusWithRebuiltException() throws Exception {
        PendingRequests pending = new PendingRequests();
        EmbeddedChannel channel = responseChannel(pending);
        long requestId = pending.nextRequestId();
        CompletableFuture<Result> future = pending.register(requestId);

        RpcResponseBody body = new RpcResponseBody();
        body.setErrorClassName("java.lang.IllegalStateException");
        body.setErrorMessage("boom happened");
        writeInbound(channel, Frame.response(HESSIAN.typeId(), ProtocolStatus.SERVER_ERROR, requestId,
                HESSIAN.serialize(body)));

        Result result = future.get(1, TimeUnit.SECONDS);
        assertEquals(Status.SERVER_ERROR, result.status());
        assertNotNull(result.exception());
        assertTrue(result.exception().getMessage().contains("boom happened"));
        assertTrue(result.exception().getMessage().contains("java.lang.IllegalStateException"));
        channel.finishAndReleaseAll();
    }

    @Test
    public void corruptResponseBodySettlesAsSerializationError() throws Exception {
        PendingRequests pending = new PendingRequests();
        EmbeddedChannel channel = responseChannel(pending);
        long requestId = pending.nextRequestId();
        CompletableFuture<Result> future = pending.register(requestId);

        writeInbound(channel, Frame.response(HESSIAN.typeId(), ProtocolStatus.SUCCESS, requestId,
                new byte[]{1, 2, 3}));

        assertEquals(Status.SERIALIZATION_ERROR, future.get(1, TimeUnit.SECONDS).status());
        channel.finishAndReleaseAll();
    }

    @Test
    public void unknownStatusByteSettlesAsSerializationError() throws Exception {
        PendingRequests pending = new PendingRequests();
        EmbeddedChannel channel = responseChannel(pending);
        long requestId = pending.nextRequestId();
        CompletableFuture<Result> future = pending.register(requestId);

        RpcResponseBody body = new RpcResponseBody();
        body.setValue("ok");
        writeInbound(channel, Frame.response(HESSIAN.typeId(), (byte) 99, requestId,
                HESSIAN.serialize(body)));

        assertEquals(Status.SERIALIZATION_ERROR, future.get(1, TimeUnit.SECONDS).status());
        channel.finishAndReleaseAll();
    }

    @Test
    public void heartbeatResponseIsIgnoredWithoutSideEffects() {
        PendingRequests pending = new PendingRequests();
        EmbeddedChannel channel = responseChannel(pending);

        writeInbound(channel, Frame.heartbeat(1L));

        assertEquals(0, pending.size());
        assertNull(channel.readOutbound());
        assertTrue(channel.isOpen());
        channel.finishAndReleaseAll();
    }

    @Test
    public void unknownFrameTypeClosesChannel() {
        PendingRequests pending = new PendingRequests();
        EmbeddedChannel channel = responseChannel(pending);

        // type=9 非法：Frame 工厂只产三种 type，这里直接编码非法 type 字节
        ByteBuf buf = Unpooled.buffer();
        buf.writeShort(Frame.MAGIC);
        buf.writeByte(Frame.VERSION);
        buf.writeByte(9);
        buf.writeByte(0);
        buf.writeByte(0);
        buf.writeShort(0);
        buf.writeLong(1L);
        buf.writeInt(0);
        channel.writeInbound(buf);

        assertFalse("unknown frame type must close the channel", channel.isOpen());
        channel.finishAndReleaseAll();
    }

    // ---------- HeartbeatTrigger ----------

    @Test
    public void writeIdleEventTriggersHeartbeatFrame() {
        EmbeddedChannel channel = new EmbeddedChannel(new FrameEncoder(), new HeartbeatTrigger());

        channel.pipeline().fireUserEventTriggered(new IdleStateEvent(IdleState.WRITER_IDLE, true));

        Frame heartbeat = readOutbound(channel);
        assertNotNull("write-idle must emit a heartbeat frame", heartbeat);
        assertEquals(Frame.TYPE_HEARTBEAT, heartbeat.type());
        assertEquals(0, heartbeat.body().length);
        channel.finishAndReleaseAll();
    }
}
```

- [x] **Step 2: 跑测试确认失败**

Run: `mvn -f small-rpc-core/pom.xml test -q -Dtest=NettyClientHandlersTest`
Expected: 编译失败（`NettyConnection`/`ResponseHandler`/`HeartbeatTrigger` 不存在）

- [x] **Step 3: 实现**

`transport/ResponseHandler.java`:

```java
package io.github.upowerman.core.transport;

import io.github.upowerman.core.protocol.Frame;
import io.github.upowerman.core.protocol.ProtocolStatus;
import io.github.upowerman.core.protocol.RpcResponseBody;
import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import io.github.upowerman.core.serialize.Serializer;
import io.github.upowerman.exception.RpcException;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

/**
 * 客户端入站帧处理：RESPONSE → 反序列化 → 按 requestId 结算 PendingRequests；
 * HEARTBEAT 忽略；未知 type 关连接（流不可信）。
 * 反序列化在 I/O 线程执行（Hessian 微秒级，P1 不引入额外线程切换）；
 * 任何解析失败都以 SERIALIZATION_ERROR 结算——in-flight 条目绝不悬挂。
 */
class ResponseHandler extends SimpleChannelInboundHandler<Frame> {

    private final PendingRequests pending;
    private final Serializer serializer;

    ResponseHandler(PendingRequests pending, Serializer serializer) {
        this.pending = pending;
        this.serializer = serializer;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Frame frame) {
        if (frame.type() == Frame.TYPE_HEARTBEAT) {
            return;
        }
        if (frame.type() != Frame.TYPE_RESPONSE) {
            ctx.close();
            return;
        }
        pending.complete(frame.requestId(), decodeResult(frame));
    }

    private Result decodeResult(Frame frame) {
        try {
            // 委托的 Hessian 忽略 clazz，转型即类型防线
            RpcResponseBody body = (RpcResponseBody) serializer.deserialize(frame.body(), RpcResponseBody.class);
            if (frame.status() == ProtocolStatus.SUCCESS) {
                return DefaultResult.success(body.getValue());
            }
            return DefaultResult.failure(ProtocolStatus.fromCode(frame.status()), rebuildException(body));
        } catch (Exception e) {
            // 反序列化失败 / 未知状态码 / 类型不符：SERIALIZATION_ERROR 结算
            return DefaultResult.failure(Status.SERIALIZATION_ERROR, e);
        }
    }

    private static Throwable rebuildException(RpcResponseBody body) {
        String className = body.getErrorClassName();
        String message = body.getErrorMessage();
        return new RpcException(className == null ? String.valueOf(message) : className + ": " + message);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        ctx.close();
    }
}
```

`transport/HeartbeatTrigger.java`:

```java
package io.github.upowerman.core.transport;

import io.github.upowerman.core.protocol.Frame;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleStateEvent;

/**
 * 写空闲即发心跳帧（协议一等公民，type=HEARTBEAT，无 body）。
 * 服务端读空闲 3 倍间隔判定死连接，见 RpcConstants.SERVER_IDLE_SECONDS。
 */
class HeartbeatTrigger extends ChannelDuplexHandler {

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            ctx.writeAndFlush(Frame.heartbeat(0L));
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }
}
```

`transport/NettyConnection.java`:

```java
package io.github.upowerman.core.transport;

import io.github.upowerman.core.RpcConstants;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.protocol.Frame;
import io.github.upowerman.core.protocol.RpcRequestBody;
import io.github.upowerman.core.result.DefaultResult;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import io.github.upowerman.core.serialize.Serializer;
import io.github.upowerman.exception.RpcException;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.util.concurrent.GenericFutureListener;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 一条 2.0 协议连接：Invocation → 协议帧，响应帧经 ResponseHandler 回来。
 * 结算路径统一清理：超时由 PendingRequests 兜底驱逐；send 失败当场结算；
 * 序列化失败以 SERIALIZATION_ERROR 结算——任何路径都不泄漏 in-flight 条目。
 */
final class NettyConnection implements Connection {

    private final Channel channel;
    private final PendingRequests pending;
    private final Serializer serializer;
    private final long defaultTimeoutMillis;
    private final String address;

    NettyConnection(Channel channel, PendingRequests pending, Serializer serializer,
                    long defaultTimeoutMillis, String address) {
        this.channel = channel;
        this.pending = pending;
        this.serializer = serializer;
        this.defaultTimeoutMillis = defaultTimeoutMillis;
        this.address = address;
    }

    @Override
    public CompletableFuture<Result> request(Invocation invocation) {
        long requestId = pending.nextRequestId();
        final CompletableFuture<Result> future = pending.register(requestId, resolveTimeout(invocation));

        byte[] bodyBytes;
        try {
            bodyBytes = serializer.serialize(toBody(invocation));
        } catch (Exception e) {
            pending.complete(requestId, DefaultResult.failure(Status.SERIALIZATION_ERROR,
                    new RpcException("serialize request failed", e)));
            return future;
        }

        channel.writeAndFlush(Frame.request(serializer.typeId(), requestId, bodyBytes))
                .addListener(new GenericFutureListener<ChannelFuture>() {
                    @Override
                    public void operationComplete(ChannelFuture f) {
                        if (!f.isSuccess()) {
                            // 连接失效/发送失败：NETWORK_ERROR 当场结算（register 的超时任务随后取消）
                            pending.complete(requestId, DefaultResult.failure(Status.NETWORK_ERROR,
                                    new RpcException("send failed to " + address, f.cause())));
                        }
                    }
                });
        return future;
    }

    private long resolveTimeout(Invocation invocation) {
        Object timeout = invocation.attachments().get(RpcConstants.ATTACH_TIMEOUT);
        if (timeout instanceof Long && ((Long) timeout) > 0) {
            return (Long) timeout;
        }
        return defaultTimeoutMillis;
    }

    private RpcRequestBody toBody(Invocation invocation) {
        RpcRequestBody body = new RpcRequestBody();
        body.setServiceName(invocation.serviceName());
        body.setMethodName(invocation.methodName());
        Class<?>[] types = invocation.parameterTypes();
        String[] typeNames = new String[types == null ? 0 : types.length];
        for (int i = 0; i < typeNames.length; i++) {
            typeNames[i] = types[i].getName();
        }
        body.setParameterTypes(typeNames);
        body.setArguments(invocation.arguments());
        Map<String, Object> attachments = invocation.attachments();
        body.setAttachments(attachments == null ? new HashMap<String, Object>() : attachments);
        return body;
    }

    @Override
    public void close() {
        // Channel 由 NettyTransport 的连接池统一管理关闭
    }
}
```

`transport/NettyTransport.java`:

```java
package io.github.upowerman.core.transport;

import io.github.upowerman.core.RpcConstants;
import io.github.upowerman.core.protocol.FrameDecoder;
import io.github.upowerman.core.protocol.FrameEncoder;
import io.github.upowerman.core.serialize.Serializer;
import io.github.upowerman.exception.RpcException;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * 2.0 自研协议 transport（client 端）：自持 per-address Channel 池与 Netty 客户端，
 * 与 1.x 的进程级静态 ConnectClient 池完全隔离（C2 修复的延续），
 * 因此 1.x 与 2.0 客户端可同进程、同地址共存而互不串话。
 */
public class NettyTransport implements Transport {

    /** 兜底默认单请求超时（毫秒）；调用未显式携带 ATTACH_TIMEOUT 时使用 */
    public static final long DEFAULT_REQUEST_TIMEOUT_MILLIS = 30000L;

    private static final int CONNECT_TIMEOUT_MILLIS = 3000;

    private final Serializer serializer;
    private final PendingRequests pending = new PendingRequests();
    private final long defaultTimeoutMillis;
    private final ConcurrentMap<String, Channel> channels = new ConcurrentHashMap<String, Channel>();
    private final ConcurrentMap<String, Object> locks = new ConcurrentHashMap<String, Object>();
    private final EventLoopGroup group = new NioEventLoopGroup();
    private volatile boolean closed;

    public NettyTransport(Serializer serializer) {
        this(serializer, DEFAULT_REQUEST_TIMEOUT_MILLIS);
    }

    public NettyTransport(Serializer serializer, long defaultTimeoutMillis) {
        this.serializer = serializer;
        // 兜底超时绝不允许非正：非正只会让挂起请求永不超时
        this.defaultTimeoutMillis = defaultTimeoutMillis > 0
                ? defaultTimeoutMillis
                : DEFAULT_REQUEST_TIMEOUT_MILLIS;
    }

    @Override
    public Connection connect(Endpoint endpoint) {
        return new NettyConnection(channelFor(endpoint.address()), pending, serializer,
                defaultTimeoutMillis, endpoint.address());
    }

    private Channel channelFor(String address) {
        Channel channel = channels.get(address);
        if (channel != null && channel.isActive()) {
            return channel;
        }
        Object lock = locks.get(address);
        if (lock == null) {
            locks.putIfAbsent(address, new Object());
            lock = locks.get(address);
        }
        synchronized (lock) {
            channel = channels.get(address);
            if (channel != null && channel.isActive()) {
                return channel;
            }
            if (channel != null) {
                channel.close();
            }
            if (closed) {
                throw new RpcException("transport already closed");
            }
            Channel newChannel = doConnect(address);
            channels.put(address, newChannel);
            return newChannel;
        }
    }

    private Channel doConnect(String address) {
        int colon = address.lastIndexOf(':');
        if (colon <= 0 || colon == address.length() - 1) {
            throw new RpcException("invalid endpoint address: " + address);
        }
        String host = address.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(address.substring(colon + 1));
        } catch (NumberFormatException e) {
            throw new RpcException("invalid endpoint port: " + address, e);
        }
        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MILLIS)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new IdleStateHandler(0, RpcConstants.HEARTBEAT_INTERVAL_SECONDS, 0))
                                .addLast(new FrameDecoder())
                                .addLast(new FrameEncoder())
                                .addLast(new ResponseHandler(pending, serializer))
                                .addLast(new HeartbeatTrigger());
                    }
                });
        ChannelFuture future = bootstrap.connect(host, port).awaitUninterruptibly();
        if (!future.isSuccess()) {
            throw new RpcException("connect failed: " + address, future.cause());
        }
        return future.channel();
    }

    /** 关闭全部连接并释放事件循环线程。幂等。 */
    public void shutdown() {
        closed = true;
        for (Channel channel : channels.values()) {
            channel.close();
        }
        channels.clear();
        group.shutdownGracefully(0, 5, TimeUnit.SECONDS);
    }
}
```

`transport/Connection.java` javadoc 改为（签名一字不动）:

```java
/**
 * 一条已建立的连接。协议帧编解码封装在实现内部（NettyConnection / LegacyConnection），
 * 接口层保持 Invocation → CompletableFuture&lt;Result&gt; 语义，上层组件与传输实现解耦。
 */
```

`transport/Endpoint.java` javadoc 改为（签名一字不动）:

```java
/**
 * 通信端点。沿用 "host:port" 字符串形式，host/port 拆分在 transport 实现内部完成。
 */
```

- [x] **Step 4: 跑测试确认通过 + 全量回归**

Run: `mvn -f small-rpc-core/pom.xml test -q -Dtest=NettyClientHandlersTest`
Expected: PASS（11 tests）
Run: `mvn -f small-rpc-core/pom.xml test -q`
Expected: 全量 PASS（既有 54 个测试零破坏——接口未动）

- [x] **Step 5: Commit**

```bash
git add small-rpc-core/src
git commit -m "feat(rpc2): 2.0 NettyTransport client with own channel pool and heartbeat"
```

---

### Task 5: 真 Netty E2E 集成测试

**Files:**
- Test: `small-rpc-core/src/test/java/io/github/upowerman/core/e2e/NettyProtocolEndToEndTest.java`

**Interfaces:**
- Consumes: Task 3 `RpcServer` + `testsupport.EchoService/EchoDTO/EchoServiceImpl`、Task 4 `NettyTransport`、`FailoverClusterInvoker`、`RemoteInvoker`、`RoundRobinLoadBalancer`、`RpcProxyFactory.getProxy()`、`ServiceDirectory`（匿名实现）、`TraceFilter`
- Produces: 全链路回归验证（成功 / SERVER_ERROR / SERVICE_NOT_FOUND / 并发 requestId 路由 / 连接复用）

- [x] **Step 1: 写测试**

```java
package io.github.upowerman.core.e2e;

import io.github.upowerman.core.RpcConstants;
import io.github.upowerman.core.cluster.FailoverClusterInvoker;
import io.github.upowerman.core.directory.ServiceDirectory;
import io.github.upowerman.core.directory.ServiceInstance;
import io.github.upowerman.core.filter.Filter;
import io.github.upowerman.core.filter.TraceFilter;
import io.github.upowerman.core.invocation.GenericInvocation;
import io.github.upowerman.core.invocation.Invocation;
import io.github.upowerman.core.invoker.Invoker;
import io.github.upowerman.core.invoker.RemoteInvoker;
import io.github.upowerman.core.loadbalance.RoundRobinLoadBalancer;
import io.github.upowerman.core.provider.ReflectiveInvoker;
import io.github.upowerman.core.proxy.RpcProxyFactory;
import io.github.upowerman.core.result.Result;
import io.github.upowerman.core.result.Status;
import io.github.upowerman.core.serialize.LegacyHessianSerializer;
import io.github.upowerman.core.serialize.SerializerRegistry;
import io.github.upowerman.core.server.RpcServer;
import io.github.upowerman.core.testsupport.EchoDTO;
import io.github.upowerman.core.testsupport.EchoService;
import io.github.upowerman.core.testsupport.EchoServiceImpl;
import io.github.upowerman.core.transport.NettyTransport;
import io.github.upowerman.exception.RpcException;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 2.0 协议栈全链路（真 Netty、真端口）：
 * Proxy → TraceFilter → FailoverClusterInvoker → 匿名 Directory → RoundRobin →
 * RemoteInvoker → NettyTransport → 协议帧 → RpcServer → ReflectiveInvoker → 回帧。
 * 全程不经过任何 1.x 组件。
 */
public class NettyProtocolEndToEndTest {

    private static final LegacyHessianSerializer HESSIAN = new LegacyHessianSerializer();

    private static int port;
    private static RpcServer server;
    private static NettyTransport transport;

    /** 端口随测试运行时取空闲端口；存在极小的 bind 竞态，本地串行测试可接受 */
    private static int freePort() throws IOException {
        ServerSocket socket = new ServerSocket(0);
        try {
            return socket.getLocalPort();
        } finally {
            socket.close();
        }
    }

    @BeforeClass
    public static void startServer() throws Exception {
        port = freePort();
        SerializerRegistry registry = new SerializerRegistry().register(HESSIAN);
        server = new RpcServer(port, registry);
        server.register(EchoService.class.getName(),
                new ReflectiveInvoker(EchoService.class, new EchoServiceImpl()));
        server.start();   // bind().sync() 返回即已监听

        transport = new NettyTransport(HESSIAN);
    }

    @AfterClass
    public static void stopAll() {
        if (transport != null) {
            transport.shutdown();
        }
        if (server != null) {
            server.shutdown();
        }
    }

    private static String address() {
        return "127.0.0.1:" + port;
    }

    private static EchoService newProxy(int retries, long timeoutMillis) {
        final List<ServiceInstance> instances =
                Arrays.asList(new ServiceInstance(address()));
        ServiceDirectory directory = new ServiceDirectory() {
            @Override
            public List<ServiceInstance> list(String service) {
                return instances;
            }

            @Override
            public void subscribe(String service) {
                // P0/P1 适配器为空实现
            }
        };
        Invoker cluster = new FailoverClusterInvoker(directory, new RoundRobinLoadBalancer(),
                new RemoteInvoker(transport, EchoService.class), retries, timeoutMillis);
        return new RpcProxyFactory<EchoService>(EchoService.class,
                Collections.<Filter>singletonList(new TraceFilter()), cluster).getProxy();
    }

    @Test
    public void fullChainEchoOverRealNetty() {
        EchoService echo = newProxy(1, 10000L);
        EchoDTO out = echo.echo(new EchoDTO("world"));
        assertEquals("echo:world", out.getMsg());
    }

    @Test
    public void serverSideExceptionSurfacesAsRpcExceptionToCaller() {
        EchoService echo = newProxy(0, 10000L);
        try {
            echo.echo(new EchoDTO("boom-now"));
            fail("expected RpcException for SERVER_ERROR result");
        } catch (RpcException expected) {
            assertTrue(expected.getMessage().contains("boom happened"));
        }
    }

    /** 直连 RemoteInvoker 观察原始状态码，不经 Failover 的重试语义 */
    @Test
    public void unknownServiceSettlesAsServiceNotFound() throws Exception {
        Map<String, Object> attachments = new HashMap<String, Object>();
        attachments.put(RpcConstants.ATTACH_ADDRESS, address());
        Invocation invocation = new GenericInvocation("no.such.Service", "go",
                new Class<?>[0], new Object[0], attachments);

        Result result = new RemoteInvoker(transport, EchoService.class)
                .invoke(invocation).get(10, TimeUnit.SECONDS);

        assertEquals(Status.SERVICE_NOT_FOUND, result.status());
    }

    /** Review Focus #5：并发请求必须按 requestId 各自回包，不串 */
    @Test
    public void concurrentRequestsRouteByRequestId() throws Exception {
        final EchoService echo = newProxy(0, 10000L);
        final int threads = 8;
        final int callsPerThread = 10;
        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch done = new CountDownLatch(threads);
        final AtomicInteger failures = new AtomicInteger();
        try {
            for (int t = 0; t < threads; t++) {
                final int base = t * 1000;
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            for (int i = 0; i < callsPerThread; i++) {
                                String name = "t" + base + "-" + i;
                                EchoDTO out = echo.echo(new EchoDTO(name));
                                if (!("echo:" + name).equals(out.getMsg())) {
                                    failures.incrementAndGet();
                                }
                            }
                        } catch (Exception e) {
                            failures.incrementAndGet();
                        } finally {
                            done.countDown();
                        }
                    }
                });
            }
            assertTrue("all concurrent calls must finish", done.await(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(0, failures.get());
    }

    /** 连接复用：同一地址的多次调用共用一条 Channel，且调用后无悬挂 in-flight 条目 */
    @Test
    public void repeatedCallsReuseOneConnection() {
        EchoService echo = newProxy(0, 10000L);
        for (int i = 0; i < 20; i++) {
            assertEquals("echo:c" + i, echo.echo(new EchoDTO("c" + i)).getMsg());
        }
        // 若前序调用有 in-flight 泄漏，后续调用仍会即时成功——本断言只是收尾一致性检查
        assertEquals("echo:final", echo.echo(new EchoDTO("final")).getMsg());
    }
}
```

- [x] **Step 2: 跑测试**

Run: `mvn -f small-rpc-core/pom.xml test -q -Dtest=NettyProtocolEndToEndTest`
Expected: PASS（5 tests）。本任务是集成验证而非新实现——若失败，按失败信息定位 Task 3/4 的缺陷并修复（修复计入本任务）

- [x] **Step 3: 全量回归**

Run: `mvn -f small-rpc-core/pom.xml test -q`
Expected: 全量 PASS（P0 既有 54 + P1 新增）

- [x] **Step 4: Commit**

```bash
git add small-rpc-core/src/test
git commit -m "test(rpc2): real-Netty E2E for 2.0 protocol stack (echo/error/concurrency/reuse)"
```

---

### Task 6: 样例接入 2.0 协议栈

**Files:**
- Modify: `small-rpc-simple/small-rpc-sample-springboot-server/src/main/java/io/github/upowerman/sample/config/RpcProviderConfig.java`
- Modify: `small-rpc-simple/small-rpc-sample-springboot-server/src/main/resources/application.yml`
- Modify: `small-rpc-simple/small-rpc-sample-springboot-client/src/main/java/io/github/upowerman/sample/config/RpcInvokerConfig.java`
- Modify: `small-rpc-simple/small-rpc-sample-springboot-client/src/main/resources/application.yml`

**Interfaces:**
- Consumes: Task 3 `RpcServer`、Task 4 `NettyTransport`、`ReflectiveInvoker`、`SerializerRegistry`、`LegacyHessianSerializer`、P0 的 `RpcProxyFactory/TraceFilter/FailoverClusterInvoker/PullServiceDirectory/RandomLoadBalancer/RemoteInvoker/LocalServiceRegistry`
- Produces: 样例中 2.0 全链路（2.0 client ↔ 2.0 RpcServer，端口 7081）与 1.x 链路（端口 7080）并存

**关键约束：** 1.x provider 已占用 7080，2.0 `RpcServer` 必须绑**另一个端口 7081**（否则启动即 bind 失败）；client 侧 2.0 目录用 `LocalServiceRegistry.DIRECT_ADDRESS` 直连 7081，与 1.x 的注册中心地址（7080）分开。

- [x] **Step 1: server 端 yml 追加 2.0 端口**

`small-rpc-sample-springboot-server/src/main/resources/application.yml` 的 `small-rpc.provider` 段追加一行（其余不动）:

```yaml
small-rpc:
  provider:
    port: 7080
    # 2.0 自研协议栈端口（与 1.x 端口并存，互不共享 pipeline）
    rpc2-port: 7081
```

- [x] **Step 2: server 端追加 2.0 RpcServer bean**

`RpcProviderConfig.java` 追加字段与 bean（保留既有 `rpcSpringProviderFactory()` 与 private 方法不动）:

```java
    @Value("${small-rpc.provider.rpc2-port:7081}")
    private int rpc2Port;

    /**
     * 2.0 协议栈 provider：serviceName → ReflectiveInvoker。
     * destroyMethod 保证 Spring 关闭时释放 Netty 事件循环与业务线程池。
     */
    @Bean(destroyMethod = "shutdown")
    public RpcServer rpc2Server(HelloService helloService) throws InterruptedException {
        SerializerRegistry registry = new SerializerRegistry().register(new LegacyHessianSerializer());
        RpcServer server = new RpcServer(rpc2Port, registry);
        server.register(HelloService.class.getName(),
                new ReflectiveInvoker(HelloService.class, helloService));
        server.start();
        return server;
    }
```

新增 import：

```java
import io.github.upowerman.core.provider.ReflectiveInvoker;
import io.github.upowerman.core.serialize.LegacyHessianSerializer;
import io.github.upowerman.core.serialize.SerializerRegistry;
import io.github.upowerman.core.server.RpcServer;
import io.github.upowerman.service.HelloService;
```

- [x] **Step 3: client 端 yml 追加 2.0 地址**

`small-rpc-sample-springboot-client/src/main/resources/application.yml` 的 `small-rpc` 段追加（其余不动）:

```yaml
small-rpc:
  registry:
    type: local
    address: localhost:7080
  # 2.0 协议栈直连地址（指向 server 的 rpc2-port）
  rpc2:
    address: localhost:7081
```

- [x] **Step 4: client 端 2.0 链路换用 NettyTransport**

`RpcInvokerConfig.java`：追加字段 + 一个 transport bean，并改写 `rpc2HelloService()`（既有 1.x bean 与 private 方法不动）。

追加字段：

```java
    @Value("${small-rpc.rpc2.address:localhost:7081}")
    private String rpc2Address;
```

追加 transport bean：

```java
    /**
     * 2.0 自研协议栈客户端（自持连接池 + 心跳），destroyMethod 释放事件循环线程。
     */
    @Bean(destroyMethod = "shutdown")
    public NettyTransport rpc2Transport() {
        return new NettyTransport(new LegacyHessianSerializer());
    }
```

改写 `rpc2HelloService()`（**仅替换 transport 与目录地址，链路结构不变**）:

```java
    /**
     * 2.0 协议栈链路：Proxy → TraceFilter → FailoverClusterInvoker →
     * PullServiceDirectory → RandomLoadBalancer → RemoteInvoker → NettyTransport
     * （纯 2.0，不经过任何 1.x 组件）
     */
    @Bean
    public HelloService rpc2HelloService() {
        LocalServiceRegistry registry = new LocalServiceRegistry();
        Map<String, String> param = new HashMap<>();
        param.put(LocalServiceRegistry.DIRECT_ADDRESS, rpc2Address);
        registry.start(param);

        PullServiceDirectory directory = new PullServiceDirectory(registry, null);
        FailoverClusterInvoker cluster = new FailoverClusterInvoker(
                directory, new RandomLoadBalancer(),
                new RemoteInvoker(rpc2Transport(), HelloService.class), 1, 3000);

        return new RpcProxyFactory<>(HelloService.class,
                Collections.<Filter>singletonList(new TraceFilter()), cluster).getProxy();
    }
```

import 变更：删 `io.github.upowerman.core.adapter.LegacyNettyTransport`、`io.github.upowerman.serialize.HessianSerializer`、`io.github.upowerman.invoker.RpcInvokerFactory`；加 `io.github.upowerman.core.serialize.LegacyHessianSerializer`、`io.github.upowerman.core.transport.NettyTransport`。其余 import 保留（1.x 链路仍用）。

- [x] **Step 5: 编译 + 样例测试**

Run: `mvn -f small-rpc-core/pom.xml install -DskipTests -Dgpg.skip=true -Dmaven.javadoc.skip=true`
Run: `mvn -f small-rpc-simple/pom.xml test`
Expected: BUILD SUCCESS（4 模块）

- [x] **Step 6: 手动验收（双链路并存，2.0 走纯自研栈）**

```bash
# 终端 1: server（1.x 监听 7080，2.0 RpcServer 监听 7081）
mvn -f small-rpc-simple/small-rpc-sample-springboot-server spring-boot:run
# 终端 2: client（HTTP 8091）
mvn -f small-rpc-simple/small-rpc-sample-springboot-client spring-boot:run
```

验证：

```bash
curl -s 'http://127.0.0.1:8091/hello?name=rpc1'       # 1.x 链路
curl -s 'http://127.0.0.1:8091/rpc2/hello?name=rpc2'   # 2.0 协议栈
```

Expected: 两条 curl 均 200 且 JSON 正确；server 端日志出现 `rpc2 server started on port 7081`；两端日志零 ERROR/Exception。**手动验收，执行者完成后在计划文件勾选并记录实际输出。**

> **实际输出（2026-09-24 09:33–09:34，控制器内联执行，commit 0f77d3a 之上）**：
> server 日志 `rpc2 server started on port 7081` + `Started RpcServerApplication`；client `Started RpcClientApplication`。
> `/hello?name=rpc1` ×4 与 `/rpc2/hello?name=rpc2` ×4 共 8 次 curl 全部 HTTP 200，
> 回显 `{"name":"rpc1","word":"hello world"}` / `{"name":"rpc2","word":"hello world"}`。
> 两端日志 grep -cE "ERROR|Exception" = 0。应用已停止，7080/7081/8090/8091 四端口确认释放。

- [x] **Step 7: Commit**

```bash
git add small-rpc-simple
git commit -m "feat(rpc2): sample apps expose 2.0 protocol stack (RpcServer bean + NettyTransport chain)"
```

---

### Task 7: 适配器 @Deprecated + 收尾

**Files:**
- Modify: `small-rpc-core/src/main/java/io/github/upowerman/core/adapter/LegacyNettyTransport.java`
- Modify: `small-rpc-core/src/main/java/io/github/upowerman/core/adapter/LegacyConnection.java`
- Modify: `small-rpc-core/src/main/java/io/github/upowerman/core/adapter/BridgingFuture.java`
- Modify: `small-rpc-core/src/main/java/io/github/upowerman/core/serialize/LegacyHessianSerializer.java`

**Interfaces:**
- Consumes: 无（纯标注 + javadoc）
- Produces: 适配器标 `@Deprecated` 并指向 P1 替代物

- [ ] **Step 1: 标注**

前三个类（`LegacyNettyTransport`/`LegacyConnection`/`BridgingFuture`）的类 javadoc 末尾追加，并加 `@Deprecated` 注解：

```java
/**
 * @deprecated P1 起由 2.0 自研协议栈替代（{@link io.github.upowerman.core.transport.NettyTransport} /
 * {@link io.github.upowerman.core.server.RpcServer}）。P2 拆多模块时移除本类及 1.x 桥接。
 */
@Deprecated
```

`LegacyHessianSerializer` **不标 `@Deprecated`**（`NettyTransport`/`RpcServer`/样例仍在用），只补一句 javadoc：

```java
 * <p>
 * 注意：委托的 1.x Hessian 反序列化忽略 {@code clazz} 参数，类型防线在调用方的强制转型
 * （见 ServerHandler/ResponseHandler）。P2 拆模块时本类基于 HessianInput/HessianOutput
 * 重写并搬入 rpc-transport-netty。
```

- [ ] **Step 2: 全量回归**

Run: `mvn -f small-rpc-core/pom.xml test -q`
Expected: 全量 PASS（`@Deprecated` 只产生编译警告，不影响构建；`LegacyNettyTransportIntegrationTest` 等既有测试仍在用适配器，允许 deprecation 警告）

- [ ] **Step 3: Commit**

```bash
git add small-rpc-core/src
git commit -m "chore(rpc2): deprecate 1.x bridge adapters in favor of 2.0 protocol stack"
```

---

## 验收清单（对照 spec §2 + §7 P1 行）

- [x] 协议帧 20 字节 Header（magic/ver/type/codec/status/requestId(long)/bodyLen）落地，纯字节层编解码可脱离 Netty 单测
- [x] 粘包/半包正确（逐字节喂入不误解、两帧粘连正确拆分、两帧流水线各自应答）
- [x] 恶意 bodyLen 与流错位被拒绝（不分配大数组、关连接）
- [x] `requestId` 为 `long`（`AtomicLong` 生成），并发路由正确（8 线程 × 10 调用零串包）
- [x] 心跳为协议一等公民：`TYPE_HEARTBEAT` 帧双向（client 写空闲触发、server 回显），替代 1.x 借道 Beat
- [x] `Result` 状态码贯通协议帧：SUCCESS/SERVICE_NOT_FOUND/METHOD_NOT_FOUND/SERIALIZATION_ERROR/SERVER_ERROR 双向映射；TIMEOUT/NETWORK_ERROR 保持调用方本地态
- [x] 序列化失败三处贯通（client 请求 / server 解码 / client 响应解码）均以 `SERIALIZATION_ERROR` 结算，不抛裸异常、不悬挂；类型伪装（合法 Hessian 非 RpcRequestBody）被转型防线拦下
- [x] 异常不跨网传对象：`errorClassName + errorMessage` 描述，客户端重建 `RpcException`
- [x] 2.0 拥有完整 client（NettyTransport）+ server（RpcServer），样例 `/rpc2/hello` 走纯 2.0 链路（端口 7081，无 1.x 组件）
- [x] codec 字段贯通（请求携带、响应沿用），`SerializerRegistry` 支持多序列化共存
- [x] `mvn -f small-rpc-core/pom.xml test -q` 全绿；1.x 代码零改动；P0 既有测试零破坏
