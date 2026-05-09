---
cluster: distributed-systems
canonical_id: 01KQ0P44Q8EETGG6ZT7S0BBB1G
title: Event Sourcing
type: article
tags:
- event-sourcing
- distributed-systems
- immutability
- cqrs
summary: Architectural deep-dive into Event Sourcing (ES), focusing on immutable logs, deterministic state reconstruction, and projection management.
auto-generated: false
---

Event Sourcing (ES) is an architectural pattern where the state of an entity is not stored as a single mutable record, but as a sequence of immutable, append-only facts called **events**.

## Core Definitions

1.  **Event:** A fact that occurred in the past (e.g., `OrderPlaced`, `InventoryReserved`). Events are immutable and named in the past tense.
2.  **Event Store:** A specialized database (e.g., EventStoreDB, Kafka) designed for high-throughput appends and ordered retrieval.
3.  **State Reconstruction:** The process of deriving current state by replaying events: $S_t = F(e_1, e_2, \dots, e_n)$.
4.  **Projection:** A materialized view of the events optimized for a specific query use case.

## Mathematical Model: The Ledger

In traditional CRUD, we store $S_t$ (State at time $t$). In ES, we store the history $H = \{e_1, e_2, \dots, e_n\}$. The state is a fold over the history:

$$S_t = \text{fold}(\text{InitialState}, H, \text{apply\_event})$$

### Determinism and Ordering
Integrity depends on **total ordering** within an aggregate. In a distributed environment, this is enforced via:
- **Optimistic Concurrency:** Every append specifies the `ExpectedVersion`. If the version in the store has moved, the write fails (Conflict).
- **Partitioning:** Routing all events for an entity (e.g., `AccountID`) to the same physical partition (e.g., Kafka Partition).

## Implementation Pattern: The Aggregate Root

The Aggregate Root is the consistency boundary. It consumes commands, validates them against current state, and emits events.

**Concrete Example (Pseudo-Java):**
```java
public class OrderAggregate {
    private List<Event> uncommittedEvents = new ArrayList<>();
    private OrderStatus status;
    private int version;

    // Command Handler
    public void shipOrder(ShipOrderCommand cmd) {
        if (this.status != OrderStatus.PAID) {
            throw new IllegalStateException("Cannot ship unpaid order");
        }
        applyNewEvent(new OrderShipped(cmd.orderId, Instant.now()));
    }

    // State Application (Pure Function)
    private void apply(Event event) {
        if (event instanceof OrderShipped) {
            this.status = OrderStatus.SHIPPED;
        }
        this.version++;
    }

    private void applyNewEvent(Event event) {
        apply(event);
        uncommittedEvents.add(event);
    }
}
```

## Scaling with Snapshots

Replaying 100,000 events to find a current balance is $O(N)$. **Snapshotting** provides a shortcut. A snapshot $S_{snap}$ is stored every $K$ events. The reconstruction becomes:
$$S_t = \text{apply}(\text{load}(S_{snap}), \{e_j \mid j > \text{snapshot\_index}\})$$

## Projections and CQRS

Event Sourcing is almost always paired with **CQRS (Command Query Responsibility Segregation)**. 

| Layer | Responsibility | Persistence |
|---|---|---|
| **Command (Write)** | Validation, Consistency, Append fact | Event Store (Append-only) |
| **Query (Read)** | High-performance lookup, Full-text search | PostgreSQL, Elasticsearch, Redis |

### Projection Divergence and Rebuilds
Projections are eventually consistent. If the projection logic changes (e.g., a new "Total Discount" field is needed), the projection is **rebuilt**:
1. Create a new, empty read table.
2. Replay all events from the event store into the new projection logic.
3. Once caught up, swap the query traffic to the new table.

## Operational Risks

1. **Schema Evolution:** If `OrderPlaced` V1 is in the log but the code expects V2, you must use **Upcasters**—middleware that transforms V1 JSON to V2 before the aggregate sees it.
2. **Eventual Consistency:** Users may not see their own changes immediately. Mitigation: Use "Read-Your-Own-Writes" tokens or block on the projection catch-up.
3. **Data Volume:** Logs grow forever. Use **Compaction** or cold-storage archiving for old streams.

## Further Reading
- [CqrsPattern](CqrsPattern)
- [EventualConsistency](EventualConsistency)
- [OutboxPattern](OutboxPattern)
