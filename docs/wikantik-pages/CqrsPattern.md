---
canonical_id: 01KQ12YDT9W70ZAA9TNNEERXSH
title: "Separation of Concerns: The CQRS Pattern"
type: article
cluster: software-architecture
status: active
date: '2026-05-22'
tags:
- cqrs
- command-query
- outbox-pattern
- scalability
summary: Decoupling write-intensive business logic from read-intensive data access. Explores the CQRS spectrum and the Outbox pattern for reliable data synchronization.
related:
- EventDrivenArchitecture
- DomainDrivenDesign
- DatabaseDesign
auto-generated: false
---

# Separation of Concerns: The CQRS Pattern

**CQRS (Command Query Responsibility Segregation)** is the principle that the model used to update data (Commands) should be different from the model used to read data (Queries). This segregation allows each side to evolve and scale independently based on its specific requirements.

## I. The CQRS Spectrum

1.  **Logical CQRS:** Using different DTOs for reads and writes within the same service and database.
2.  **Structural CQRS:** Using different database schemas (e.g., Normalized for Writes, Flattened for Reads).
3.  **Physical CQRS:** Using different database technologies (e.g., PostgreSQL for Commands, Elasticsearch for Queries).

## II. The Write Model (Command Side)
The write model is optimized for **Correctness and Invariants**. It typically uses a normalized schema and is wrapped in a domain model (Aggregates) that enforces business rules.

## III. The Read Model (Query Side)
The read model is optimized for **Performance and User Experience**. It uses denormalized views or search indexes that match the UI's needs exactly, avoiding expensive joins at runtime.

## IV. Concrete Example: Synchronizing with the Outbox Pattern

The biggest challenge in CQRS is keeping the two sides in sync without distributed transactions (which are slow and fragile). The **Outbox Pattern** provides a resilient solution.

### 1. The Atomic Transaction
The application writes the business change and a "Domain Event" into the *same* database in a single transaction.

```sql
BEGIN;
INSERT INTO orders (...) VALUES (...);
INSERT INTO outbox (event_type, payload) VALUES ('ORDER_CREATED', '{...}');
COMMIT;
```

### 2. The Relay
An external process (like Debezium) or a scheduled task reads the `outbox` table and pushes events to the Read Model (e.g., updating an Elasticsearch index).

## V. Strategic Trade-offs

*   **Complexity:** CQRS significantly increases code volume and architectural complexity. It should only be used when the read and write requirements are fundamentally at odds.
*   **Eventual Consistency:** The Read Model will always be slightly behind the Write Model. This must be handled in the UI (e.g., by using "Optimistic Updates").
*   **Auditability:** Because writes are often captured as events (see [Event Sourcing](EventSourcing)), you get a perfect audit trail of every state change for free.

---
**See Also:**
- [Domain Driven Design](DomainDrivenDesign) — Designing the command-side aggregates.
- [Event Driven Architecture](EventDrivenArchitecture) — The backbone of CQRS synchronization.
- [Database Design](DatabaseDesign) — Normalized vs. Denormalized schema strategies.
