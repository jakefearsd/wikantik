---
cluster: devops-sre
canonical_id: 01KQ0P44MXK2X5DVXZ0MT3YVKD
title: Canary Deployments
type: article
tags:
- devops
- deployment
- canary
- traffic-splitting
- reliability
status: active
date: 2025-05-15
summary: Technical guide to Canary deployments. Covers traffic splitting (Istio/NGINX), automated analysis, and rollback triggers.
auto-generated: false
---

# Canary Deployments: Risk Mitigation

A Canary deployment is a strategy where a new version of software is rolled out to a small subset of users before being promoted to the entire infrastructure.

## 1. Traffic Splitting Mechanisms

The core of a canary is the ability to route a precise percentage of traffic.

*   **Layer 4 (IP-based):** Simple, but lacks granularity. Often used at the Load Balancer level (e.g., AWS Target Group weights).
*   **Layer 7 (Request-based):** Superior for modern APIs. Routes based on headers, cookies, or user IDs.
*   **Concrete Example (Istio):** Use a `VirtualService` to route 90% of traffic to the `stable` subset and 10% to the `canary` subset.
    ```yaml
    http:
    - route:
      - destination: {host: my-svc, subset: stable}, weight: 90
      - destination: {host: my-svc, subset: canary}, weight: 10
    ```

## 2. Automated Canary Analysis (ACA)

Manual verification of a canary is slow and error-prone. Use automated gates.
*   **Metrics Comparison:** Compare the **P95 Latency** and **Error Rate** of the canary group against the stable group.
*   **Tools:** Use **Flagger** or **Argo Rollouts**. They automatically increase the traffic weight (e.g., 5% $\to$ 10% $\to$ 50% $\to$ 100%) if metrics remain healthy.
*   **Concrete Trigger:** *Abort the rollout and roll back immediately if the canary error rate exceeds the stable error rate by >2% for a 2-minute window.*

## 3. Data Compatibility (The Hard Part)

If Version 2 requires a database schema change, the canary (V2) and the stable pool (V1) must both function against the same DB.
*   **Rule:** Database migrations must be **Additive and Backwards Compatible**. Never delete or rename columns until the rollout is 100% complete and the old version is decommissioned.

## 4. Sticky Canaries

To prevent a user from flipping between V1 and V2 (which can break UIs), use **Session Affinity**. 
*   **Implementation:** Set a `canary-version` cookie on the first request. The API Gateway or Load Balancer respects this cookie to ensure the user stays on the same version throughout their session.

---
**See Also:**
- [Auto Scaling Strategies](AutoScalingStrategies) — Handling the load shift.
- [Backwards Compatibility Strategies](BackwardsCompatibilityStrategies) — Managing schema drift.
- [Monitoring and Alerting](MonitoringAndAlerting) — The data source for ACA.
