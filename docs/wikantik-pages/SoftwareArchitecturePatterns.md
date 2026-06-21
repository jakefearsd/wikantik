---
status: active
date: '2026-05-15'
summary: Technical deep-dive into major architectural patterns (Layered, Hexagonal,
  Event-Driven) with concrete I/O flow diagrams and dependency analysis.
tags:
- software-architecture
- hexagonal-architecture
- event-driven-architecture
- layered-architecture
- design-patterns
type: article
auto-generated: false
canonical_id: 01KQ0P44WQEHZAGCX1JCZHFKE9
cluster: design-patterns
title: Software Architecture Patterns
---

# Software Architecture Patterns

Software architecture defines the structural boundaries and communication paths of a system. Choosing a pattern is a trade-off between developer velocity, system maintainability, and operational complexity.

## 1. Layered (N-Tier) Architecture

The most common traditional pattern, where components are organized into horizontal layers. Each layer has a specific responsibility and typically only communicates with the layer immediately below it.

### I/O Flow Diagram (Prose)
1.  **Request Input:** User sends an HTTP request to the **Presentation Layer** (Controller).
2.  **Downward Call:** The Controller translates the request into a method call on the **Business Layer** (Service).
3.  **Transitive Dependency:** The Service performs business logic and calls the **Data Access Layer** (Repository).
4.  **Physical I/O:** The Repository executes a SQL query against the Database.
5.  **Upward Response:** Data flows back up the stack (DB → Repo → Service → Controller → User).

**Key Characteristic:** High coupling. The business logic depends directly on the database implementation. If you change the database, the Service layer often requires modification.

## 2. Hexagonal (Ports & Adapters)

This pattern inverts the dependencies of the Layered architecture. The "Core" (Business Logic) sits at the center and defines **Ports** (interfaces) for everything external.

### I/O Flow Diagram (Prose)
1.  **Primary Actor:** A user (or another system) interacts with a **Driving Adapter** (e.g., a REST API or CLI).
2.  **Inward Translation:** The Adapter converts the external signal into a call to a **Driving Port** (Interface implemented by the Core).
3.  **Core Execution:** The Core logic executes using only internal entities.
4.  **Outward Port Call:** When the Core needs data, it calls a **Driven Port** (an interface it defines, e.g., `UserRepository`).
5.  **Secondary Adapter:** A **Driven Adapter** (e.g., `PostgreSQLAdapter`) implements that port, performing the actual physical I/O.

**Key Characteristic:** Dependency Inversion. The Core depends on *nothing* external. The Database depends on the Core's interface.

## 3. Event-Driven Architecture (EDA)

EDA moves away from synchronous method calls toward asynchronous message passing. Components communicate by emitting and consuming events.

### I/O Flow Diagram (Prose)
1.  **Event Source:** Service A performs a local change and emits a `UserRegistered` event to an **Event Broker** (e.g., Kafka).
2.  **Decoupled Buffer:** The Broker stores the event in an append-only log. Service A is now finished; it does not wait for a response.
3.  **Asynchronous Consumption:** Service B (Emailer) and Service C (Analytics) independently poll the Broker.
4.  **Independent Action:** Service B sees the event and sends an email. Service C sees the event and updates a dashboard.
5.  **State Consistency:** The system reaches [EventualConsistency](EventualConsistency) as all consumers process the event log.

**Key Characteristic:** Temporal and Spatial Decoupling. Producers and consumers do not need to exist at the same time or know each other's location.

## Comparative Summary

| Pattern | Dependency Direction | Primary Benefit | Main Drawback |
|---|---|---|---|
| **Layered** | Top → Down | Simple to understand | Hard to test without DB |
| **Hexagonal** | Outside → Inside | Testable, Tech-agnostic | High boilerplate |
| **Event-Driven** | To/From Broker | Massive Scalability | Debugging/Tracing complexity |

## Further Reading
- [SystemDesignPrinciples](SystemDesignPrinciples)
- [HexagonalArchitecture](HexagonalArchitecture)
- [EventDrivenArchitecture](EventDrivenArchitecture)
- [CqrsPattern](CqrsPattern)
