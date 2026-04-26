---
title: Grpc Fundamentals
type: article
cluster: software-architecture
status: active
date: '2026-04-25'
tags:
- grpc
- protobuf
- rpc
- microservices
- streaming
summary: gRPC for inter-service communication — when to pick it over REST,
  the four streaming modes, the things Protobuf forces you to do right,
  and the operational pitfalls.
related:
- ApiDesignBestPractices
- MicroservicesArchitecture
- SchemaRegistryAndEvolution
hubs:
- SoftwareArchitecture Hub
---
# gRPC Fundamentals

gRPC is a remote-procedure-call framework on top of HTTP/2, with Protobuf as the schema and serialisation language. It's fast, strongly typed, and supports four streaming modes that REST doesn't natively. It's also more operationally finicky than REST and worse at supporting browsers.

This page is when to pick it, the gotchas, and the patterns that make it work in production.

## When gRPC wins

- **Inter-service communication** in polyglot environments. Generated clients in every major language; strong type guarantees.
- **High-throughput, low-latency** internal calls. HTTP/2 multiplexing, binary serialisation, header compression — measurably faster than JSON over HTTP/1.1.
- **Streaming**. Server-streaming, client-streaming, bidirectional streaming. Native; not bolted on.
- **Strong contracts**. Protobuf-defined interfaces; can't accidentally break consumers.
- **Auto-generated clients**. Less hand-rolled HTTP-client code.

## When REST wins

- **Public-facing APIs.** Browsers can't speak gRPC natively (gRPC-Web is a workaround but limited). Mobile apps can but often have better tooling for REST.
- **Easy human inspection.** `curl` against a gRPC endpoint is awkward; against REST, trivial.
- **Caching.** HTTP semantics give you ETags, Cache-Control, intermediate caches. gRPC has none of this natively.
- **Simpler debugging.** Wireshark / browser devtools / proxy logs all understand REST; gRPC needs specialised tooling.
- **Lower setup cost** for simple services.

In practice: REST for external / public APIs; gRPC for internal service-to-service. The split is consistent across most production architectures.

## The four streaming modes

```proto
service OrderService {
  // Unary: one request, one response (like REST)
  rpc GetOrder(GetOrderRequest) returns (Order);
  
  // Server streaming: one request, stream of responses
  rpc StreamOrders(GetOrdersRequest) returns (stream Order);
  
  // Client streaming: stream of requests, one response
  rpc UploadEvents(stream Event) returns (UploadResult);
  
  // Bidirectional: both ways
  rpc Chat(stream ChatMessage) returns (stream ChatMessage);
}
```

Common uses:

- **Unary** — most calls. The default.
- **Server streaming** — long-running results, log tails, real-time updates, server-sent-events equivalents.
- **Client streaming** — uploading large data in chunks, batching events.
- **Bidirectional** — chat, real-time collaboration, control protocols.

For services that don't need streaming, unary is enough. The streaming modes are powerful but operationally trickier (long-lived connections, partial failures).

## The Protobuf contract

A proto file defines services, methods, and messages:

```proto
syntax = "proto3";
package com.example.orders;

message Order {
  string id = 1;
  string user_id = 2;
  Money total = 3;
  OrderStatus status = 4;
  google.protobuf.Timestamp created_at = 5;
}

message Money {
  string currency = 1;
  int64 amount_cents = 2;
}

enum OrderStatus {
  ORDER_STATUS_UNSPECIFIED = 0;
  ORDER_STATUS_PENDING = 1;
  ORDER_STATUS_CONFIRMED = 2;
  ORDER_STATUS_SHIPPED = 3;
  ORDER_STATUS_DELIVERED = 4;
}

service OrderService {
  rpc GetOrder(GetOrderRequest) returns (Order);
  rpc ListOrders(ListOrdersRequest) returns (ListOrdersResponse);
}
```

Code generation produces server stubs and client libraries in target languages. The proto file is the source of truth; everything else is generated.

## Field-numbering discipline (the load-bearing convention)

Field numbers identify fields on the wire. Once assigned, never:

- Reuse a number after removing a field. Reserve it.
- Renumber fields. Adds and removes only.
- Change a field's type incompatibly.

```proto
message User {
  reserved 5;  // was 'phone', removed in v2.3
  reserved "phone";
  
  string id = 1;
  string email = 2;
  string name = 3;
  // 4 was previously something; reserve if you removed it
  reserved 4;
  string country = 6;
}
```

Field-number errors corrupt data silently. The reserve discipline is non-negotiable.

## Common patterns

### Pagination

```proto
message ListOrdersRequest {
  string user_id = 1;
  int32 page_size = 2;
  string page_token = 3;
}

message ListOrdersResponse {
  repeated Order orders = 1;
  string next_page_token = 2;
}
```

Cursor-based pagination using opaque tokens. Standard pattern.

### Error handling

gRPC has its own status codes (different from HTTP). Common ones:

