---
canonical_id: 01KQ0P44S0ZHBGACMEN6RJXJHE
title: Load Balancing Strategies
type: article
cluster: networking
status: active
date: '2026-04-26'
summary: Technical deep-dive into L4/L7 load balancing, health check mechanics, and consistent hashing algorithms for distributed systems.
auto-generated: false
tags:
- load-balancing
- networking
- consistent-hashing
- nginx
- haproxy
related:
- TcpIpFundamentals
- ReverseProxyPatterns
- DnsDeepDive
- CdnArchitecture
hubs:
- NetworkingHub
---

Load balancing is the distribution of network traffic across a pool of backend resources. It is implemented at either the Transport Layer (L4) or the Application Layer (L7).

## L4 vs. L7 Load Balancing

| Feature | L4 (Transport) | L7 (Application) |
|---|---|---|
| **OSI Layer** | Layer 4 (TCP/UDP) | Layer 7 (HTTP/gRPC/TLS) |
| **Visibility** | IP, Port, Protocol | Path, Headers, Cookies, Body |
| **Performance** | High (No packet inspection) | Lower (Parsing overhead) |
| **Features** | Simple routing, NAT | Content-based routing, TLS termination |
| **Example** | AWS NLB, IPVS, HAProxy (TCP) | AWS ALB, Nginx, Envoy |

## Selection Algorithms

1. **Round Robin:** Sequential assignment. Best for homogeneous backend capacity.
2. **Least Connections:** Assigns to the node with the fewest active sessions. Best for long-lived connections (e.g., WebSockets).
3. **Consistent Hashing:** Maps requests to nodes using a hash ring.
   - **Math:** A request with key $k$ is assigned to node $n = \text{argmin}_{i} (\text{hash}(n_i) \geq \text{hash}(k))$.
   - **Benefit:** Minimizes cache invalidation when nodes join/leave; only $1/N$ of keys are remapped.

## Health Check Mechanics

A load balancer must proactively prune unhealthy nodes from its rotation.
- **Liveness:** Is the process running? (e.g., TCP port open).
- **Readiness:** Is the application ready to serve? (e.g., `/healthz` returns 200 after cache warm-up).

**Failure Thresholds:**
- `interval`: Time between probes (e.g., 5s).
- `unhealthy_threshold`: Consecutive failures before removal (e.g., 3).
- `healthy_threshold`: Consecutive successes before re-entry (e.g., 2).

## Advanced Patterns

### 1. TLS Termination vs. Passthrough
- **Termination:** Decrypt at the LB. Reduces CPU load on backends and centralizes certificate management.
- **Passthrough:** Forward encrypted packets. Required for end-to-end encryption (e.g., HIPAA compliance).

### 2. Draining (Graceful Shutdown)
When a node is marked for removal, the LB stops sending *new* requests but allows *in-flight* requests to complete before closing the connection. Mandatory for zero-downtime deployments.

### 3. Sticky Sessions (Session Affinity)
Ensures a client is routed to the same backend for the duration of a session.
- **Mechanism:** Injected cookies (L7) or Client IP hashing (L4).
- **Anti-pattern:** Use shared state (Redis/DB) instead of sticky sessions to enable better horizontal scaling.

## Common Failure Modes
- **Aggressive Probing:** Health checks consuming significant backend CPU/bandwidth.
- **Zombie Backends:** No health checks configured, leading to black-holed traffic when nodes crash.
- **Herding Effect:** When a node returns to the pool, the "Least Connections" algorithm floods it with traffic, causing an immediate re-failure. Use **Slow Start** ramps.
