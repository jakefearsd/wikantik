---
canonical_id: 01KQ12YDVYMJ6YFQP897KP47CC
title: Microservices Architecture
type: article
cluster: software-architecture
status: active
date: '2026-05-15'
tags:
- microservices
- distributed-transactions
- saga-pattern
- orchestration
- consistency
summary: Advanced guide to microservices consistency, focusing on the Saga Pattern (Orchestration vs Choreography) and the "Final Boss" of distributed transactions.
auto-generated: false
---

# Microservices Architecture

Microservices achieve organizational scalability by decoupling service boundaries. However, this decoupling introduces the most difficult problem in distributed systems: **Cross-Service Consistency**.

## The "Final Boss": Distributed Transactions

In a monolith, a transaction either commits or rolls back across the entire database. In microservices, each service has its own database. If a business process spans three services (e.g., Order → Payment → Inventory), you cannot use a global lock without destroying availability and performance.

### The Saga Pattern
A Saga is a sequence of local transactions. Each local transaction updates the database and publishes an event to trigger the next local transaction. If a step fails, the Saga executes **compensating transactions** to undo the preceding steps.

#### 1. Choreography (Event-Based)
Services exchange events without a central coordinator.
-   **Flow:** Order Service (Success) → `OrderCreated` → Payment Service (Success) → `PaymentAuthorized` → Inventory Service.
-   **Pros:** Highly decoupled, simple to start.
-   **Cons:** Hard to track the overall state; "Cyclic Dependencies" are common and dangerous.

#### 2. Orchestration (Command-Based)
A central "Saga Orchestrator" manages the state machine and tells each service what to do.
-   **Flow:** Orchestrator → `AuthorizePayment` → Payment Service (Success) → Orchestrator → `ReserveInventory` → Inventory Service.
-   **Pros:** Centralized visibility, easier to debug, no cyclic dependencies.
-   **Cons:** The Orchestrator itself is a single point of failure (requires [StateManagementPatterns](StateManagementPatterns) for durability).

## Concrete Example: Travel Booking Saga

A travel booking requires a Hotel and a Flight. If the Flight fails, the Hotel must be cancelled.

| Step | Service | Transaction | Compensation |
|---|---|---|---|
| 1 | Hotel | `bookHotel()` | `cancelHotel()` |
| 2 | Flight | `bookFlight()` | `cancelFlight()` |
| 3 | Payment | `chargeCard()` | `refundCard()` |

**Orchestrator Logic (Pseudo-code):**
```python
def travel_saga(request):
    try:
        hotel_id = hotel_service.book(request)
        try:
            flight_id = flight_service.book(request)
            try:
                payment_service.charge(request)
            except PaymentError:
                flight_service.cancel(flight_id)
                hotel_service.cancel(hotel_id)
        except FlightError:
            hotel_service.cancel(hotel_id)
    except HotelError:
        return "Booking Failed"
```

## Isolation Challenges (The AC-D in BASE)
Sagas lack the "Isolation" of ACID. While a Saga is running, other transactions might see the "Intermediate State" (e.g., the Hotel is booked but the Flight isn't yet).

**Mitigation Strategies:**
-   **Semantic Lock:** Use an `application-level lock` (e.g., set `status = PENDING`) to prevent other processes from modifying the same data.
-   **Commutative Updates:** Design operations so the order doesn't matter (e.g., increments/decrements).
-   **Pessimistic View:** Show users "Pending" states instead of "Success" until the entire Saga completes.

## Observability and the "Golden Signal"
When a Saga spans 10 services, finding the point of failure is impossible without **Distributed Tracing**.
-   **Trace Context:** Every request must carry a `trace_id` and `span_id`.
-   **Log Correlation:** All service logs must include the `trace_id` to allow reconstructing the "Story" of a failed transaction across the entire cluster.

## Further Reading
- [SagaPattern](SagaPattern)
- [OutboxPattern](OutboxPattern)
- [EventSourcing](EventSourcing)
- [DistributedTracing](DistributedTracing)
