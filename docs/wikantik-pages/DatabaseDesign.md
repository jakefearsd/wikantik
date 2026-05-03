---
canonical_id: 01KQ12YDTGNPYEV2YB12F0QM96
title: Database Design
type: article
cluster: databases
status: active
date: '2026-04-25'
tags:
- database
- schema-design
- normalization
- denormalization
- data-modeling
summary: Schema design that survives growth — normalisation, when to denormalise,
  the constraints worth enforcing, and the patterns (audit columns, soft delete,
  surrogate keys) you'll thank yourself for later.
related:
- DatabaseDesignPatterns
- DatabaseIndexingStrategies
- DatabaseMigrationStrategies
- DimensionalModeling
hubs:
- DatabasesHub
---
# Database Design: Pragmatic Schema Engineering

Schema design decisions compound over time. A resilient database is built on pragmatic normalization, consistent column standards, and robust constraints.

## I. Normalization and Consistency

Start in **Third Normal Form (3NF)** to eliminate data redundancy and preserve the single source of truth. Denormalize only when performance metrics demonstrate that join costs exceed the overhead of managing duplicate state.

### Standard Column Infrastructure
Every mutable table should include the following standard columns:
*   **Surrogate Primary Key**: `BIGINT GENERATED ALWAYS AS IDENTITY` or `UUID v7`. Do not use natural keys (emails, usernames) as primary keys; they are subject to change and reveal sensitive data.
*   **Audit Timestamps**: `created_at` and `updated_at` (both `TIMESTAMPTZ NOT NULL DEFAULT NOW()`).
*   **Soft Delete**: `deleted_at TIMESTAMPTZ NULL` for user-recoverable data.
*   **Tenant Isolation**: `tenant_id` on every table in multi-tenant systems, enabling Row-Level Security (RLS) from day one.

---

## II. Key Selection: Surrogate vs. Natural

Always favor surrogate keys for primary and foreign keys. Natural keys should be enforced via **Unique Constraints** but never used as the target for a relationship.

| Key Type | Strength | Weakness |
|---|---|---|
| **BIGINT** | Sequential, small (8 bytes), cache-friendly. | Reveals creation order; centralized sequence. |
| **UUID v4** | Distributed, random (16 bytes). | Fragmented indexes; poor locality. |
| **UUID v7** | Timestamp-prefixed (2024 standard). | **Recommended default**: Locality of BIGINT with the distributability of UUID. |

## III. Data Integrity and Constraints

The database is the final arbiter of correctness. Do not rely exclusively on application-layer validation.
*   **Foreign Keys**: Mandatory for all relationships; impossible to write orphan records.
*   **Check Constraints**: Enforce domain-specific invariants (e.g., `status IN ('pending', 'paid')`).
*   **Not Null**: Use whenever a value is required; nullable columns are weak contracts.
*   **Atomic Types**: Use `NUMERIC` for currency (never `FLOAT`), `TIMESTAMPTZ` for time, and `JSONB` for semi-structured blobs.

---

## IV. Architectural Patterns

### Soft Delete
Use soft delete selectively for data where "undo" is expected. For derived or ephemeral data, use hard `DELETE` to maintain index performance and storage efficiency.

### Audit and History
For high-integrity domains, use **History Tables**. A database trigger writes a row snapshot to a parallel table on every change, providing a verifiable audit trail with minimal application logic overhead.

### Multi-Tenancy with RLS
Implement shared-schema multi-tenancy using **Postgres Row-Level Security (RLS)**. By binding the `tenant_id` to the session context, the database enforces isolation at the storage layer, preventing cross-tenant leakage even if application queries omit a `WHERE` clause.

### Indexing Strategy
*   **Index Foreign Keys**: Postgres does not auto-index FK columns.
*   **Composite Indexes**: Use for queries frequently filtering on multiple columns (e.g., `tenant_id` + `status`).
*   **Incremental Sync**: Index `updated_at` to support high-performance polling.

## V. Migration Discipline
Schema changes must be versioned, immutable, and additive-by-default.
*   **Safe**: Adding nullable columns, adding indexes concurrently.
*   **Unsafe**: Renaming or dropping columns (requires the Expand-Contract pattern).
*   **Dangerous**: Changing types on large tables (causes hours of locking).
