---
canonical_id: 01KQ0P44PVGNG31V5A3E2K29Z6
title: DNS Deep Dive
type: article
cluster: networking
status: active
date: '2026-04-26'
summary: How DNS actually works — resolution, caching, TTL, the operational pain
  points, and the patterns for using DNS reliably in production systems.
tags:
- dns
- networking
- operations
- caching
related:
- TcpIpFundamentals
- LoadBalancingStrategies
- CdnArchitecture
- NetworkTroubleshooting
hubs:
- Networking Hub
---
# DNS Deep Dive

DNS translates names to addresses. The conceptual model is simple; the operational reality is full of edge cases. DNS issues are a frequent source of production problems — slow lookups, stale caches, propagation delays, DNS-server failures.

This page covers the parts that matter for application engineers.

## How resolution works

When a process needs to resolve `example.com`:

1. **Local cache**: OS or process-level cache. Fast.
2. **Stub resolver** in the OS sends UDP query to configured DNS server.
3. **Recursive resolver** (your ISP, 8.8.8.8, etc.) does the actual work:
   - Queries root nameservers
   - Queries TLD nameservers (.com)
   - Queries authoritative nameservers for example.com
   - Returns answer
4. **Response cached** at multiple levels with TTL.
5. **Address returned** to your process.

The recursive resolver does most of the heavy work. The stub on your machine is simple.

## Record types

Common types:

- **A**: IPv4 address
- **AAAA**: IPv6 address
- **CNAME**: alias to another name
- **MX**: mail exchanger
- **TXT**: arbitrary text (used for verification, SPF, etc.)
- **NS**: nameserver delegation
- **SOA**: zone metadata
- **SRV**: service location (port, weight, priority)

For application code, A/AAAA is dominant. CNAME is common for "point to managed service" patterns (CloudFront distribution, ALB).

## Caching and TTL

Every DNS response includes a TTL. Resolvers cache for that duration.

Common TTLs:
- **60 seconds**: very low; for rapidly-changing resources
- **300 seconds**: low; common default
- **3600 seconds (1 hour)**: typical
- **86400 seconds (1 day)**: long; common for stable records

TTL trade-off:
- **Shorter**: changes propagate fast; more queries; more load on resolvers
- **Longer**: less load; changes are slow

When planning a change, lower the TTL well in advance (1 day before), make the change, raise the TTL after.

## Propagation

"DNS propagation" is the time for changes to be visible everywhere. Determined by:

- Authoritative nameserver TTL (you control)
- Recursive resolver caching (you don't directly control)
- Stub resolver caching (varies by OS/app)

You can lower TTL in advance to bound propagation. Once the new value is published, all caches expire within their TTL and pick up the new value.

The "DNS takes 24-48 hours to propagate" advice exists because:
1. Some old records had 24-hour TTLs
2. Resolver caching is sometimes longer than TTL says
3. Some resolvers misbehave

For most modern setups with reasonable TTLs, propagation is minutes, not days.

## DNS in cloud

Cloud providers offer managed DNS:

- **Route 53** (AWS): full-featured; integrated with cloud services
- **Cloud DNS** (GCP)
- **Azure DNS**

Features beyond basic resolution:
- **Health checks**: don't return endpoints that are down
- **Geo-routing**: different answers based on querier location
- **Latency-based routing**: route to closest region
- **Weighted routing**: A/B testing or gradual rollouts
- **Failover routing**: primary/secondary

These are useful for managing global services without per-region client logic.

## DNS as load balancing

Common pattern: DNS returns multiple A records; clients pick one.

```
example.com → 10.0.0.1, 10.0.0.2, 10.0.0.3
```

Pros:
- Simple
- Built-in distributed load balancing
- Survives DNS server failure (after caching)

Cons:
- Slow failover (TTL determines)
- Client-side picking is uneven
- No health awareness without DNS provider features

For traffic distribution within a data center, dedicated load balancers are better. DNS load balancing is for cross-region or cross-data-center routing.

## Common DNS issues

### Stale cache

Application has cached IP that's no longer valid. Causes:
- Long TTL respected too long
- Application-level DNS caching not respecting TTL
- Connection-pool reusing old IP

For long-running connections to a hostname that may move (managed databases, cloud services), build in periodic re-resolution.

### Lookups blocking

DNS resolution is synchronous. A slow DNS server makes every connection slow.

In Java: `InetAddress.getByName()` blocks. Use a connection pool or async resolution.

### IPv6 vs. IPv4

If a hostname has both A and AAAA records, the application picks one. Some networks have broken IPv6; the application falls back to IPv4 after timeout. The fallback adds latency.

Modern apps use "happy eyeballs" — try both simultaneously, pick the first to succeed.

### DNSSEC

DNS responses are not authenticated by default. DNSSEC provides cryptographic signing. Adoption is partial; most public DNS doesn't use it. Server-to-server within infrastructure usually skips DNSSEC.

### CNAME at apex

The DNS spec doesn't allow CNAME at the apex of a domain (`example.com`, not `www.example.com`). Some providers offer "ALIAS" or "ANAME" records that simulate this; behavior varies.

For services hosted at apex pointing to cloud-managed endpoints, this can be awkward.

## Common failure patterns

- **TTL too long for needs.** Slow change.
- **TTL too short.** Excessive query load.
- **Application caching DNS forever.** Missing changes.
- **Single DNS provider.** DNS provider outage = your services down.
- **Synchronous DNS in performance-critical paths.** Latency from resolution.
- **Hardcoded IP addresses.** Defeats DNS; harder to change.

## Further Reading

- [TcpIpFundamentals](TcpIpFundamentals) — Below DNS
- [LoadBalancingStrategies](LoadBalancingStrategies) — DNS as a load balancing layer
- [CdnArchitecture](CdnArchitecture) — DNS-based traffic routing
- [NetworkTroubleshooting](NetworkTroubleshooting) — `dig`, `nslookup`, etc.
- [Networking Hub](Networking+Hub) — Cluster index
