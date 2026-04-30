---
canonical_id: 01KQ0P44R08SSJA60M78QBEMVF
title: Idempotency Patterns
type: article
cluster: web-services-and-apis
status: active
date: '2026-04-26'
summary: How to design idempotent APIs — the role of idempotency keys, the right level
  of granularity, and the storage and expiration patterns that make idempotency reliable
  in production.
tags:
- idempotency
- api-design
- retry
- distributed-systems
related:
- ApiProtocolComparison
- BatchApiDesign
- WebhookPatterns
- ServerSentEventsPatterns
hubs:
- WebServicesAndApisHub
---
# Idempotency Patterns

A request is idempotent if making it twice produces the same effect as making it once. For unreliable networks (which is to say, all networks), idempotency is essential — clients retry; without idempotency, retries cause duplicate side effects.

This page is about how to design idempotent APIs and the patterns that work in production.

## What idempotent means

Formally: `f(f(x)) = f(x)`. Operationally: the second call has no additional effect.

Examples:
- `PUT /resource/123` with full state — idempotent (same final state)
- `DELETE /resource/123` — idempotent (deleted is deleted)
- `POST /orders` creating a new order — NOT idempotent without explicit support
- Adding $10 to a balance — NOT idempotent; doubles on retry

POST is the typically-troublesome verb. Most state-creating operations are POST; without idempotency keys, retries duplicate.

## Idempotency keys

The standard pattern: clients send a key with the request. Server records the key + response. On retry with the same key, server returns the same response without reprocessing.

```http
POST /api/orders
Idempotency-Key: 8d4f...

{ "amount": 100.00 }
```

If the request is retried with the same key, the server:
1. Checks if the key exists
2. If yes, returns the original response (no reprocessing)
3. If no, processes the request and stores the key + response

## Generating keys

Keys must be unique per logical operation:

- **UUIDs** for general use
- **Business-meaningful keys** when the natural identifier is stable: `order-{customer}-{timestamp}`
- **Hash of request payload** for cases where the same content shouldn't process twice

Clients generate the key; the server doesn't know what the client considers the same operation.

## Server-side storage

Storage requirements:

- Persistent (survives server restarts)
- Distributed (any server in a cluster sees the same key)
- TTL (keys eventually expire)

Common stores: Redis (with TTL), database table with cleanup job, dedicated idempotency service.

Schema:

```sql
CREATE TABLE idempotency (
    key VARCHAR(255) PRIMARY KEY,
    request_hash VARCHAR(64),  -- to detect different request with same key
    response_status INT,
    response_body JSONB,
    created_at TIMESTAMP,
    expires_at TIMESTAMP
);
```

The `request_hash` lets you detect "key reuse with different payload" — a client error worth flagging.

## Concurrent retries

What if two retries hit two different servers simultaneously? Both see "key not found" and both start processing.

Solutions:

### Database-level uniqueness

Insert the key with the request before processing. If insert fails (key exists), wait or read the existing record.

```sql
-- Atomic check-and-set
INSERT INTO idempotency (key, status) VALUES (?, 'processing')
ON CONFLICT DO NOTHING;
```

### Distributed lock

Acquire a lock on the key before processing. Release after recording response. Other concurrent retries wait or fail.

### Optimistic with deduplication

Process both; deduplicate at storage time (transaction with constraint). Wasteful but simple.

## TTL

Keys expire eventually. Common: 24 hours.

Too short: legitimate retries (network delay, client retry policy) miss the window.
Too long: storage grows; old keys clutter the system.

24 hours covers most retry scenarios. Document the TTL so clients know how long they can safely retry.

## What about non-POST verbs?

- **GET**: naturally idempotent (no state change)
- **PUT**: naturally idempotent if the body is the full new state
- **DELETE**: naturally idempotent

POST is the verb that needs idempotency keys. PATCH is between — partial updates can be non-idempotent if the patch references current state.

## Specific patterns

### Stripe-style

Stripe popularized idempotency keys for payment APIs. Their pattern:

- Header: `Idempotency-Key: <client-provided>`
- Different request body with same key → error (client bug detection)
- TTL: 24 hours
- Documented; client SDKs generate keys automatically

Most payment and financial APIs follow this pattern.

### Database upsert

The simplest case: the database itself enforces idempotency via unique constraint:

```sql
INSERT INTO orders (id, ...) VALUES (?, ...)
ON CONFLICT (id) DO NOTHING
RETURNING id;
```

The client provides the ID; duplicates are no-ops. Simpler than full idempotency-key storage; only works when the client generates IDs.

### Idempotent operations by design

Some operations are naturally idempotent:

```
POST /orders/{id}/mark-shipped
```

Calling it twice has the same effect as once: status = shipped. No need for idempotency keys.

Designing operations to be idempotent by construction is often easier than retrofitting keys.

## Common failure patterns

- **No idempotency support.** Network retries duplicate orders.
- **Idempotency that doesn't survive server restart.** In-memory cache; lost on deploy.
- **Different request body, same key.** Should be an error; sometimes silently ignored.
- **Forgetting cleanup.** Idempotency table grows indefinitely.
- **Idempotency at the wrong granularity.** Per-batch when per-item is needed, or vice versa.
- **Race on concurrent retries.** Both pass the "key exists" check; both process.

## Further Reading

- [ApiProtocolComparison](ApiProtocolComparison) — Idempotency across protocols
- [BatchApiDesign](BatchApiDesign) — Idempotency for batches
- [WebhookPatterns](WebhookPatterns) — Webhook delivery semantics
- [WebServicesAndApis Hub](WebServicesAndApisHub) — Cluster index
