---
canonical_id: 01KQ12YDVYMJ6YFQP897KP47CC
title: Microservices Architecture
type: article
cluster: software-architecture
status: active
date: '2026-05-24'
tags:
- microservices
- architecture
- distributed-systems
- service-boundaries
- modular-monolith
summary: An engineering critique of microservices, focusing on the "Network Tax," service boundary discovery via DDD, and when to favor a modular monolith.
auto-generated: false
---
# Microservices Architecture

Microservices are a solution to an **organizational scaling problem**, not a technical one. They allow 500 engineers to work on the same product without stepping on each other's toes. If you have 5 engineers, microservices are merely a high-latency way to make your life difficult.

## The Microservices Trade-off

| Aspect | Monolith | Microservices |
|---|---|---|
| **Deployment** | Atomic (all or nothing). | Independent (high velocity). |
| **Transactions** | ACID (simple). | BASE / Sagas (eventual consistency). |
| **Observability** | Single stack trace. | Distributed tracing (OTel) mandatory. |
| **State** | Shared Database. | Database-per-service (No joins). |

## Finding the Boundaries: DDD

The most common failure in microservices is "Entity-based splitting" (e.g., `UserService`, `OrderService`). This leads to high coupling and "distributed monolith" behavior.

Use **Domain-Driven Design (DDD)** and **Bounded Contexts**.
- **Bad Boundary:** Every service calls `UserService` to get a name.
- **Good Boundary:** `BillingService` owns the user's credit card; `ShippingService` owns the user's address. They share a `user_id` but no other data.

## The Network Tax

In a monolith, a function call takes nanoseconds. In microservices, an RPC takes 10-100 milliseconds. 

**Anti-Pattern: N+1 Service Calls.** 
If your `FrontendAPI` calls `OrderService` for a list of 50 orders, and then calls `ProductService` 50 times to get the product names, your page will take 5 seconds to load. 
**Fix:** Bulk endpoints or a **BFF (Backend for Frontend)** that aggregates data on the server side.

## The "Database Per Service" Rule

If two services share the same database schema, they are not microservices. They are a distributed monolith. You cannot change the schema in Service A without potentially breaking Service B.
**Strict Rule:** One service, one database. Communication happens ONLY via APIs or Events.

## Implementation: The Outbox Pattern

To maintain consistency without 2PC (Two-Phase Commit), use the **Transactional Outbox**.

```sql
-- Inside the same transaction as your business logic
BEGIN;
  INSERT INTO orders (id, user_id, total) VALUES (123, 456, 99.99);
  INSERT INTO outbox (event_type, payload) VALUES ('ORDER_CREATED', '{"id": 123}');
COMMIT;
```

A separate "Relay" process reads the `outbox` table and publishes the events to Kafka. This ensures that the event is *only* published if the database write succeeds.

## When to Stay Monolithic

Start with a **Modular Monolith**. Use language-level boundaries (Packages in Java, Namespaces in C#) to keep domains separate. Use a single database but with separate schemas. 
Only "spin off" a module into a microservice when:
1. It needs different scaling (e.g., one module needs GPUs, the rest don't).
2. It has a different deployment cadence (e.g., daily updates vs. weekly).
3. The team owning it has grown too large to coordinate with the main repo.

## Further Reading
- [[DomainDrivenDesign]] — The prerequisite for drawing boundaries.
- [[EventDrivenArchitecture]] — How services talk without blocking.
- [[DistributedTracing]] — Finding where the 5 seconds went.
