---
type: hub
tags:
- networking
- protocol
- hub
- tcp-ip
- http
summary: Index of pages on networking concepts — TCP/IP, DNS, HTTP/2-3, CORS, load
  balancing, and the practical knowledge needed to design and troubleshoot networked
  systems.
status: active
date: '2026-04-26'
title: NetworkingHub
related:
- WebServicesAndApisHub
- CloudPlatformsHub
- DevOpsAndSreHub
canonical_id: 01KZHC6PVY4SBQM9R0F3T7K8ZB
cluster: networking
---
# Networking Hub

This cluster covers the networking layer of distributed systems — protocols, routing, security, and the practical knowledge needed to design and troubleshoot networked systems at the application level.

## Foundational protocols

- [TcpIpFundamentals](TcpIpFundamentals) — TCP vs. UDP, the OSI model in practice, the parts that actually matter
- [DnsDeepDive](DnsDeepDive) — Resolution, caching, edge cases, the operational pain points
- [HttpTwoAndHttpThree](HttpTwoAndHttpThree) — Multiplexing, server push, QUIC, what changed

## Application-level concerns

- [CorsDeepDive](CorsDeepDive) — The CORS preflight, headers, common configuration errors
- [LoadBalancingStrategies](LoadBalancingStrategies) — Round-robin, least-conn, sticky sessions, when each fits
- [ReverseProxyPatterns](ReverseProxyPatterns) — Nginx, Envoy, what they do beyond load balancing
- [API Gateway Patterns](ApiGatewayPatterns) — Routing, AuthN, and Aggregation at the network edge

## Operations

- [NetworkTroubleshooting](NetworkTroubleshooting) — `traceroute`, `dig`, `tcpdump`, the diagnostic toolkit

## Adjacent clusters

- [Web Services and APIs Hub](WebServicesAndApisHub) — Application protocols above networking
- [Cloud Platforms Hub](CloudPlatformsHub) — Cloud networking specifics (VPC, security groups)
- [DevOps and SRE Hub](DevOpsAndSreHub) — Operations and on-call concerns
