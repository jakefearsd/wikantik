---
canonical_id: 01KQ0P44X66GR63Q528CVZZY16
title: System Design Principles
type: article
cluster: software-architecture
status: active
date: '2026-05-15'
tags:
- system-design
- architecture
- microservices
- monolith
- actor-model
- technical-decision-making
summary: A rigorous architectural decision matrix for choosing between Monolith, Microservices, and Actor model architectures based on scale, consistency, and operational complexity.
auto-generated: false
---

# System Design Principles

Modern system design is less about choosing "the best" technology and more about managing the trade-offs between complexity, latency, and consistency. This guide provides a rigorous framework for navigating these choices.

## Architectural Decision Matrix

Choosing the fundamental shape of your system (Monolith vs. Microservices vs. Actor Model) is the most consequential decision in the lifecycle of a project.

| Dimension | Monolith | Microservices | Actor Model (e.g., Erlang/Akka) |
|---|---|---|---|
| **State Management** | Shared memory / Single DB | Distributed DBs / Eventual Consistency | Encapsulated in Actors (Mailbox) |
| **Consistency** | Strong (ACID) | Eventual (Sagas/Outbox) | Strong (within Actor) / Causal (between) |
| **Scalability** | Vertical (Scale Up) | Horizontal (Scale Out) | Highly Granular (Millions of Actors) |
| **Fault Isolation** | Poor (Process-wide failure) | Good (Service-level isolation) | Excellent (Supervision trees) |
| **Deployment** | Single Atomic Release | Independent CI/CD Pipelines | Hot-code loading (often supported) |
| **Network Tax** | Negligible (In-process calls) | High (Serialization/Latency) | Low to Medium (Location Transparency) |
| **Operational Tax** | Low (Single log/metric stream) | Extreme (Tracing/Service Mesh) | Medium (System-wide monitoring) |
| **Team Structure** | Single Large Team | Multiple Independent Teams | Hybrid (Requires actor-logic expertise) |
| **Concurrency** | Threads/Locks (Complex) | Process Isolation (Simple) | Message Passing (Lock-free) |

## 1. The Monolith: When Consistency is King
A monolith is not "legacy"; it is a strategic choice for **Maximum Developer Velocity** and **Strong Consistency**.

-   **Use when:** You are in the "Discovery Phase" (finding product-market fit), your team is small (< 10 engineers), or your domain requires strictly atomic transactions across all entities.
-   **The Modular Monolith:** A middle ground where code is strictly organized into independent modules with clear interfaces, but deployed as a single unit. This allows for a later transition to microservices if needed.

## 2. Microservices: When Scaling is a Team Sport
Microservices solve **Organizational Bottlenecks** more than technical ones. They allow 500 engineers to work on the same product without stepping on each other's toes.

-   **Use when:** Your organization has > 3 teams, components have vastly different scaling needs (e.g., a CPU-heavy Video Encoder vs. a simple API), or you require high deployment frequency across different parts of the system.
-   **The Network Tax:** Every service call introduces serialization overhead (JSON/gRPC), network latency, and the "Final Boss" of distributed transactions (the [SagaPattern](SagaPattern)).

## 3. The Actor Model: The "Self-Healing" Architecture
The Actor Model (used in Erlang, Elixir, and Akka) treats everything as an independent "Actor" that communicates via asynchronous message passing.

-   **Use when:** You need **Massive Concurrency** (e.g., millions of active websocket connections), high-frequency state updates (e.g., online gaming or real-time trading), or extreme fault tolerance.
-   **Supervision Trees:** Actors are organized in hierarchies. If a "worker" actor crashes, its "supervisor" detects the failure and restarts it based on a strategy (One-For-One, One-For-All), ensuring the system remains "Self-Healing."

## Core Principles of Scalable Design

Regardless of the chosen architecture, three principles remain foundational:

### I. Decouple via Asynchrony
Synchronous calls create **Temporal Coupling**. If Service A calls Service B synchronously, Service A's availability is capped by Service B's.
-   **Solution:** Use Message Queues (Kafka, RabbitMQ) to move to a fire-and-forget model where possible.

### II. Design for Failure (The Circuit Breaker)
Assume every dependency *will* fail.
-   **Implementation:** Wrap outbound calls in a **Circuit Breaker**. If failure rates spike, the breaker trips, and your system fails-fast or returns a cached default, preventing a "Retry Storm" from bringing down the entire cluster.

### III. Statelessness in the Compute Layer
Keep the application servers stateless. All state must reside in durable persistence (DBs) or distributed caches (Redis). This allows for easy horizontal scaling (Auto-scaling groups) and simplifies rolling deployments.

## Further Reading
- [SoftwareArchitecturePatterns](SoftwareArchitecturePatterns)
- [MicroservicesArchitecture](MicroservicesArchitecture)
- [SagaPattern](SagaPattern)
- [CapTheorem](CapTheorem)
