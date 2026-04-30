---
canonical_id: 01KQ0P44MCDCQNDHCX00XVJ7HC
title: Batch API Design
type: article
cluster: web-services-and-apis
status: active
date: '2026-04-26'
summary: How to design batch endpoints — the request/response shapes, partial-failure
  handling, ordering guarantees, and the patterns that scale beyond hand-written per-request
  endpoints.
tags:
- api
- batch
- bulk-operations
- partial-failure
related:
- ApiProtocolComparison
- IdempotencyPatterns
- PaginationStrategies
- FileUploadPatterns
hubs:
- WebServicesAndApisHub
---
# Batch API Design

Calling an API once per item is fine for small N. Once N is large or the network is slow, batching is essential. The batch-API design problem is non-trivial: how do you represent the request, handle partial failures, return per-item results, and keep the API understandable?

This page covers the patterns that work.

## Why batch

For 1000 items at 100ms per request: 100 seconds.
For 1000 items batched: typically 1-3 seconds total.

The savings come from eliminating per-request overhead (TCP, TLS, HTTP, auth) and from server-side optimization (single transaction, prepared-statement reuse).

Batching matters whenever the item count or rate is high enough that the overhead dominates.

## Request shape

A common pattern:

```json
POST /api/orders/batch
{
    "operations": [
        { "id": "1", "amount": 100.00 },
        { "id": "2", "amount": 200.00 },
        { "id": "3", "amount": 300.00 }
    ]
}
```

The request contains an array of items. The response mirrors the structure with per-item results.

## Partial failures

The hard part. Some items in a batch may succeed while others fail. The API must communicate which.

### All-or-nothing

Either the whole batch succeeds or none of it does. Simpler API; more failures.

```json
{
    "status": "failed",
    "error": "Item 2 invalid: amount must be positive"
}
```

### Per-item results

Each item has its own outcome. More complex API; better client experience.

```json
{
    "results": [
        { "id": "1", "status": "ok", "data": {...} },
        { "id": "2", "status": "error", "error": "amount must be positive" },
        { "id": "3", "status": "ok", "data": {...} }
    ]
}
```

For most batch APIs, per-item results are right. The client retries failed items; succeeded items are not redone.

### HTTP status code

For mixed-success batches, the standard is 200 OK with per-item results in the body. The HTTP status reflects only whether the batch was processed at all (parsed, accepted), not whether all items succeeded.

This is unintuitive but standard. Don't return 4xx for "some items failed."

## Ordering and atomicity

### Independent items

If items are independent (no relationships), order does not matter; processing can be parallel. Most batch APIs are this case.

### Dependent items

Order matters; items reference each other. The API must process in order or fail consistently.

### Atomicity

If clients need "all or nothing," the API must support transactional semantics. Costs more server-side; smaller maximum batch sizes. Make the choice explicit per endpoint.

## Size limits

Batch endpoints need limits. Without limits, clients send arbitrarily large batches and break the server.

Reasonable defaults:
- 100 items per batch (small operations)
- 1000 items per batch (read-only or simple writes)
- Limited by total payload size (e.g., 1MB) for large-item batches

Document the limit in the API. Return a clear error if exceeded:

```json
{
    "error": "batch_too_large",
    "message": "Maximum 100 items per batch; received 250"
}
```

## Idempotency

Batch operations should support idempotency keys (see [IdempotencyPatterns](IdempotencyPatterns)). A retry of a partially-failed batch should not double-process the items that succeeded.

Two approaches:
- **Batch-level idempotency**: a single key for the whole batch
- **Item-level idempotency**: each item has its own key

Item-level is more useful for partial-failure retries; batch-level is simpler.

## Async batches

For very large batches that take minutes:

```json
POST /api/imports/batch
{ "items": [...] }

Response 202 Accepted:
{
    "job_id": "abc123",
    "status_url": "/api/imports/batch/abc123"
}
```

The client polls or subscribes to status. Each item's status is reported when known.

This pattern decouples the request from the processing time.

## Common patterns to avoid

- **Implicit ordering dependence.** "First create A, then B references A" without making it explicit. Clients break.
- **All-or-nothing semantics with no item-level error.** Client cannot tell which item failed.
- **Returning 207 Multi-Status.** Standard but rarely well-handled by clients; HTTP 200 with per-item results is more common.
- **No batch-size limit.** Server gets DoS'd.
- **Batch endpoint that's "just a wrapper."** If the server processes items serially without optimization, the network savings are real but server-side performance is unchanged.

## Common failure patterns

- **Different shape for batch vs. single.** Single-item endpoint and batch endpoint have inconsistent fields. Make them parallel.
- **Forgetting partial-failure handling.** Most batches don't fail entirely; clients need item-level signals.
- **No idempotency.** A network blip during the batch leaves the client unsure which items succeeded; retrying double-processes.
- **No async option for large batches.** Batches that take 5 minutes block the connection.

## Further Reading

- [ApiProtocolComparison](ApiProtocolComparison) — Where batching fits in protocol choice
- [IdempotencyPatterns](IdempotencyPatterns) — Idempotency for retries
- [PaginationStrategies](PaginationStrategies) — Read-side analog
- [FileUploadPatterns](FileUploadPatterns) — Different style of bulk transfer
- [WebServicesAndApis Hub](WebServicesAndApisHub) — Cluster index
