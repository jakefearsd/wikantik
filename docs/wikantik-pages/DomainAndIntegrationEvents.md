---
cluster: distributed-systems
canonical_id: 01KQ0P44PYDRSSJFPFRAXSZ4GK
title: Domain and Integration Events
type: article
tags:
- ddd
- microservices
- outbox-pattern
- exactly-once
- event-driven
status: active
date: 2025-05-15
summary: Advanced architectural patterns for maintaining consistency in distributed systems using Domain and Integration events with Transactional Outbox.
auto-generated: false
---

# Consistency Patterns: Domain vs. Integration Events

In high-scale distributed systems, the distinction between internal state transitions and external data contracts is the primary defense against architectural decay. Mismanaging the boundary between **Domain Events** and **Integration Events** leads to "distributed big balls of mud" and catastrophic consistency failures.

## 1. Tactical Implementation: Domain Events (DE)

Domain Events capture occurrences within a single **Bounded Context**. They facilitate side effects within the same aggregate or across multiple aggregates in the same transactional boundary.

### 1.1 The In-Memory Dispatcher
DEs are typically handled synchronously or asynchronously within the same process. Using an in-memory bus (e.g., MediatR in .NET, Spring Events in Java), the domain model remains decoupled from the side effects (e.g., updating a read model, triggering a secondary aggregate).

**Key Constraint:** DEs should be emitted *before* the transaction commits to allow side-effect handlers to participate in the same ACID transaction if required, though DDD purists often advocate for eventual consistency even within a context.

## 2. Strategic Contract: Integration Events (IE)

Integration Events are the public API of a service. They represent committed facts that external services must consume. Unlike DEs, which often contain rich domain objects, IEs must be lean, versioned, and stable.

### 2.1 Schema Stability and Versions
*   **IE Payload:** Should contain the event type, version, timestamp, and the minimal state required for downstream processing (usually just IDs and changed fields).
*   **Contract:** Use Avro or Protobuf with a Schema Registry to enforce backward compatibility.

## 3. Solving the Atomicity Problem: The Transactional Outbox Pattern

The most critical failure mode in event-driven systems is the "dual write" problem: updating the database and sending a message to a broker (Kafka/RabbitMQ) are not atomic.

### 3.1 The Pattern Mechanics
To achieve atomicity without 2PC (Two-Phase Commit), use the **Transactional Outbox**:
1.  **Atomicity:** Within the business transaction, insert the event payload into a dedicated `OUTBOX` table in the same database.
2.  **Relay:** A separate process (the "Message Relay" or "Change Data Capture" agent) polls the `OUTBOX` table or tails the database transaction log.
3.  **Dispatch:** The relay publishes the message to the broker and marks the outbox entry as dispatched.

### 3.2 Exactly-Once Semantics
Strict "Exactly-Once" is a theoretical ideal; in practice, we achieve it via **At-Least-Once Delivery + Idempotent Consumption**.

1.  **Producer (At-Least-Once):** The Outbox Relay ensures the message reaches the broker. If the broker ACK fails, it retries.
2.  **Consumer (Idempotency):** The consumer must track processed `EventID`s.
    ```sql
    -- Consumer side idempotency check
    BEGIN TRANSACTION;
    IF NOT EXISTS (SELECT 1 FROM ProcessedEvents WHERE EventID = :eventId) THEN
        UPDATE AggregateTable SET ...;
        INSERT INTO ProcessedEvents (EventID) VALUES (:eventId);
    END IF;
    COMMIT;
    ```

## 4. Technical Comparison

| Feature | Domain Event (DE) | Integration Event (IE) |
| :--- | :--- | :--- |
| **Scope** | Internal (Bounded Context) | External (Cross-Service) |
| **Transaction** | Part of the local ACID txn | Outbox Pattern (Eventual Consistency) |
| **Transport** | In-memory bus / local DB | Message Broker (Kafka, SNS/SQS) |
| **Format** | Domain Classes | DTO / Schema-bound (Avro/JSON) |
| **Failure Mode** | Local Txn Rollback | Retry / Dead Letter Queue (DLQ) |

## 5. Synthesis: The Event Lifecycle

An expert implementation follows this flow:
1.  **Command Execution:** Aggregate processes a command, emits DE.
2.  **Local Side Effects:** Local handlers react to DE (e.g., update local search index).
3.  **State Commitment:** Local transaction commits, including the **Outbox Entry**.
4.  **Asynchronous Relay:** Outbox relay detects the entry, publishes IE to Kafka.
5.  **External Consumption:** Downstream service consumes IE, ensuring idempotency.

By decoupling the *fact of change* (Domain) from the *notification of change* (Integration), we preserve the integrity of the microservice boundary while ensuring system-wide reliability.
