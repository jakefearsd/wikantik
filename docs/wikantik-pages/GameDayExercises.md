---
cluster: devops-sre
canonical_id: 01KQ0P44QMRSV4SWPS2PVT49F7
title: Game Day Exercises
type: article
tags:
- devops
- sre
- chaos-engineering
- resilience
- incident-management
status: active
date: 2025-05-15
summary: Technical guide to running Game Day exercises for resilience testing. Covers hypothesis-driven failure injection and observability validation.
auto-generated: false
---

# Game Day Exercises: Resilience Engineering

A "Game Day" is a structured, collaborative exercise where teams inject controlled failures into a system to validate its resilience, observability, and incident response protocols.

## 1. The Hypothesis-Driven Model

Chaos engineering is not random; it is scientific. Every test must start with a steady-state hypothesis.
*   **Hypothesis Template:** *"If we [Action], the system will [Expected Outcome], as measured by [Metric]."*
*   **Concrete Example:** *"If we inject 500ms of latency into the `User-Auth` service, the `Gateway` will trigger its circuit breaker within 5 seconds, and the end-user will receive a cached response, maintaining a P99 latency < 1s."*

## 2. Common Failure Scenarios

| Failure Vector | Mechanism | Objective |
| :--- | :--- | :--- |
| **Network Latency** | `tc qdisc` / Service Mesh | Test timeouts and circuit breakers. |
| **Process Kill** | `SIGKILL` | Test auto-healing (Kubernetes restarts). |
| **Resource Starvation** | `stress-ng` (CPU/RAM) | Test horizontal pod autoscaling (HPA). |
| **Dependency Loss** | Block outbound IP | Test graceful degradation (fallbacks). |

## 3. Game Day Execution Protocol

1.  **Define Blast Radius:** Limit the test to a specific subset of users, a single availability zone, or a staging environment that mirrors production.
2.  **Observability Baseline:** Ensure dashboards are active. If you can't see the failure in your metrics, the test has already failed its primary goal (validating observability).
3.  **The "Big Red Button":** Have a pre-scripted, immediate rollback command (e.g., `kubectl delete networkpolicy block-db`).
4.  **Post-Mortem:** Document the "Time to Detect" and "Time to Recovery." Identify gaps in the runbook or code (e.g., "The fallback was triggered, but it pointed to an empty cache").

## 4. Why Game Days? (The Human Factor)

Beyond the code, Game Days train the **On-Call Engineer**.
*   **Muscle Memory:** Reduces panic during real incidents by having navigated the failure before.
*   **Runbook Validation:** Proves that the "standard procedure" actually works in practice, not just in theory.

---
**See Also:**
- [Graceful Degradation](GracefulDegradation) — Implementing the fallbacks being tested.
- [Incident Management](IncidentManagement) — The process surrounding real failures.
- [Auto Scaling Strategies](AutoScalingStrategies) — Handling the load during starvation.
