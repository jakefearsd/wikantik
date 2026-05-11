---
summary: Index of pages on web service and API design — REST vs. GraphQL vs. RPC,
  the patterns that scale (idempotency, pagination, batching), and the protocols that
  enable real-time communication.
date: '2026-04-26'
cluster: web-services-and-apis
related:
- JavaHub
- FrontendDevelopmentHub
- DevOpsAndSreHub
- NetworkingHub
canonical_id: 01KZHC6PVS4SBQM9R0F3T7K8Z5
type: hub
title: WebServicesAndApisHub
tags:
- api
- web-services
- hub
- REST
- GraphQL
- protocol
status: active
hubs:
- NetworkingHub
- DevOpsAndSreHub
- CloudPlatformsHub
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

- [Java Hub](JavaHub) — Server-side implementation
- [Frontend Development Hub](FrontendDevelopmentHub) — Client-side consumption
- [DevOps and SRE Hub](DevOpsAndSreHub) — API operability concerns
- [Networking Hub](NetworkingHub) — Protocol-level layer below APIs
