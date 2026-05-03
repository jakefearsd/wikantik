---
canonical_id: 01KQEKGD9ED8G0WM5GFWCK094R
title: Database Design Patterns
type: article
cluster: databases
status: active
date: '2026-04-25'
tags:
- database
- design-patterns
- event-sourcing
- audit-trail
- soft-delete
summary: Patterns at the multi-table level — event sourcing, audit trails,
  polymorphic associations, materialised views, soft delete, temporal data —
  with the trade-offs that decide when each pays.
related:
- DatabaseDesign
- DatabaseIndexingStrategies
- CqrsPattern
- EventDrivenArchitecture
hubs:
- DatabasesHub
---
# Database Design Patterns: Multi-Table Architecture

Advanced database design requires patterns that span multiple tables to handle auditing, relationship complexity, and temporal state.

---

## I. Audit and Change Tracking

### Audit Columns (Tier 1)
The universal default. Every table includes `created_at`, `updated_at`, `created_by`, and `updated_by`. This identifies *when* and *who*, but not the specific delta.

### History Tables (Tier 2)
A parallel table stores a snapshot of the main table's row on every change via a database trigger.
*   **Trade-off**: Increases storage and write overhead but provides a queryable audit trail for critical entities (e.g., `orders`, `profiles`).

### Event Sourcing (Tier 3)
Events are the immutable source of truth; state is a projection. 
*   **Implementation**: Store events in an `event_store` table. Replay events to reconstruct state at any point in time. Use for financial ledgers or high-compliance domains.

---

## II. Relationship Patterns

### Soft Delete
Mark rows as deleted via a `deleted_at` timestamp rather than physical removal.
*   **Constraint Tip**: Use partial unique indexes (e.g., `UNIQUE (email) WHERE deleted_at IS NULL`) to allow reuse of natural identifiers after deletion.

### Polymorphic Associations
When a table (e.g., `comments`) can link to one of several parents (e.g., `posts`, `videos`).
*   **Recommended Pattern**: Use **Separate Foreign Key Columns** for each parent type with a `CHECK` constraint ensuring exactly one is populated. This maintains referential integrity, which is lost in "type-tag + ID" patterns.

---

## III. Performance and Scaling Patterns

### Materialized Views
Stored, pre-computed aggregations refreshed periodically.
*   **Use Case**: Expensive queries where minutes of staleness are acceptable (e.g., `user_lifetime_value`).
*   **Optimization**: Use `REFRESH MATERIALIZED VIEW CONCURRENTLY` in Postgres to avoid blocking reads during updates.

### Idempotency Table
A dedicated table to track unique request keys for side-effect-producing operations.
*   **Pattern**: Store the `idempotency_key` and the result of the first successful execution. Subsequent retries with the same key return the stored result without re-executing business logic.

### Outbox Pattern
Atomically write data to the database and an `outbox` table in a single transaction.
*   **Purpose**: Ensures "at-least-once" delivery of events to external message brokers (Kafka, RabbitMQ) without distributed transactions.

---

## IV. Temporal and Hierarchical Data

### Effective-Dating
Track row values over time using `effective_from` and `effective_until` timestamps.
*   **Postgres Tip**: Use `TSTZRANGE` types with exclusion constraints to prevent overlapping time windows at the database layer.

### Hierarchical Storage
*   **Adjacency List**: `parent_id` referencing the same table. Best for simple trees; query via Recursive CTEs.
*   **Ltree / Path**: Storing the full path (e.g., `A.B.C`). Best for deep trees and prefix-based retrieval.

---

## V. Strategic Alignment
Aging-well designs prioritize **Correctness over Performance** in the early phases.
1.  **Enforce constraints** (FKs, CHECKs) from day one.
2.  **Use surrogate keys** to decouple relationships from data volatility.
3.  **Implement auditing and soft-delete** for user-controlled data.
4.  **Adopt the Outbox pattern** for all external integrations.
