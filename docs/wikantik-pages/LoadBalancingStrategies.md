---
canonical_id: 01KQ0P44S0ZHBGACMEN6RJXJHE
title: Load Balancing Strategies
type: article
cluster: networking
status: active
date: '2026-04-26'
summary: How load balancers work — algorithms (round-robin, least-connections, etc.),
  the L4 vs. L7 distinction, sticky sessions, health checks, and the patterns that
  scale.
tags:
- load-balancing
- networking
- scaling
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
# Load Balancing Strategies

Load balancers distribute traffic across many backend servers. Done well, they enable horizontal scaling and graceful failure handling. Done poorly, they're bottlenecks or single points of failure.

This page covers the algorithms, the L4/L7 distinction, and the operational patterns.

## L4 vs. L7

The big architectural choice.

### L4 (transport layer)

The load balancer routes TCP connections without inspecting their contents.

Examples: AWS NLB, HAProxy in TCP mode, IPVS.

Pros:
- Very fast (no parsing)
- Protocol-agnostic (works for any TCP)
- Can preserve source IP

Cons:
- Can't make routing decisions based on content (path, headers)
- Limited features

When to use: high-throughput TCP traffic, non-HTTP protocols.

### L7 (application layer)

The load balancer parses HTTP (or other application protocol) and routes based on content.

Examples: AWS ALB, Nginx, HAProxy in HTTP mode, Envoy.

Pros:
- Path-based routing (`/api → service A`, `/static → service B`)
- Header-based routing
- TLS termination
- Request modification (rewrite, inject headers)

Cons:
- More CPU (parsing HTTP)
- Bigger surface area
- Application-protocol-specific

For HTTP traffic, L7 is usually right. The features (path routing, TLS termination, request manipulation) are valuable.

## Algorithms

The algorithm picks which backend gets the next request.

### Round-robin

Request 1 → backend A
Request 2 → backend B
Request 3 → backend C
Request 4 → backend A
...

Simple. Even distribution if backends are similar.

### Least connections

Picks the backend with fewest active connections. Better when requests have varying durations — slow requests don't pile up on one backend.

### Random

Random pick. Surprisingly effective; avoids worst-case patterns of round-robin.

### Weighted

Backends have weights (representing capacity). Bigger backends get proportionally more requests.

Useful for gradual rollouts (new version starts with low weight) and heterogeneous fleets.

### Hash-based / consistent hashing

Hash of request key (URL, header, etc.) determines backend. Same input → same backend. Used for cache affinity.

Consistent hashing minimizes redistribution when backends are added/removed.

### IP hash

Hash of client IP. All requests from same client go to same backend. Provides stickiness without explicit sessions.

## Health checks

The load balancer probes each backend; unhealthy backends are removed from rotation.

### Health check configuration

- **Path**: `/health` or `/healthz`. Simple endpoint that returns 200 if the service is OK.
- **Interval**: every 5-30 seconds typically
- **Threshold**: N consecutive failures before marking unhealthy

Health endpoint should:
- Return quickly (just a status check, not a full operation)
- Reflect actual readiness (database connection works, etc.)
- Not cause cascading load (don't make health a heavy operation)

### Liveness vs. readiness

In Kubernetes terminology:
- **Liveness**: is the process alive? Fail = restart it.
- **Readiness**: is the process ready to serve? Fail = remove from load balancer.

Different timeouts and behaviors. The distinction matters.

## Sticky sessions

Some applications need the same user's requests to go to the same backend (in-memory sessions). Two approaches:

### Cookie-based

Load balancer issues a cookie naming the backend. Subsequent requests with the cookie route there.

### IP-based

Hash of client IP. Brittle — multiple users behind one IP, mobile users changing IPs.

### When to use

Stateful applications that need to be sticky. The right answer is usually to fix the application (move state to a shared store), but stickiness is the bridge.

## TLS termination

L7 load balancers usually terminate TLS — decrypt at the load balancer; forward HTTP to backends.

Pros:
- Centralized cert management
- Backends don't need TLS
- Load balancer can inspect and route based on content

Cons:
- Traffic between load balancer and backends is plaintext (use private network or re-encrypt)
- Cert management is critical

For most public-facing traffic, terminate at load balancer.

## Specific load balancers

### AWS ALB / NLB / GLB

Managed AWS services. ALB is L7 (HTTP); NLB is L4. GLB (Gateway) is for VPN/firewall integration.

Pros: managed; integrates with AWS.
Cons: AWS-specific; can be expensive at high volume.

### Nginx

The dominant open-source load balancer / reverse proxy. Configurable; widely understood.

### HAProxy

Excellent for L4 and L7. More technical configuration than Nginx; more features at scale.

### Envoy

Modern; designed for cloud-native. Used by Istio, Consul, AWS App Mesh. More complex than Nginx; more features.

### F5, Citrix

Hardware load balancers. Enterprise-focused. Expensive but feature-rich.

## Patterns

### Internal load balancer

For service-to-service traffic within a VPC. Not internet-facing.

### External load balancer

Public-facing. Receives internet traffic; routes to internal services.

### Load balancer per service vs. shared

For microservices, the question. Per-service: isolation, independent scaling. Shared: lower cost; better for path-based routing.

### Multi-region load balancing

DNS-level routing (Route 53, GCP Cloud Load Balancing global). Routes users to nearest region.

## Common failure patterns

- **No health checks.** Dead backends still receive traffic.
- **Aggressive health checks.** False positives remove healthy backends.
- **Backend can't drain on shutdown.** In-flight requests cut off.
- **Health endpoint overlaps with auth.** Auth fails for health checks.
- **Sticky sessions when state should be shared.** Limits horizontal scaling.
- **Load balancer as single point of failure.** Use redundant LBs (or managed service).

## Further Reading

- [TcpIpFundamentals](TcpIpFundamentals) — TCP under the LB
- [ReverseProxyPatterns](ReverseProxyPatterns) — Adjacent role
- [DnsDeepDive](DnsDeepDive) — DNS as LB
- [CdnArchitecture](CdnArchitecture) — Edge LB role
- [Networking Hub](NetworkingHub) — Cluster index
