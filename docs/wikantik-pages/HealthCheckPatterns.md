---
cluster: devops-sre
canonical_id: 01KQ0P44QVWKBREPTPKPKX8P6N
title: Health Check Patterns
type: article
tags:
- health-check
- kubernetes
- liveness
- readiness
- reliability
summary: Technical analysis of distributed health checking primitives (Liveness, Readiness, Startup) and adaptive failure detection models.
auto-generated: false
date: '2026-04-26'
---

Health checks are control-plane primitives that allow an orchestrator to manage the lifecycle and traffic-readiness of a containerized process.

## The Probe Triad

Kubernetes implements three distinct probe types to manage the failure domain.

| Probe Type | Question Answered | Failure Action | Failure Context |
|---|---|---|---|
| **Startup** | Is the app still bootstrapping? | Hold off other probes. | Slow migrations, cache warm-up. |
| **Liveness** | Is the process deadlocked? | **Restart Container**. | In-memory corruption, thread deadlock. |
| **Readiness** | Can the app handle traffic? | **Remove from Service**. | Dependency down, saturated IO. |

## Designing the Liveness Probe

The Liveness probe must be **minimalist**. Querying an external database in a liveness probe is an anti-pattern: if the DB is down, all replicas restart simultaneously, inducing a cluster-wide outage.
- **Rule:** Liveness = Process state only.
- **Target:** In-memory heartbeat or local socket check.

## Failure Detection Math: Phi Accrual

Instead of binary "up/down" thresholds, advanced systems (Akka, Cassandra) use the **$\phi$Accrual Failure Detector**. It calculates the probability of failure based on the history of heartbeat inter-arrival times.

**Mathematical Model:**
If$T_{last}$is the time of the last heartbeat,$\phi$is defined as:$$\phi(T_{now}) = -\log_{10}(P(T_{inter-arrival} > T_{now} - T_{last}))$$- **Low$\phi$:** Suspicious, but still routing traffic.
- **High$\phi$($>8$):** Confirmed failure; trigger eviction.

## Operational Risk: The Thundering Herd

A naive health check configuration can induce a "Thundering Herd" during recovery.
1. Service A hits a resource limit and fails Liveness.
2. Kubernetes restarts the container.
3. The load balancer immediately floods the restarting container with traffic before it's ready.
4. The container fails again, entering a **CrashLoopBackOff**.

**Mitigation:**
- **[ReadinessProbe](HealthCheckPatterns):** Ensure the container is fully warmed up before re-entering the rotation.
- **Exponential Backoff:** Kubernetes' native restart backoff protects the cluster, but the application should also implement internal throttling during the "warm" phase.

## Sidecar Pattern for Probes
For complex health logic (e.g., checking multiple internal subsystems), move the logic to a **Health Sidecar**.
- **Data Plane:** Sidecar performs deep inspection.
- **Control Plane:** Kubelet pings the Sidecar via a simple HTTP endpoint.
- **Benefit:** Decouples health-checking overhead from the main application's CPU/Memory limits.

## Implementation Checklist
- **[ ] Idempotency:** The `/healthz` endpoint must not have side effects.
- **[ ] Timeout Synchronization:** Probe `timeoutSeconds` must be shorter than the `periodSeconds`.
- **[ ] Failure Thresholds:** Set `failureThreshold: 3` to absorb transient network jitter.
- **[ ] Deep vs. Shallow:** Readiness = Deep (dependencies); Liveness = Shallow (process).
