---
title: The Saga Pattern
type: article
cluster: distributed-systems
status: published
date: '2026-05-10'
summary: A pattern for managing distributed transactions across microservices through a sequence of local transactions and compensating actions.
tags:
- distributed-systems
- transactions
- microservices
- reliability
- event-driven
relations:
- {type: component_of, target_id: 01KQEKGD9XWDSFGH7TWHH63NZT} # Distributed Systems Hub
- {type: alternative_to, target_id: "Two-Phase Commit"}
- {type: related_to, target_id: 01KS7X5P5S838D4EYVWFA9F36E} # CQRS/ES
canonical_id: 01KS7X5P5S838D4EYVWFA9F36E
---

# The Saga Pattern: Distributed Consistency without 2PC

In a microservices architecture, a single business process (e.g., "Order Fulfillment") often spans multiple services, each with its own database. Traditional **Two-Phase Commit (2PC)** is often avoided in 2026 due to its blocking nature and poor scalability. The **Saga Pattern** provides an alternative by treating a distributed transaction as a sequence of local transactions.

## 1. Core Concept

A Saga is a sequence of local transactions $T_1, T_2, ..., T_n$. Each local transaction updates the database and publishes an event or message to trigger the next step.
*   **Success Path:** If all $T_i$ succeed, the business process is complete.
*   **Failure Path:** If $T_i$ fails, the Saga must execute **Compensating Transactions** $C_{i-1}, ..., C_1$ to undo the changes made by the preceding steps.

## 2. Implementation Strategies

### A. Choreography (Event-Based)
There is no central coordinator. Each service produces and listens to events from other services.
*   **Pros:** Highly decoupled; easy to add/remove participants.
*   **Cons:** Can lead to "spaghetti events" where the business flow is hard to visualize.

### B. Orchestration (Command-Based)
A central **Orchestrator** (State Machine) manages the Saga logic. It sends commands to services and handles their responses.
*   **Pros:** Centralized visibility; easy to implement complex logic (parallel steps, conditional branches).
*   **Cons:** Risk of a "distributed monolith" if the orchestrator becomes too heavy.
*   **2026 Standard:** Use for workflows with 4+ steps or strict audit requirements (e.g., via *Temporal* or *AWS Step Functions*).

## 3. The 2026 Transaction Model

To simplify recovery, modern Sagas categorize steps into three types:

| Type | Description |
| :--- | :--- |
| **Compensatable** | Steps that can be undone (e.g., "Reserve Item"). Requires a matching $C_i$. |
| **Pivot** | The "Point of No Return." If this succeeds, the Saga *must* complete. |
| **Retriable** | Steps after the pivot (e.g., "Send Receipt"). Designed to eventually succeed via retries. |

## 4. Critical Dependencies

Sagas are fragile without two supporting patterns:
1.  **Transactional Outbox:** Ensures the database update and event publication happen atomically.
2.  **Idempotent Consumers:** Ensures that retrying an event (at-least-once delivery) does not result in duplicate side effects (e.g., charging a customer twice).

## See Also
*   [Distributed Systems Hub](DistributedSystemsHub) — Pattern catalog.
*   [CQRS and Event Sourcing](CQRSAndEventSourcing) — Often used to store Saga state.
*   [Idempotent Receiver](IdempotentReceiver) — Mandatory for Saga participants.
