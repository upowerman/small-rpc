<div align="right">
  <a href="README.md">中文</a> | <strong>English</strong>
</div>

# Small-RPC 3.0

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.upowerman/small-rpc-spring-boot-starter.svg)](https://central.sonatype.com/artifact/io.github.upowerman/small-rpc-spring-boot-starter)
[![Java](https://img.shields.io/badge/java-8+-green.svg)](https://www.oracle.com/java/)
[![Netty](https://img.shields.io/badge/netty-4.1.108-orange.svg)](https://netty.io/)
[![Spring Boot](https://img.shields.io/badge/spring--boot-2.7.18-brightgreen.svg)](https://spring.io/projects/spring-boot)

## 📖 Project Overview

**Small-RPC** is a high-performance, lightweight, and extensible Java RPC framework crafted specifically for in-depth understanding of distributed RPC kernel mechanics.

3.0 continues the "see through the RPC kernel" journey on the 2.x microkernel foundation, completing the last missing piece of consumer-side fault-tolerance governance:
- **Consumer Governance Loop (3.0 new)**: circuit breaker (dual-condition error/slow-call rate state machine), client rate limiting (token bucket + concurrency semaphore), Fallback degradation, in-flight cap fast-fail — all opt-in, off by default;
- **Microkernel Architecture** (2.x foundation): core abstractions thoroughly decoupled from network transport; `rpc-core` maintains zero dependencies on network protocols or serialization formats;
- **Self-Developed Industrial SPI** (2.x foundation): default implementation binding, field dependency injection (with circular dependency detection), runtime adaptive dynamic dispatching;
- **Event-Driven Registry** (2.x foundation): retires the 1.x synchronous pull model in favor of `Registry` subscribe-push, combined with `CachingServiceDirectory` local snapshot caching for lock-free reads and **zero-loss degradation during registry failures**;
- **Full Ecosystem Extensions** (2.x foundation): out-of-the-box local direct connection, Apache ZooKeeper (Curator 5.6 ephemeral nodes + real-time push), and Redis (Jedis 4.4 Set registry + polling diff push);
- **Async & Fault-Tolerance Foundation** (2.x foundation): Failover cluster retry, timeout budget, `CompletableFuture` async invocation, weighted load balancing with instance warmup, end-to-end Trace context, Spring Boot graceful shutdown deregistration.

> ⚠️ **Learning & Research Positioning**: This project focuses on crystal-clear presentation of core mechanisms and clean engineering design, making it ideal for deeply studying distributed network programming, RPC wire protocols, and service governance.

---

## 📜 Architecture Evolution & 1.0 Review

### 1. Origin & Motivation (October 2019)

Small-RPC was born in **October 2019** (initial commit on `2019-10-09`).

**Original Motivation: To deeply study and demystify the core internals of RPC.**
In the era of microservices, industrial-grade frameworks such as Dubbo, gRPC, and Spring Cloud are immensely powerful but feature massive codebases. Critical low-level mechanics—such as network I/O threading models, binary wire protocol framing/codecs, dynamic proxy stubs, and service registration/discovery—are hidden deep beneath layers of complex abstractions. Beginners often find themselves merely configuring annotations and APIs without truly understanding what transpires over the network during an RPC invocation.

To break this "black box", Small-RPC was built **from scratch**: based on classic Java + Netty + Hessian, manually implementing network communication, dynamic proxies, service export, and remote invocations to form a minimal, readable, and debuggable closed-loop RPC prototype (Small-RPC 1.0).

### 2. 1.0 Minimal Architecture & Mechanics

In version 1.0, the framework prioritized a minimal viable implementation for step-by-step debugging:
- **Monolithic Architecture**: All core logic resided in a single module `small-rpc-core`, encompassing Netty communication, Hessian encoding/decoding, basic registry discovery, and Spring integration;
- **Transport & Serialization**: Utilized Netty 4 NIO channels, directly serializing Java objects to binary streams via Hessian;
- **Manual Spring Factory Wiring**: Configured `RpcSpringProviderFactory` (managing ports, thread pools, and Netty Server bootstrap) and `RpcSpringInvokerFactory` (managing service discovery and client JDK dynamic proxy creation), utilizing `@RpcService` and `@RpcReference` annotations;
- **Basic Registry**: Started with `LocalServiceRegistry` (single-machine static direct-connect / in-memory registry), and later explored basic pull-based registration with Redis and ZooKeeper in v1.1/1.2.

### 3. 1.0 Bottlenecks & Why 2.0

As research progressed and distributed requirements grew, structural bottlenecks in 1.0 emerged, sparking the complete microkernel redesign in 2.0:
1. **Tight Coupling between Core and Protocols**: Core logic was strictly bound to Netty and Hessian, making it impossible to swap transport engines or serialization algorithms without modifying core code;
2. **Lack of an Extensibility Mechanism**: Implementation swapping relied on hardcoded class names or reflection without a unified SPI standard;
3. **Fragile Registry without Fault Degradation**: Synchronous pull / direct connections meant any registry outage or network partition immediately caused invocation failures without local snapshot fallbacks;
4. **Lack of Wire Protocol Robustness**: Missing magic numbers, protocol versioning, and fine-grained protocol status codes, making troubleshooting network framing and transport errors difficult;
5. **Invasive Spring Integration**: Users had to manually define Factory `@Bean`s instead of enjoying modern Spring Boot Starter auto-configuration.

### 4. 1.0 vs 2.0 Comprehensive Comparison

| Dimension | 1.0 Prototype (2019) | 2.x Microkernel Architecture |
|---|---|---|
| **Positioning & Goal** | Minimal educational prototype; basic RPC communication loop | High-performance microkernel architecture; educational insight + production-grade governance |
| **Module Decoupling** | Monolithic `small-rpc-core` (mixed dependencies) | 8 dedicated submodules; `rpc-core` depends solely on `slf4j-api` (zero transport/protocol dependencies) |
| **Extension Mechanism** | No SPI; configured via Class name / reflection | Self-developed industrial SPI: `@Spi` default name, `@SpiInject` IoC (with cycle detection), `@Adaptive` dynamic proxy dispatch |
| **Wire Protocol Frame** | Raw Hessian byte stream without custom header | Custom binary frame: Magic `0x5352`, MsgType discriminator, global request ID, wire status code passthrough |
| **Registry & Discovery** | Synchronous pull / local static direct address | Event-driven pub-sub model (`subscribe` / `onChange`) with immutable snapshot pushes |
| **HA & Degradation** | Registry failure immediately breaks calls | **Zero-loss degradation**: `CachingServiceDirectory` local frozen snapshot read; Redis lease heartbeats & network self-healing |
| **Cluster & Fault Tolerance** | Single-endpoint call or basic random | Failover cluster retry, dynamic SPI load balancers (Random / RoundRobin), end-to-end Trace tracking |
| **Spring Ecosystem** | Manual Factory `@Bean` definition | `small-rpc-spring-boot-starter` auto-configuration with strongly-typed YAML hints and validation |

### 5. 3.0: Consumer Governance Loop (Why 3.0)

When the 2.0 rewrite wrapped up, the design document left an explicit hook for fault-tolerance governance: the `FailoverClusterInvoker` status-code model was designed around "circuit-breaker decision inputs", and the source comment read "circuit breaker deferred to 3.0 — the status-code model has reserved the decision basis for it". **The theme of 3.0 is delivering on that promise** — completing the fault-tolerance foundation laid in 2.x (Failover retry, timeout budget, weighted warmup, server-side CallerRuns backpressure) into a full consumer governance loop:

| Dimension | 2.x Microkernel Foundation | 3.0 Governance Loop (Current) |
|---|---|---|
| **Failure Isolation** | Failover retries on another instance, but sustained failures amplify traffic | **Circuit breaker**: sliding window tracks error rate / slow-call rate; breaching either trips OPEN with fast-fail; half-open probes self-heal (Hystrix/Sentinel state machine + Resilience4j dual condition) |
| **Traffic Protection** | Server-side CallerRuns backpressure (inbound) | **Client rate limiting**: token bucket + concurrency semaphore (outbound); **in-flight cap** fails fast with CLIENT_BUSY, completing full-link backpressure |
| **Failure Aftermath** | Failures surface as RpcException to the caller | **Fallback degradation**: `@RpcReference(fallback=...)` rescue (Dubbo Mock idea) |
| **Observability** | Call counts and RT (`service#method`) | New cause-attributed rejection counters (circuit-open / rate-limit / concurrent-limit / client-busy) and degradation counter |

The three governance filters sit on the consumer chain (`Trace → Metrics → Fallback → RateLimit → CircuitBreaker → FailoverCluster`), and together with the new SPI extension points (`CircuitBreaker` / `RateLimiter`) uphold the teaching stance that "every mechanism can be read, debugged, and switched off in isolation"; rejection statuses (`CIRCUIT_OPEN` / `RATE_LIMITED` / `CLIENT_BUSY`) continue the 2.x "local status" principle — settled on the consumer only, never on the wire.

### 6. Checking Out Historical Source Code

To explore the full journey from the minimal 1.0 prototype to the 2.0 microkernel and then the 3.0 governance loop, check out the historical Git tags:

```bash
# Check out the initial 1.0 educational prototype
git checkout 1.0.0

# Check out 1.x final evolutionary release (with Redis / ZK exploration)
git checkout 1.2.0

# Check out the completed 2.x microkernel (pre-governance: weighted warmup / async / generic / metrics)
git checkout 2.2.0

# Switch back to the 3.0 governance-loop architecture
git checkout master
```

---

## 🏗️ Module Architecture

The entire project manages dependencies and plugin versions centrally via the parent POM (based on the Spring Boot 2.7.18 dependency management ecosystem), split into 8 single-responsibility submodules:

| Module | Core Responsibility | Key Dependencies |
|---|---|---|
| **`rpc-core`** | Microkernel abstraction: SPI mechanism, Invocation, Invoker, Filter chain, LoadBalancer, Registry abstraction, CachingServiceDirectory degradation, governance abstractions (CircuitBreaker/RateLimiter SPI, sliding window, Fallback/RateLimit/CircuitBreaker filters) | Only `slf4j-api` (zero third-party protocol/transport dependencies) |
| **`rpc-transport-netty`** | Custom binary protocol frame (Magic `0x5352`) codec, Netty 4 communication engine, SerializerRegistry, Hessian serialization, RpcServer, consumer in-flight cap (PendingRequests) | Netty 4.1, Hessian 4.0 |
| **`rpc-registry-local`** | Local direct-connect registry: supports static configuration and in-process memory registry for local testing | `rpc-core` |
| **`rpc-registry-zookeeper`** | ZooKeeper registry: EPHEMERAL instance nodes, PathChildrenCache real-time event pushes, automatic reconnection | Apache Curator 5.6, ZooKeeper 3.7+ |
| **`rpc-registry-redis`** | Redis registry: Set collection registry, scheduled diff polling pushes, thread-safe scheduling management | Jedis 4.4 |
| **`small-rpc-spring`** | Spring framework integration: `@RpcService` service export, `@RpcReference` reference injection, `ReferenceBeanPostProcessor` proxy generation | Spring Context 5.3+ |
| **`small-rpc-spring-boot-starter`** | Spring Boot auto-configuration: Provider/Consumer auto-config, configuration-driven, graceful shutdown deregistration | Spring Boot Autoconfigure 2.7+ |
| **`rpc-examples`** | End-to-end sample application: `rpc-example-api` (interfaces), `rpc-example-server` (provider), `rpc-example-client` (consumer & web controllers) | Spring Boot Starter Web |

---

## 🔄 Self-Developed SPI Extension Mechanism

The framework adopts a unified SPI extension specification. Extension definition files are placed in `META-INF/small-rpc/<interface-full-name>`, with format `extensionName=implementationClassFullName`.

### Core Capabilities

1. **`@Spi("defaultName")`**:
   Annotated on the SPI interface to declare the default extension name, e.g., `@Spi("random") public interface LoadBalancer`.
2. **`@SpiInject` Field Dependency Injection**:
   Annotated on extension implementation fields. The SPI loader automatically resolves and injects the default singleton of the dependency type; includes same-thread circular dependency detection, immediately failing fast with `IllegalStateException`.
3. **`@Adaptive("key")` Dynamic Adaptive Proxy**:
   Dynamically routes to specific extension implementations based on the invocation parameter `attachments[key]`, gracefully falling back to default extension if omitted.
4. **Multi-Resource Deduplication (name, FQCN)**:
   In multi-ClassLoader or classpath-overlapping environments, matches extension names with class names for idempotent deduplication; strictly throws an error if names match but class names differ.

### Core SPI Extension Points

| Interface | SPI File Path | Built-in Implementations | Default |
|---|---|---|---|
| `Registry` | `io.github.upowerman.core.registry.Registry` | `local`, `zookeeper`, `redis` | `local` |
| `LoadBalancer` | `io.github.upowerman.core.loadbalance.LoadBalancer` | `random`, `roundrobin`, `weightedrandom` | `random` |
| `Serializer` | `io.github.upowerman.core.serialize.Serializer` | `hessian` (typeId=1), `json` (typeId=2, Jackson) | `hessian` |
| `Filter` | `io.github.upowerman.core.filter.Filter` | `trace`, `metrics` (wired into the consumer chain) | — |
| `CircuitBreaker` | `io.github.upowerman.core.governance.CircuitBreaker` | `default` (sliding window + three-state machine) | `default` |
| `RateLimiter` | `io.github.upowerman.core.governance.RateLimiter` | `tokenbucket` (token bucket, capacity=qps) | `tokenbucket` |

### Weighted Load Balancing & Warmup (weightedrandom)

Instance metadata (weight / startTime / warmup) propagates with registration data: ZooKeeper writes it into ephemeral node data, Redis keeps a parallel meta Hash, Local stores it in memory. `weightedrandom` splits traffic by effective weight = `weight × warmup factor` — within the warmup window after startup, traffic ramps linearly from 0 to full weight; when `startTime` is unknown (legacy data) or warmup is not configured, full weight applies. If all effective weights are 0, it falls back to uniform random. Legacy registration data is treated as equal-weight and pre-warmed — forward and backward compatible.

### Async Invocation

Any interface method returning `CompletableFuture<T>` is an async call, no annotation needed: the proxy passes the internal call-chain future straight through (no blocking at the proxy layer), the server automatically unwraps a future returned by the implementation method, and the response frame carries the final business value. Sync and async methods can be mixed on the same interface.

### Generic Invocation

Invoke by service name + method name + parameter type names without the business API jar (gateway/console/script scenarios). Inject the `GenericService` bean:

```java
@Autowired
private GenericService genericService;

Object result = genericService.$invoke(
        "com.example.api.HelloService",   // service name (interface FQCN)
        "hello",                           // method name
        new String[]{"java.lang.String"}, // parameter type names (int/long shorthands supported)
        new Object[]{"world"});            // argument values
```

> Current boundary: parameter types are resolved on the consumer classpath (`Class.forName`); true zero-dependency generic invocation (reflective type construction from descriptors) remains a protocol-level evolution direction, not part of the 3.0 governance theme.

### Call Metrics

The consumer chain ships with `MetricsFilter`, accumulating total / success / failure / cumulative RT / max RT per `service#method`; the process-level singleton `RpcMetrics.getInstance().snapshot()` returns a monotonic cumulative snapshot (delta over a time window gives QPS). 3.0 governance extensions: the snapshot now exposes `rejections()` (cause-attributed: circuit-open / rate-limit / concurrent-limit / client-busy) and `degraded()` (fallback rescues). Bridging to Micrometer or similar is left to the user based on the snapshot.

---

## 🌐 Registry Pub-Sub & Directory Degradation

### 1. Subscribe-Push Event-Driven Model
- Completely replaces the inefficient 1.x pattern of issuing synchronous Pull queries to the registry on every RPC call;
- The `Registry` interface uses `subscribe(service, ServiceListener)`. Whenever registry instances change, an asynchronous callback pushes an **immutable full instance snapshot** via `ServiceListener.onChange(snapshot)`;
- Provider instances are managed through the Spring container lifecycle: automatically registering after binding the port, and automatically invoking `unregister` during shutdown (Spring context destruction or SIGTERM shutdown hook).

### 2. CachingServiceDirectory Degradation Guarantees
- **Non-blocking Invocations**: When RPC consumers initiate requests, instances are read solely from a local `ConcurrentHashMap` read-only snapshot, never synchronously waiting on remote registries;
- **Zero-Loss Degradation during Registry Outages**: When the registry encounters network partitions, latency spikes, or complete failure, local caches are **completely frozen and retained**, allowing existing service instances to continue serving without disruption;
- **Self-Healing**: Once registry nodes recover and reconnect, the real-time push pipeline resumes, and the local cache updates instantly to the latest state.

---

## 🛡️ Consumer Governance (3.0)

Governance filters sit on the consumer chain in the order `Trace → Metrics → Fallback → RateLimit → CircuitBreaker → FailoverCluster`; backpressure lives in the transport layer. Everything is off by default (opt-in) — zero behavior change on upgrade. Consistent with 2.x "local status" principles: rejections are settled locally on the consumer (`CIRCUIT_OPEN` / `RATE_LIMITED` / `CLIENT_BUSY`) and never travel on the wire.

| Capability | Mechanism | Inspired by |
|---|---|---|
| **Circuit Breaker** | Sliding window (second-level buckets) tracks error rate and slow-call rate; breaching either trips OPEN with fast-fail; after `open-ms` HALF_OPEN admits probes, success restores CLOSED | Hystrix/Sentinel state machine + Resilience4j dual condition |
| **Client Rate Limiting** | Per-resource (`service#method`) token bucket + concurrency semaphore, instant rejection | Sentinel local flow control |
| **Fallback Degradation** | `@RpcReference(fallback = X.class)` — on downstream failure or governance rejection, invoke the fallback implementation (container bean first, reflective instantiation otherwise) | Dubbo Mock (fail semantics) |
| **In-flight Backpressure** | Consumer-wide in-flight cap; over-cap calls fail fast with `CLIENT_BUSY`, completing full-link backpressure with server-side CallerRuns | TCP congestion control |

```java
public static class HelloFallback implements HelloService {
    @Override
    public HelloDTO hello(String name) {
        return new HelloDTO(name, "degraded: provider unavailable");
    }
}

@RpcReference(fallback = HelloFallback.class)
private HelloService protectedHelloService;
```

```yaml
small-rpc:
  consumer:
    max-in-flight: 100
    governance:
      circuit-breaker:
        enabled: true
        min-calls: 5
        open-ms: 5000
      rate-limit:
        enabled: true
        qps: 50
```

> Metrics attribution: `RpcMetrics` snapshot `rejections()` splits by four causes (circuit-open / rate-limit / concurrent-limit / client-busy); `degraded()` counts fallback rescues (see "Call Metrics").

---

## 🚀 Three Service Consumption Modes

Small-RPC provides three flexible service consumption models, covering everything from rapid local debugging to distributed cluster governance:

### Mode 1: Local Default Direct Connection
Ideal for local single-machine debugging. The client directly configures the target Provider address (with IDE attribute auto-completion):
```yaml
small-rpc:
  consumer:
    enabled: true
  provider:
    enabled: false
  registry:
    type: local
    local:
      direct-address: localhost:7081
```
> [!NOTE]
> For backwards compatibility, the legacy parameter `small-rpc.registry.param.DIRECT_ADDRESS` is still fully supported as a fallback.

### Mode 2: `@RpcReference(address = "...")` Annotation-Level Direct Connection
Explicitly specify the instance address in code, completely bypassing registry resolution and load balancing:
```java
@RestController
public class OrderController {
    // Force point-to-point call to specified IP and port; ideal for canary or debug testing
    @RpcReference(address = "127.0.0.1:7081")
    private HelloService helloService;
}
```

### Mode 3: Registry-Driven Discovery & Dynamic Load Balancing
Designed for distributed microservices. Instance management is handled by the registry, coupled with `LoadBalancer` dynamic routing and failover retry:
```yaml
small-rpc:
  consumer:
    enabled: true
  provider:
    enabled: false
  registry:
    type: zookeeper      # or redis / local
    zookeeper:
      connect: localhost:2181
      namespace: small-rpc
  loadbalance: random    # random or roundrobin
```
If using the Redis registry:
```yaml
small-rpc:
  registry:
    type: redis
    redis:
      host: localhost
      port: 6379
```

---

## 🛠️ Quick Start

### 1. Add Maven Dependency

Add the starter to your Spring Boot application's `pom.xml` (comes pre-packaged with single-machine Local direct registry, zero external dependencies required out of the box):

```xml
<dependency>
    <groupId>io.github.upowerman</groupId>
    <artifactId>small-rpc-spring-boot-starter</artifactId>
    <version>2.2.0</version>
</dependency>

<!-- Production cluster deployment: add distributed registry implementation on demand (choose one) -->
<!-- 1. ZooKeeper Registry -->
<dependency>
    <groupId>io.github.upowerman</groupId>
    <artifactId>rpc-registry-zookeeper</artifactId>
    <version>2.2.0</version>
</dependency>

<!-- 2. Redis Registry -->
<dependency>
    <groupId>io.github.upowerman</groupId>
    <artifactId>rpc-registry-redis</artifactId>
    <version>2.2.0</version>
</dependency>
```

### 2. Service Provider Implementation

Configure the service provider port in `application.yml`:
```yaml
server:
  port: 8090
small-rpc:
  provider:
    rpc2-port: 7081
  consumer:
    enabled: false    # Recommended to disable consumer auto-config on pure provider nodes
  registry:
    type: local       # Local direct; replace with zookeeper or redis for clusters
```

Define business interface and implementation, and expose the service with `@RpcService`:
```java
@Service
@RpcService
public class HelloServiceImpl implements HelloService {
    @Override
    public HelloDTO hello(String name) {
        return new HelloDTO(name, "hello world");
    }
}
```

### 3. Service Consumer Reference

Inject remote proxy objects in Controller or Service using `@RpcReference`:

```java
@RestController
public class HelloController {
    @RpcReference
    private HelloService helloService;

    @GetMapping("/rpc2/hello")
    public HelloDTO hello(@RequestParam("name") String name) {
        return helloService.hello(name);
    }
}
```

---

## 🧪 Multi-Instance Cluster & High Availability Verification

The project includes ready-to-run demo modules (`rpc-example-server` and `rpc-example-client`) to verify multi-instance load balancing, automatic node eviction upon failure, and registry outage degradation.

### Scenario A: ZooKeeper Multi-Instance & Node Failure Eviction

1. **Start Local ZooKeeper** (Docker):
   ```bash
   docker run -d --name zookeeper -p 2181:2181 zookeeper:3.9
   ```

2. **Start Provider Instance 1** (Web: 8090, RPC: 7081):
   ```bash
   java -jar rpc-examples/rpc-example-server/target/rpc-example-server-2.2.0.jar \
     --spring.profiles.active=zookeeper --server.port=8090 --small-rpc.provider.rpc2-port=7081
   ```

3. **Start Provider Instance 2** (Web: 8092, RPC: 7082):
   ```bash
   java -jar rpc-examples/rpc-example-server/target/rpc-example-server-2.2.0.jar \
     --spring.profiles.active=zookeeper --server.port=8092 --small-rpc.provider.rpc2-port=7082
   ```

4. **Start Consumer** (Web: 8091):
   ```bash
   java -jar rpc-examples/rpc-example-client/target/rpc-example-client-2.2.0.jar \
     --spring.profiles.active=zookeeper --server.port=8091
   ```

5. **Verify Multi-Instance Load Balancing**:
   Send 20 consecutive requests:
   ```bash
   for i in {1..20}; do curl -s "http://127.0.0.1:8091/rpc2/hello?name=zk-$i"; done
   ```
   Check server logs: invocations are evenly distributed across both instances (7081 and 7082).

6. **Verify Failure Eviction and Failover**:
   Force kill instance 1 (`kill -9 <instance1_PID>`).
   Wait for the ZooKeeper session to expire (≤10s), then send another 20 requests:
   All requests remain 100% successful and **smoothly fail over to the surviving 7082 instance**!

---

### Scenario B: Zero-Loss Degradation during Registry Outage (Directory Cache Degradation Core Guarantee)

1. With the ZooKeeper cluster running and consumers already subscribed;
2. **Completely stop the ZooKeeper service**:
   ```bash
   docker stop zookeeper
   ```
3. Send requests to verify degradation:
   ```bash
   curl -s "http://127.0.0.1:8091/rpc2/hello?name=degraded"
   ```
   **Requests continue to return HTTP 200 normally**! Consumers rely on frozen local in-memory snapshots, unaffected by registry downtime.
4. **Restore the ZooKeeper service**:
   ```bash
   docker start zookeeper
   ```
   Consumer and provider automatically reconnect, resuming real-time pushes and directory synchronization.

---

### Scenario C: Redis Registry Multi-Instance Demo

1. **Start Local Redis** (Docker):
   ```bash
   docker run -d --name redis -p 6379:6379 redis:7
   ```
2. Start two Providers and one Consumer with `--spring.profiles.active=redis`;
3. Send requests to observe load balancing;
4. Gracefully terminate instance 1 (`kill <instance1_PID>`). The provider triggers deregistration (`SREM`), and the Redis polling task synchronizes within 3–5 seconds, switching calls to the 7082 instance.
5. **Passive Termination Eviction**: Redis uses "liveness lease keys" (`{prefix}:registry-heartbeat:{service}:{address}`) to determine instance health—Provider periodically renews (`heartbeat-ms`), while Consumer evicts instances whose lease has expired (exceeding `heartbeat-stale-ms`). Therefore, **`kill -9` / OOM / container evictions** are automatically cleaned up in the next polling cycle, preventing zombie entries.

---

### Scenario D: Circuit Open Fast-Fail & Half-Open Self-Healing (3.0 Governance Demo)

The example client ships with governance demo config (`circuit-breaker.enabled=true, min-calls=5, open-ms=5000`) and a fallback-protected reference (`/rpc2/protected-hello`).

1. Start provider and consumer (as in Scenario A);
2. **Inject consecutive failures** (server throws on `name=boom`), fire 10 requests:
   ```bash
   for i in {1..10}; do curl -s "http://127.0.0.1:8091/rpc2/protected-hello?name=boom"; done
   ```
3. **Verify fast-fail**: switch to a normal call immediately — the circuit is OPEN, requests never reach the server (no new `hello called` in server logs), the call fails fast with CIRCUIT_OPEN and the Fallback returns the degraded message with near-zero RT:
   ```bash
   curl -s "http://127.0.0.1:8091/rpc2/protected-hello?name=alice"
   # {"name":"alice","message":"degraded: provider unavailable"}
   ```
4. **Verify half-open healing**: after `open-ms` (5s) the breaker admits a half-open probe; the recovered service succeeds and the circuit returns to CLOSED:
   ```bash
   sleep 6 && curl -s "http://127.0.0.1:8091/rpc2/protected-hello?name=alice"
   # {"name":"alice","message":"hello world"}
   ```

### Scenario E: Rate Limiting & In-flight Cap (3.0 Governance Demo)

The example client is configured with `rate-limit.qps=50` and `max-in-flight=100`.

1. **Rate limiting**: burst-load to exhaust the token bucket; some requests fail instantly (RATE_LIMITED, caught by fallback):
   ```bash
   for i in {1..100}; do curl -s "http://127.0.0.1:8091/rpc2/protected-hello?name=rl-$i" & done; wait
   ```
   Responses mix `hello world` with `degraded: provider unavailable` (the latter are rate-limited calls rescued by fallback).
2. **In-flight cap**: `name=slow-...` blocks the server for 4s holding in-flight slots; requests beyond `max-in-flight` get CLIENT_BUSY immediately (no memory pile-up on the client):
   ```bash
   for i in {1..120}; do curl -s "http://127.0.0.1:8091/rpc2/protected-hello?name=slow-$i" & done; wait
   ```
3. **Metrics attribution**: read `RpcMetrics.snapshot()` from the consumer — `rejections()` splits by cause (circuit-open / rate-limit / concurrent-limit / client-busy), `degraded()` counts fallback rescues.

---

## ⚙️ Configuration Reference

| Configuration Key | Default | Type | Description |
|---|---|---|---|
| `small-rpc.provider.enabled` | `true` | Boolean | Whether to enable service provider (set to `false` for pure consumer apps) |
| `small-rpc.provider.rpc2-port` | `7081` | Integer | RpcServer Netty listening port |
| `small-rpc.provider.address` | Auto-detected IP | String | Advertised provider address (`IP:Port`). Recommended to configure explicitly in containerized/multi-NIC environments |
| `small-rpc.provider.weight` | `1` | Integer | Static instance weight: `weightedrandom` splits traffic proportionally; 1 = equal weight |
| `small-rpc.provider.warmup-ms` | `0` | Long | Warmup window (ms): >0 ramps traffic linearly from 0 to full weight after startup; 0 = warmup off |
| `small-rpc.provider.business-threads` | `8` | Integer | Server business thread-pool size (request deserialization and reflective execution; Netty IO threads untouched) |
| `small-rpc.provider.business-queue-capacity` | `0` | Integer | Bounded queue capacity of the business pool; 0 = unbounded. When full, the submitting thread executes the task (CallerRuns backpressure) — no rejections |
| `small-rpc.provider.shutdown-drain-ms` | `10000` | Long | Graceful-shutdown drain limit (ms): deregister first, then wait for in-flight requests; 0 = close immediately |
| `small-rpc.consumer.enabled` | `true` | Boolean | Whether to enable service consumer |
| `small-rpc.consumer.timeout-ms` | `3000` | Long | Global default per-call timeout (deadline budget incl. retries); applies when `@RpcReference.timeout` is unset |
| `small-rpc.consumer.retries` | `1` | Integer | Global default retry count (extra attempts, 0 = no retry); applies when `@RpcReference.retries` is unset |
| `small-rpc.serializer` | `hessian` | String | Consumer serializer SPI name: `hessian` / `json`; the server auto-detects by frame codec — no symmetric config needed |
| `small-rpc.registry.type` | `local` | String | Registry SPI extension name: `local` / `zookeeper` / `redis` |
| `small-rpc.registry.local.direct-address` | `""` | String | Target Provider address under Local direct mode |
| `small-rpc.registry.zookeeper.connect` | `localhost:2181` | String | ZooKeeper connection string (comma-separated for multi-address) |
| `small-rpc.registry.zookeeper.namespace` | `small-rpc` | String | ZooKeeper namespace root path |
| `small-rpc.registry.zookeeper.session-timeout-ms` | `10000` | Integer | ZooKeeper session timeout (ms) |
| `small-rpc.registry.zookeeper.connection-timeout-ms` | `3000` | Integer | ZooKeeper connection timeout (ms) |
| `small-rpc.registry.redis.host` | `localhost` | String | Redis server hostname/IP address |
| `small-rpc.registry.redis.port` | `6379` | Integer | Redis server port (1–65535) |
| `small-rpc.registry.redis.database` | `0` | Integer | Redis database index (default: 0) |
| `small-rpc.registry.redis.timeout-ms` | `2000` | Integer | Redis connection and read timeout (ms) |
| `small-rpc.registry.redis.password` | none | String | Redis authentication password (leave empty if none) |
| `small-rpc.registry.redis.key-prefix` | `small-rpc:registry:` | String | Redis registry key prefix |
| `small-rpc.registry.redis.poll-interval-ms` | `3000` | Long | Redis registry diff polling interval (ms) |
| `small-rpc.registry.redis.heartbeat-ms` | `1000` | Long | Provider heartbeat renewal interval (ms) |
| `small-rpc.registry.redis.heartbeat-stale-ms` | `5000` | Long | Liveness lease TTL (ms); expired providers are evicted by consumer (must be ≥ heartbeat-ms) |
| `small-rpc.registry.param.*` | - | Map<String, String> | Generic underlying registry parameter dictionary (backwards-compatible) |
| `small-rpc.loadbalance` | `random` | String | Global load balancer SPI name: `random` (random), `roundrobin` (round-robin), or `weightedrandom` (weighted random, with `provider.weight` / `provider.warmup-ms`) |
| `small-rpc.consumer.max-in-flight` | `0` | Integer | Consumer in-flight request cap; 0 = unlimited. Over-cap calls fail fast with CLIENT_BUSY |
| `small-rpc.consumer.governance.circuit-breaker.enabled` | `false` | Boolean | Circuit breaker switch (opt-in) |
| `small-rpc.consumer.governance.circuit-breaker.error-rate-threshold` | `0.5` | Double | Error-rate trip threshold (requires ≥ min-calls samples in window) |
| `small-rpc.consumer.governance.circuit-breaker.slow-call-rate-threshold` | `0.5` | Double | Slow-call-rate trip threshold |
| `small-rpc.consumer.governance.circuit-breaker.slow-rt-ms` | `0` | Long | Slow-call threshold ms; 0 = derive as 80% of call timeout budget |
| `small-rpc.consumer.governance.circuit-breaker.min-calls` | `20` | Integer | Minimum samples in window (prevents cold-start false trips) |
| `small-rpc.consumer.governance.circuit-breaker.window-ms` | `10000` | Long | Statistics sliding window (ms) |
| `small-rpc.consumer.governance.circuit-breaker.open-ms` | `10000` | Long | Circuit OPEN duration (ms) |
| `small-rpc.consumer.governance.circuit-breaker.half-open-probes` | `1` | Integer | HALF_OPEN probe permits |
| `small-rpc.consumer.governance.rate-limit.enabled` | `false` | Boolean | Rate limit switch (opt-in) |
| `small-rpc.consumer.governance.rate-limit.qps` | `0` | Integer | Per-resource token bucket rate (per second); 0 = unlimited |
| `small-rpc.consumer.governance.rate-limit.max-concurrent` | `0` | Integer | Per-resource concurrency cap (semaphore); 0 = unlimited. Scope is **a single @RpcReference proxy** (independent per proxy) — multiple fields of the same interface multiply the effective cap |

---

## 🔄 End-to-End Invocation Sequence Diagram

### 3.0 Call Flow (with Governance Chain)

```mermaid
sequenceDiagram
    autonumber
    participant App as Business Caller
    participant Proxy as JDK Dynamic Proxy
    participant Trace as TraceFilter
    participant Metrics as MetricsFilter
    participant FB as FallbackFilter
    participant RL as RateLimitFilter
    participant CB as CircuitBreakerFilter
    participant Cluster as FailoverClusterInvoker
    participant Dir as CachingServiceDirectory
    participant LB as LoadBalancer (SPI)
    participant Client as NettyClientTransport
    participant Server as RpcServer
    participant Invoker as ReflectiveInvoker
    participant Svc as @RpcService Bean

    App->>Proxy: helloService.hello("p3")
    Proxy->>Trace: invoke(Invocation)
    Trace->>Metrics: invoke(Invocation)
    Metrics->>FB: invoke(Invocation)
    FB->>RL: invoke(Invocation)
    RL->>CB: invoke(Invocation)
    CB->>CB: tryPass? If OPEN, settle CIRCUIT_OPEN immediately
    CB->>Cluster: invoke(Invocation)
    Cluster->>Dir: list(Invocation) [Read from local read-only snapshot]
    Dir-->>Cluster: List<ServiceInstance>
    Cluster->>LB: select(invokers, invocation)
    LB-->>Cluster: Selected RemoteInvoker
    Cluster->>Client: invoke(Invocation)
    Client->>Server: Send custom binary protocol Frame (Magic 0x5352)
    Server->>Invoker: Decode, validate, and dispatch to business thread
    Invoker->>Svc: Invoke business method via reflection
    Svc-->>Invoker: Return business DTO
    Invoker-->>Server: Result (Status.SUCCESS)
    Server-->>Client: Write back response Frame
    Client-->>Cluster: CompletableFuture.completedFuture(Result)
    Cluster-->>CB: Result (feed breaker stats incl. final retry outcome)
    CB-->>RL: Result (release concurrency permit)
    RL-->>FB: Result (invoke fallback if not SUCCESS)
    FB-->>Metrics: Result (record degraded if rescued)
    Metrics-->>Trace: Result (counters / timing)
    Trace-->>Proxy: Result.value()
    Proxy-->>App: Return HelloDTO
```

---

## 📜 License

This project is licensed under the [Apache License 2.0](LICENSE).
