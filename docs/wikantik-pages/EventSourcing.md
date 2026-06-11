---
canonical_id: 01KQ0P44Q8EETGG6ZT7S0BBB1G
title: Event Sourcing
type: article
cluster: distributed-systems
status: active
date: '2026-05-15'
tags:
- event-sourcing
- distributed-systems
- cqrs
- persistence-patterns
- technical-design
summary: Detailed implementation guide for Event Sourcing, covering aggregate snapshotting, idempotent projection management, and deterministic state reconstruction.
auto-generated: false
---

# Event Sourcing

Event Sourcing (ES) is a persistence pattern that treats the history of an entity as the primary source of truth. Unlike traditional CRUD (Create, Read, Update, Delete) where the database stores a snapshot of the current state, ES stores an immutable, append-only log of every state change (events).

## The State Reconstruction Equation

Current state $S$at time$t$is the deterministic result of folding an initial state$S_0$over a chronologically ordered sequence of events$E$:

$$
S_t = S_0 \oplus e_1 \oplus e_2 \oplus \dots \oplus e_n
$$

In implementation terms, this means the state is a projection of history. This approach provides a perfect audit trail, the ability to time-travel (reconstruct state at any point in history), and simplified concurrency through append-only semantics.
## Aggregate Snapshotting

As the event stream grows, replaying$N$events becomes a performance bottleneck ($O(N)$ reconstruction). Snapshotting introduces a performance shortcut.

### Snapshot Strategy
1.  **Frequency:** Typically every 50-100 events, or based on a calculated replay-time budget.
2.  **Versioning:** Snapshots must include the `sequence_number` of the last event processed.
3.  **Storage:** Snapshots are often stored in a key-value store or a specialized table in the event store, separate from the primary log.

**Reconstruction Logic with Snapshots:**
```java
public OrderAggregate load(String aggregateId) {
    Snapshot<OrderState> snap = snapshotStore.load(aggregateId);
    OrderAggregate aggregate = new OrderAggregate(snap.getState());
    
    // Resume replay from the event immediately following the snapshot
    List<Event> events = eventStore.readStream(aggregateId, snap.getVersion() + 1);
    for (Event e : events) {
        aggregate.apply(e);
    }
    return aggregate;
}
```

## Read-Model Projections

Projections (Materialized Views) transform the raw event stream into a format optimized for specific query patterns. This is the "Query" side of CQRS.

### Idempotency and At-Least-Once Delivery
Most event brokers (Kafka, RabbitMQ) guarantee **at-least-once** delivery. Projection handlers must be idempotent to prevent data corruption during retries.

**Implementation Patterns for Idempotent Projections:**
-   **Database-level Deduplication:** Use the `event_id` or `sequence_number` as a unique constraint in the read-model table.
-   **Sequence Tracking:** Store the `last_processed_sequence` alongside the read model in the same transaction.

```sql
-- PostgreSQL Atomic Projection Update
BEGIN;
  UPDATE user_account_summary 
  SET balance = balance + :amount, last_event_id = :event_id
  WHERE user_id = :user_id AND last_event_id < :event_id;
COMMIT;
```

### Projection Replays
When projection logic changes (e.g., adding a new field or fixing a calculation bug), the read model is "replayed":
1.  **Shadow Table:** Create a new projection table.
2.  **Pointer Reset:** Point a new consumer group to the beginning of the event stream.
3.  **Hydration:** Process events into the shadow table.
4.  **Cutover:** Atomically swap the query traffic to the new table once the consumer catch-up latency is near zero.

## Event Upcasting: Handling Schema Evolution

Events are immutable, but code is not. If an event schema changes (e.g., `OrderPlaced` gains a `currency` field), you cannot rewrite the history.

**Upcasting Strategies:**
-   **Lazy Upcasting:** The event store returns the raw JSON; a middleware component transforms it to the current class version before it reaches the Aggregate.
-   **In-Place Transformation:** The transformation happens within the Aggregate's `apply` method (often leads to "pollution" of the domain model).
-   **Eager Upcasting (Background):** A background process migrates events to a new stream or version (risky, breaks strict immutability).

**Upcaster Example:**
```java
public class OrderPlacedUpcaster implements Upcaster {
    public JsonNode upcast(JsonNode eventJson) {
        if (!eventJson.has("currency")) {
            ((ObjectNode) eventJson).put("currency", "USD"); // Default for legacy events
        }
        return eventJson;
    }
}
```

## Determinism and Side Effects

**Crucial Rule:** The `apply(Event e)` method in an Aggregate must be a **pure function**. 
-   ❌ Never call an external API (e.g., Payment Gateway) inside `apply`.
-   ❌ Never use `Instant.now()` or random numbers inside `apply`.
-   ✅ Perform side effects in the **Command Handler** before emitting the event, or in an **External Reactor/Saga** after the event is persisted.

Failure to follow this rule makes state reconstruction non-deterministic, rendering the event log useless for recovery.

## Further Reading
- [CqrsPattern](CqrsPattern)
- [OutboxPattern](OutboxPattern)
- [EventualConsistency](EventualConsistency)
- [SagaPattern](SagaPattern)
