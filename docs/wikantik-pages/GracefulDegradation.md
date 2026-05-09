---
cluster: devops-sre
canonical_id: 01KQ0P44QQ0MHAB159XYZTCW9J
title: Graceful Degradation
type: article
tags:
- software-architecture
- resilience
- reliability
- distributed-systems
status: active
date: 2025-05-15
summary: Technical patterns for maintaining core system functionality during component failure. Covers fallbacks, feature toggles, and circuit breakers.
auto-generated: false
---

# Graceful Degradation: Architectural Resilience

Graceful Degradation is the ability of a system to maintain its core mission-critical functions even when one or more non-essential components fail or are under extreme load.

## 1. The Fallback Hierarchy

When a dependency fails, the system must choose a secondary behavior:
1.  **Cached Fallback:** Return the last known good value from a local cache (e.g., Redis).
    *   *Example:* If the `Personalization` service is down, show a cached list of "Popular Items" instead of a blank page.
2.  **Static Fallback:** Return a hardcoded default.
    *   *Example:* Default to a standard $10.00 shipping fee if the `Dynamic-Shipping-Estimator` times out.
3.  **Stubbed Fallback:** Return an empty set or a "Service Temporarily Unavailable" message for that specific UI widget.

## 2. Circuit Breakers (Fail-Fast)

To prevent a slow dependency from exhausting the entire system's thread pool, use a **Circuit Breaker** (e.g., Resilience4j or Istio).
*   **Closed:** Normal operation.
*   **Open:** After a threshold of errors (e.g., 50% failures), the circuit "opens." Subsequent calls fail immediately without hitting the backend.
*   **Half-Open:** After a cooldown (e.g., 30s), the system allows a few test requests to see if the dependency has recovered.

## 3. Load Shedding and Throttling

When throughput exceeds capacity, the system must prioritize traffic.
*   **Priority Queues:** Ensure "Checkout" requests are processed before "Add to Wishlist" requests.
*   **Throttling:** Return `429 Too Many Requests` to non-critical clients (e.g., internal analytics crawlers) to save capacity for end-users.

## 4. Implementation with Feature Flags

Use [Feature Flags](ClaudeCodeWorkflows) (e.g., LaunchDarkly) to manually or automatically "kill" expensive features during a traffic spike.
*   **Concrete Scenario:** During a Black Friday surge, disable the "Related Products" recommendation engine to reduce database load by 30%, keeping the "Buy Now" button functional.

---
**See Also:**
- [Game Day Exercises](GameDayExercises) — Testing degradation in production.
- [Incident Management](IncidentManagement) — Managing the fallout of component loss.
- [Auto Scaling Strategies](AutoScalingStrategies) — Scaling to avoid degradation.
