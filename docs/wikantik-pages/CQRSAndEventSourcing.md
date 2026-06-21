---
status: active
date: '2026-05-10'
summary: A pair of architectural patterns that separate read and write models (CQRS)
  and represent state as a sequence of immutable events (Event Sourcing).
tags:
- distributed-systems
- cqrs
- event-sourcing
- reactive-systems
- audit-trail
type: article
relations:
- type: component_of
  target_id: 01KQEKGD9XWDSFGH7TWHH63NZT
- type: extension_of
  target_id: FunctionalProgrammingFoundations
- type: related_to
  target_id: 01KS7X5P5S838D4EYVWFA9F36E
cluster: distributed-systems
canonical_id: 01KS7Y6Q6T938D4EYVWFA9F36F
title: CQRS and Event Sourcing
---

# CQRS and Event Sourcing

In 2026, **CQRS** and **Event Sourcing** are the definitive patterns for building high-throughput, audit-perfect distributed systems. While they can be used independently, they are often combined to bypass the "performance wall" of traditional RDBMS systems in complex domains.

## 1. CQRS: Command Query Responsibility Segregation

CQRS separates the data model for **Writes** (Commands) from the data model for **Reads** (Queries).

*   **Command Model:** Optimized for business logic validation, consistency, and transactions. Often normalized.
*   **Read Model:** Optimized for specific UI views or API responses. Often denormalized and stored in high-speed caches (Redis) or search indexes (Elasticsearch).
*   **The Dividend:** Allows read and write paths to scale independently. In 2026 benchmarks, CQRS reduces read latency by up to **160%** compared to complex SQL JOINs on the write database.

## 2. Event Sourcing (ES)

Event Sourcing treats the **Event Log** as the primary source of truth. Instead of storing the "current state" (e.g., `balance = $100`), the system stores the sequence of events that led to that state (`Deposited $50`, `Deposited $50`).

### Core Advantages
1.  **Immutability:** Events never change; they are only appended. This eliminates row-level locking contention.
2.  **Auditability:** A perfect history of the system is preserved for free.
3.  **State Replay:** State can be reconstructed at any point in time by replaying the log.

### Managing Complexity: Snapshots
Replaying millions of events is slow. Modern systems use **Snapshots** (persisting the state every $N$ events) to keep reconstruction times under **50ms**.

## 3. The Power of the Duo

When combined, Event Sourcing provides the **Write-side** (the Event Store), and CQRS provides the **Read-side** (Projections).

1.  **Command arrives:** The system validates it and appends a new event to the Event Store.
2.  **Projection triggers:** An asynchronous process listens to the event and updates the denormalized Read Models.
3.  **Query arrives:** The UI reads from the lightning-fast Read Model.

## 4. 2026 Performance Benchmarks

| Metric | CRUD (RDBMS) | CQRS + Event Sourcing |
| :--- | :--- | :--- |
| **Write Throughput** | ~2.5k ops/sec | **~12.5k ops/sec** |
| **Read Latency** | Variable (JOINs) | **~12ms (Static Read Model)** |
| **Scalability** | Vertical | **Linear (Horizontal)** |

## See Also
*   [Distributed Systems Hub](DistributedSystemsHub) — Pattern index.
*   [The Saga Pattern](SagaPattern) — Managing consistency across Event Stores.
*   [Functional Programming Foundations](FunctionalProgrammingFoundations) — The mathematical roots of ES (state as a fold over events).
