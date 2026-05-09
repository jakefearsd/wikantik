---
cluster: design-patterns
canonical_id: 01KQ0P44N9S7WFVJJA94XTNBN7
title: Circuit Breaker Pattern
type: article
tags:
- circuit-breaker
- resilience
- fault-tolerance
- microservices
summary: Technical analysis of the Circuit Breaker pattern for managing cascading failures and resource exhaustion in distributed systems.
auto-generated: false
date: '2026-04-26'
---

The Circuit Breaker pattern is a stability mechanism that prevents a service from repeatedly attempting an operation that is likely to fail. It acts as a stateful proxy between a caller and a failing dependency, protecting local resources (threads, memory) from being exhausted by slow or dead downstream services.

## The Finite State Machine (FSM)

A circuit breaker implements three primary states:

1.  **CLOSED (Success):** Requests pass through normally. The breaker tracks failure rates over a **Sliding Window** (e.g., last 100 requests).
2.  **OPEN (Failure):** The threshold (e.g., 50% failure rate) is exceeded. All requests are rejected immediately with a `CallNotPermittedException`. This gives the dependency time to recover.
3.  **HALF-OPEN (Probing):** After a "Wait Duration," the breaker allows a limited number of trial requests.
    - If they succeed $\rightarrow$ **CLOSED**.
    - If they fail $\rightarrow$ **OPEN** (resets wait duration).

## Library Comparison

| Feature | Resilience4j | Hystrix (Netflix) | Sentinel (Alibaba) |
|---|---|---|---|
| **Status** | Active / Recommended | Maintenance Mode | Active |
| **Threading** | Functional / Decoupled | Thread-pool Isolation | Adaptive Throttling |
| **State Storage** | In-Memory (Atomic) | RxJava Observables | Slot-based Bucket |
| **Complexity** | Low | High | Medium |

## Advanced Strategies

### 1. Adaptive Timeouts
Static timeouts ($2\text{s}$) are often either too long (exhausting threads) or too short (causing false failures). Adaptive timeouts use the $P99$ latency of the last window plus a safety margin to set dynamic thresholds.

### 2. Predictive Tripping
Instead of waiting for a 50% failure rate, predictive breakers monitor the **Derivative of Latency**. If latency is increasing exponentially, the breaker trips early to preempt a total outage.

### 3. Bulkhead Integration
Circuit breakers should be paired with **[BulkheadPattern](BulkheadPattern)** to ensure that a tripped circuit for `Service A` does not starve the thread pool used for `Service B`.

## Implementation Checklist
- **Don't wrap internal calls:** Only wrap calls that cross a network boundary or a process boundary.
- **Log State Transitions:** Alert when a circuit moves to **OPEN**. This is a leading indicator of a downstream incident.
- **Fail Fast, but return Fallbacks:** Where possible, return a cached value or a default response instead of throwing an error to the end user.
- **Test via Chaos Engineering:** Use tools like Gremlin or Chaos Mesh to inject latency and verify the breaker trips as expected.

## Further Reading
- [DistributedSystemsHub](DistributedSystemsHub) — Resilience foundations.
- [MicroservicesArchitecture](MicroservicesArchitecture) — Service mesh pattern context.
- [MonitoringAndAlerting](MonitoringAndAlerting) — Telemetry for state visibility.
