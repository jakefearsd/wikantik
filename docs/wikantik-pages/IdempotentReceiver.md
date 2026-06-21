---
status: active
date: '2026-05-10'
summary: A design pattern ensuring that a service can safely process the same message
  multiple times without unintended side effects, a requirement for at-least-once
  delivery.
tags:
- distributed-systems
- messaging
- reliability
- idempotency
- api-design
type: article
relations:
- type: component_of
  target_id: 01KQEKGD9XWDSFGH7TWHH63NZT
- type: extension_of
  target_id: 01KS7X5P5S838D4EYVWFA9F36E
cluster: distributed-systems
canonical_id: 01KS8A8R8V938D4EYVWFA9F36H
title: Idempotent Receiver
---

# Idempotent Receiver

In distributed systems, the "Exactly-Once" delivery guarantee is an expensive abstraction that is often impossible to achieve at scale. Most systems provide **At-Least-Once** delivery, meaning a message may be delivered multiple times due to network retries, timeouts, or leader failovers. The **Idempotent Receiver** pattern ensures that processing a message more than once results in the same system state as processing it exactly once.

## 1. Natural vs. Synthetic Idempotency

### Natural Idempotency
Some operations are inherently idempotent and require no special logic.
*   **State Setting:** `UPDATE user SET status = 'ACTIVE'` (Multiple calls result in the same state).
*   **Deletions:** `DELETE FROM orders WHERE id = 123`.

### Synthetic Idempotency
Operations that change state incrementally must be made idempotent via explicit tracking.
*   **Increments:** `UPDATE accounts SET balance = balance + 100`.
*   **Creation:** `POST /orders` (Multiple calls could create duplicate orders).

## 2. Implementation Strategies

### Idempotency Keys (The Gold Standard)
The sender attaches a unique identifier (e.g., a UUID or a deterministic hash of the payload) to every request.
1.  **Check:** The receiver checks if the key exists in a persistent **Deduplication Store**.
2.  **Act:** If missing, it processes the request and stores the key + result in an atomic transaction.
3.  **Return:** If the key exists, it simply returns the cached result without re-executing the logic.

### Database Unique Constraints
Leveraging the database's ability to enforce uniqueness.
*   **Example:** A unique index on `order_id` in an `orders` table. A retry will trigger a "Unique Constraint Violation," which the receiver catches and treats as a success.

### Sequence High-Water Marks
Common in stream processing (Kafka/TCP). The receiver tracks the last processed sequence number. Any message with a number $\le$ the high-water mark is discarded.

## 3. Critical Design Rules

*   **Atomic Persistence:** The business logic update and the recording of the idempotency key **must** happen in a single atomic transaction. If one succeeds and the other fails, the system enters an inconsistent state.
*   **TTL Management:** Idempotency keys cannot be stored forever. A Time-to-Live (TTL) should be set that safely exceeds the maximum retry window (typically 24–72 hours).
*   **Downstream Propagation:** If a receiver calls other services, it must pass its own idempotency key (or a derived one) to those services to prevent duplicate side effects further down the chain.

## See Also
*   [Distributed Systems Hub](DistributedSystemsHub) — Reliability patterns.
*   [The Saga Pattern](SagaPattern) — Where idempotency is a mandatory requirement.
*   [Write-Ahead Log (WAL)](WriteAheadLog) — Managing the deduplication state durability.
