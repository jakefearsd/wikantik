---
title: Distributed Tracing
related:
- AgentObservability
- ServiceLevelAgreements
- BlamelessPostMortems
- MicroservicesArchitecture
type: article
summary: Technical deep-dive into distributed tracing mechanics, W3C propagation,
  and tail-based sampling architectures.
status: active
date: '2026-04-26'
canonical_id: 01KQ12YDTP6JTFQEKQZT19ACQZ
hubs:
- ObservabilityHub
tags:
- observability
- tracing
- opentelemetry
- jaeger
- microservices
auto-generated: false
cluster: devops-sre
---

Distributed tracing is the capture of a request's lifecycle as it traverses service boundaries. Each segment of work is a **span**, and the entire tree of spans for a single request is the **trace**.

## Core Mechanics

Tracing relies on three pillars: **Propagation**, **Instrumentation**, and **Aggregation**.

### 1. Context Propagation
The `traceparent` header (W3C standard) must be passed between all services. It prevents "trace fragmentation" where a single request appears as disconnected spans.

```
traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01
             ver trace-id (32 hex)             span-id (16 hex)  flags
```

### 2. Instrumentation
The industry standard is **OpenTelemetry (OTel)**. Manual instrumentation is required for business-critical operations that cross multiple async or framework boundaries.

**Concrete Example (Java/OpenTelemetry):**
```java
// Manual span creation for a complex business operation
Span span = tracer.spanBuilder("process-order")
    .setAttribute("order.id", order.getId())
    .setAttribute("customer.tier", customer.getTier())
    .startSpan();

try (Scope scope = span.makeCurrent()) {
    // Perform work...
    validateOrder(order);
} catch (Exception e) {
    span.setStatus(StatusCode.ERROR, "Order validation failed");
    span.recordException(e);
    throw e;
} finally {
    span.end();
}
```

## Sampling Mathematics: The Cost Lever

Tracing generates massive data volumes. At 1,000 requests per second (RPS), with 20 spans per request and 500 bytes per span, the daily uncompressed volume is:
$$
1000 \text{ req/s} \times 20 \text{ spans/req} \times 500 \text{ bytes/span} \times 86400 \text{ s/day} \approx 864 \text{ GB/day}
$$

### Head-based vs. Tail-based Sampling1. **Head-based:** Sampling decision is made at the start of the request (e.g., sample 1%).
   - **Pros:** Low overhead, predictable cost.
   - **Cons:** Misses outliers and rare errors.
2. **Tail-based:** All spans are buffered; the decision is made after the request finishes.
   - **Strategy:** Keep 100% of errors, 100% of slow requests ($>P95$), and 1% of healthy requests.
   - **Math:** If$E$is error rate (2%) and$S$is slow rate (5%), total data kept is$2\% + 5\% + (93\% \times 1\%) = 7.93\%$. This provides$10 \times$ better signal-to-noise than 10% head-based sampling for the same cost.

## What to Span
Do not span every function. Focus on:
- **IO Boundaries:** HTTP, gRPC, DB, Cache, Queue.
- **Critical Path Logic:** Complex calculations or ML inference.
- **Resource Contention:** Lock acquisition/release.

## Common Trace Patterns

| Pattern | Detection | Fix |
|---|---|---|
| **N+1 Queries** | Trace shows many small, sequential DB spans. | Implement batching or joins. |
| **Silent Retries** | Multiple identical child spans for one logical request. | Check retry policy; ensure idempotency. |
| **Clock Skew** | Child span appears to start before parent. | Sync via NTP/PTP; use tracer-specific skew correction. |
| **Gaps in Timeline** | Large time gaps between spans. | Uninstrumented work (CPU/GC) or network latency. |

## Implementation Strategy
1. **Standardize on W3C Trace Context.**
2. **Inject Trace IDs into logs.** Use the log's `trace_id` field to jump from an error log to its trace.
3. **Use a Collector.** Never send traces directly from the app to the backend; use an OpenTelemetry Collector for buffering and tail-sampling.
4. **Choose a Backend:**
   - **Tempo/Jaeger:** For self-hosted/cost-conscious.
   - **Honeycomb:** For high-cardinality exploration.
