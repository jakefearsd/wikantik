---
canonical_id: 01KRPNNV4JYMQDCP8999FDTY6A
title: Load Testing Strategies
tags:
- load-testing
- performance
- sre
- capacity-planning
- gatling
- k6
cluster: devops-sre
type: article
date: '2026-05-15'
status: active
summary: Comprehensive strategies for load testing distributed systems, covering open
  vs. closed workload models, coordinated omission, and capacity planning.
---

# Load Testing Strategies

In distributed systems engineering, **Load Testing** is the empirical process of subjecting a system to anticipated peak operational volume to observe its behavior, identify latency bottlenecks, and validate capacity planning.

## 1. Workload Models: Open vs. Closed

A fundamental error in load testing is selecting the wrong workload model.

### Closed Workload Model
In a closed system, a fixed number of concurrent virtual users (threads) loops through requests. A new request is only sent *after* the previous one completes. 
*   **The Trap:** If the server slows down, the test tool slows down. The load drops precisely when the system is under stress, masking the true failure point.
*   **Use Case:** Validating connection pooling limits or testing legacy synchronous systems.

### Open Workload Model
In an open system, requests arrive at a predefined arrival rate (e.g., 500 requests per second), regardless of how fast the server processes them.
*   **The Benefit:** Accurately simulates internet traffic. If the server slows down, requests queue up, realistically exposing thread exhaustion, memory leaks, and cascading failures.
*   **Tools:** Modern tools like [k6](https://k6.io/) and Gatling excel at generating open workloads.

## 2. The Danger of Coordinated Omission

**Coordinated Omission** occurs when a load testing tool inadvertently coordinates with the system under test to omit latency spikes from its measurements.

If a test tool expects to send a request every 10ms, but the server pauses for 100ms (e.g., during a Garbage Collection pause), the tool might silently skip sending the 9 requests that should have occurred during that pause. The resulting report will completely hide the 100ms latency spike, presenting a falsely optimistic 99th percentile (p99) latency.

To mitigate this, SREs must use testing tools engineered to correct for coordinated omission (like `wrk2` or hyperfoil) and rely on server-side metrics (Prometheus, OpenTelemetry) rather than client-side aggregates.

## 3. Types of Performance Tests

*   **Load Testing:** Assessing behavior at expected peak volume.
*   **Stress Testing:** Pushing the system beyond its limits to observe its failure modes and recovery gracefulness.
*   **Soak (Endurance) Testing:** Running a moderate load for an extended duration (hours or days) to detect memory leaks and resource exhaustion.
*   **Spike Testing:** Simulating a sudden, massive influx of traffic (e.g., a Black Friday sale) to test auto-scaling and circuit breakers.