- `OK` — success.
- `INVALID_ARGUMENT` — client error in input.
- `NOT_FOUND` — resource doesn't exist.
- `PERMISSION_DENIED` — auth issue.
- `UNAUTHENTICATED` — no/bad credentials.
- `RESOURCE_EXHAUSTED` — quota exceeded.
- `FAILED_PRECONDITION` — system in wrong state for this operation.
- `INTERNAL` — server error.
- `UNAVAILABLE` — service temporarily down.
- `DEADLINE_EXCEEDED` — timeout.

Map these correctly; clients depend on the status to decide retry vs surface.

For richer error info, attach `google.rpc.Status` with details (a message detailing what went wrong, which field, what to do).

### Authentication

Two layers:

- **Per-call metadata** — typically a Bearer token in the `authorization` header.
- **mTLS** — for service-to-service with mutual cert verification.

Both are common. mTLS is the default for service mesh; bearer tokens for user-context calls.

### Retries

gRPC's retry policy is configured per service:

```json
{
  "retryPolicy": {
    "maxAttempts": 4,
    "initialBackoff": "1s",
    "maxBackoff": "10s",
    "backoffMultiplier": 2,
    "retryableStatusCodes": ["UNAVAILABLE", "DEADLINE_EXCEEDED"]
  }
}
```

Idempotency: only retry idempotent operations. POST-equivalent calls must be idempotent if you retry; otherwise duplicate side effects.

### Deadlines

gRPC supports per-call deadlines: "this call must complete within X seconds." Propagate the remaining deadline through dependent calls so a slow downstream doesn't blow your overall budget.

This is the equivalent of context.Context in Go; standard discipline.

## The browser problem

Browsers can't speak gRPC natively because they don't expose HTTP/2 trailers (which gRPC uses for status). Workarounds:

- **gRPC-Web** — a modified protocol with trailers in the body. Requires a translating proxy (Envoy, gRPC-Web Go server). Limited (no client streaming, limited bidirectional).
- **Connect-go / Connect-web** — alternative protocol, gRPC-compatible but designed for the browser. Buf's framework. Increasingly the right answer for browser use.
- **REST-ify the gRPC** — define HTTP mappings in proto; use grpc-gateway to expose REST endpoints alongside gRPC.

For mobile, gRPC clients work natively (iOS, Android). For browsers in 2026, Connect is the smoothest path.

## Operational concerns

### Schema evolution

Use a registry (Buf, Apicurio) and compatibility checks. See [SchemaRegistryAndEvolution]. Without it, breaking changes ship silently.

### Observability

gRPC interceptors are the equivalent of HTTP middleware. Use them for:

- Tracing (OpenTelemetry interceptors exist for every major language).
- Logging.
- Metrics (per-method latency, error rate).
- Authentication.
- Rate limiting.

Without these, gRPC services are harder to debug than REST.

### Load balancing

gRPC over HTTP/2 multiplexes many calls on one connection. This breaks naive layer-4 load balancing — all calls from one client go to one server.

Solutions:

- **Layer-7 load balancing** (Envoy, NGINX with HTTP/2 support, gRPC-aware). Routes per-call.
- **Client-side load balancing** (gRPC's native LB; resolve a list of servers; round-robin or weighted).
- **Service mesh** (Istio, Linkerd) — handles this for you.

Misconfigured load balancing produces "all traffic goes to one pod" surprises. Usually surfaces under load.

### TLS termination

Service mesh (Linkerd, Istio) typically handles mTLS at the sidecar level. Without a mesh, configure TLS in the gRPC server explicitly.

Don't expose gRPC over plain HTTP/2 in production. Always TLS.

## Tooling

- **Buf CLI / Buf Schema Registry** — modern Protobuf workflow; schema linting; breaking-change detection. Use it.
- **`grpcurl`** — command-line gRPC client; like curl for gRPC.
- **`grpcui`** — web UI for gRPC services.
- **BloomRPC** (deprecated) / Postman / Bruno — GUI clients; varying gRPC support.
- **Wireshark** with gRPC dissector — for wire-level debugging.

## Migration patterns

If you're moving from REST to gRPC for internal service-to-service:

1. **Define proto files** for existing endpoints.
2. **Generate gRPC servers** alongside existing REST.
3. **Migrate clients** one at a time.
4. **Retire REST** once all clients have moved.

Run both during transition. The proto file becomes the source of truth; REST becomes a generated facade for legacy consumers via grpc-gateway.

## When to skip gRPC

- **One language only.** If everyone's on Python, the polyglot benefit doesn't materialise; REST or a Python-native RPC framework may be simpler.
- **Public APIs.** As discussed; REST or GraphQL fits better.
- **Loose coupling matters more than performance.** REST's looseness is sometimes a feature.
- **Team unfamiliarity with Protobuf.** Learning curve is real; if the project is small, REST may ship faster.

## Further reading

- [ApiDesignBestPractices] — broader API design context
- [MicroservicesArchitecture] — service-to-service comms in microservices
- [SchemaRegistryAndEvolution] — managing the Protobuf evolution
