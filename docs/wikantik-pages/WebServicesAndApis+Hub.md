---
canonical_id: 01KZHC6PVS4SBQM9R0F3T7K8Z5
title: WebServicesAndApis Hub
type: hub
cluster: web-services-and-apis
status: active
date: '2026-04-26'
summary: Index of pages on web service and API design — REST vs. GraphQL vs. RPC,
  the patterns that scale (idempotency, pagination, batching), and the protocols that
  enable real-time communication.
tags:
- api
- web-services
- hub
- REST
- GraphQL
- protocol
related:
- Java+Hub
- FrontendDevelopment+Hub
- DevOpsAndSre+Hub
- Networking+Hub
---
# WebServicesAndApis Hub

This cluster covers the patterns and protocols for service-to-service and client-server communication on the web. The orientation is design — what each pattern is for, when to use it, and the failure modes that catch teams that pick the wrong tool.

## Protocol comparison

- [ApiProtocolComparison](ApiProtocolComparison) — REST vs. GraphQL vs. gRPC vs. SOAP: when each is right
- [GraphQlFundamentals](GraphQlFundamentals) — Schema, queries, mutations, the N+1 problem
- [HateoasAndHypermediaApis](HateoasAndHypermediaApis) — Why HATEOAS sounded good and rarely shipped
- [HttpTwoAndHttpThree](HttpTwoAndHttpThree) — Multiplexing, server push, QUIC, and what changed

## Patterns that scale

- [IdempotencyPatterns](IdempotencyPatterns) — Idempotency keys, the right level of granularity
- [PaginationStrategies](PaginationStrategies) — Offset vs. cursor, ordering stability, edge cases
- [BatchApiDesign](BatchApiDesign) — Batch endpoints, partial failure handling
- [FileUploadPatterns](FileUploadPatterns) — Multipart, signed URLs, resumable uploads

## Real-time communication

- [WebSocketPatterns](WebSocketPatterns) — Connection management, backpressure, fallback strategies
- [ServerSentEventsPatterns](ServerSentEventsPatterns) — When SSE beats WebSockets
- [WebhookPatterns](WebhookPatterns) — Delivery guarantees, retry, signature verification

## Adjacent clusters

- [Java Hub](Java+Hub) — Server-side implementation
- [Frontend Development Hub](FrontendDevelopment+Hub) — Client-side consumption
- [DevOps and SRE Hub](DevOpsAndSre+Hub) — API operability concerns
- [Networking Hub](Networking+Hub) — Protocol-level layer below APIs
