---
canonical_id: 01KQ12YDSXRNMGVVBHNMP885XP
title: Chaos Engineering
type: article
cluster: software-architecture
status: active
date: '2026-05-24'
tags:
- chaos-engineering
- reliability
- resilience-testing
- saps
summary: Scientific approach to system reliability through controlled fault injection. Covers blast radius management, hypothesis-driven testing, and the "Game Day" discipline.
auto-generated: false
---
# Chaos Engineering

Chaos Engineering is the discipline of experimenting on a system in order to build confidence in the system's capability to withstand turbulent conditions in production. It is not "breaking things in prod"; it is a **scientific experiment** to verify resilience hypotheses.

## The Four Steps of a Chaos Experiment

1. **Define the Steady State:** Identify a measurable metric that indicates the system is healthy (e.g., "p99 latency < 200ms" or "HTTP 200 rate > 99.9%").
2. **Form a Hypothesis:** "If we kill one of the three database replicas, the steady state will not change."
3. **Introduce a Variable (The Fault):** Inject a failure (e.g., terminate a node, inject 500ms of network latency).
4. **Try to Disprove the Hypothesis:** If the steady state is affected, you have found a resilience gap.

## Blast Radius Management

Never start with a "Chaos Monkey" that kills random production nodes. Use the **Blast Radius** progression:
- **Dev/Stage:** Break it here first. If it fails, fix the architecture.
- **Canary:** Break it for 1% of users.
- **Production:** Break it for everyone, but only after it has passed the Canary test.

## Common Chaos Experiments

| Target | Fault | Hypothesis |
|---|---|---|
| **Network** | Latency Injection | "The circuit breaker will trip and fall back to cache." |
| **Storage** | Disk Full | "The application will gracefully degrade to read-only mode." |
| **Compute** | CPU Hog / OOM | "The load balancer will health-check the node out of rotation." |
| **DNS** | Resolve Failure | "The secondary DNS provider will take over automatically." |

## Implementation: Chaos Mesh (Kubernetes)

For teams on K8s, **Chaos Mesh** is the industry standard. It allows you to inject faults via CRDs (Custom Resource Definitions) without changing application code.

```yaml
# Example: Network latency injection
apiVersion: chaos-mesh.org/v1alpha1
kind: NetworkChaos
metadata:
  name: network-delay
spec:
  action: delay
  mode: one
  selector:
    namespaces:
      - default
    labelSelectors:
      'app': 'my-web-app'
  delay:
    latency: '200ms'
    jitter: '50ms'
  duration: '5m'
```

## The "Game Day" Discipline

A Game Day is a scheduled 2-4 hour window where the engineering team runs a series of chaos experiments. 
- **Roles:** One person is the "Chaos Engineer" (injects faults); one is the "Incident Commander" (responds); one is the "Scribe" (records timestamps and metrics).
- **Goal:** Not just to find technical bugs, but to test the **Human Response**. Does the alert fire? Is the runbook accurate? Does the team know where the dashboard is?

## Anti-Pattern: Chaos without Observability
If you inject a fault and your dashboards don't show any change—but your users are complaining on Twitter—you have a **Blind Spot**. Chaos Engineering is as much about testing your monitoring as it is about testing your code.

## Further Reading
- [BlamelessPostMortems](BlamelessPostMortems) — Documenting the findings from a Game Day.
- [ServiceLevelAgreements](ServiceLevelAgreements) — Defining the "Steady State" metrics.
- [CircuitBreakerPattern](CircuitBreakerPattern) — The primary defense against cascading failures.
