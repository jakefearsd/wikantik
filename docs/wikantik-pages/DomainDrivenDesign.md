---
canonical_id: 01KQ12YDTQ0NHYCA7TRW1SSM4Z
title: "Ubiquitous Modeling: Domain-Driven Design"
type: article
cluster: software-architecture
status: active
date: '2026-05-22'
tags:
- ddd
- aggregates
- bounded-context
- ubiquitous-language
summary: A practitioner's guide to DDD, focusing on Ubiquitous Language, Bounded Contexts, and Aggregate Roots as transaction boundaries.
related:
- HexagonalArchitecture
- CqrsPattern
- EventDrivenArchitecture
auto-generated: false
---

# Ubiquitous Modeling: Domain-Driven Design

**Domain-Driven Design (DDD)** is an approach to software development for complex needs by connecting the implementation to an evolving model. It is not about technology, but about creating a shared mental model between domain experts and developers.

## I. Strategic Design: The Big Picture

### 1. Ubiquitous Language
The core of DDD is a single language shared by everyone on the team. If a domain expert says "Revision," the code must say `Revision`, not `Version` or `ChangeLog`. This eliminates the "translation layer" where bugs thrive.

### 2. Bounded Contexts
In large systems, the same term can mean different things. In the **Content Context**, a "User" is an author; in the **Auth Context**, a "User" is a set of credentials. DDD handles this by defining strict boundaries. Each Bounded Context has its own model and its own database schema.

## II. Tactical Design: The Building Blocks

### 1. Entities vs. Value Objects
*   **Entities:** Have a distinct identity that spans time (e.g., a `WikiPage` with a ULID).
*   **Value Objects:** Have no identity; they are defined by their attributes (e.g., an `Address` or `Money`). They are immutable. If the attributes change, it's a new Value Object.

### 2. Aggregate Roots
An **Aggregate** is a cluster of associated objects treated as a single unit for data changes. The **Aggregate Root** is the only gatekeeper to the cluster.

**Concrete Example: The `WikiPage` Aggregate**
A `WikiPage` and its `Frontmatter` are a single aggregate. You cannot modify the frontmatter directly; you must go through the page to ensure invariants (like "Title cannot be empty") are enforced.

```java
public class WikiPage {
    private final String id;
    private PageMetadata metadata; // Value Object

    // Business Logic on the Aggregate Root
    public void updateTitle(String newTitle) {
        if (newTitle == null || newTitle.isBlank()) {
            throw new DomainException("Title is mandatory");
        }
        this.metadata = metadata.withTitle(newTitle);
    }
}
```

## III. The Aggregate as a Transaction Boundary
A fundamental rule of DDD: **One transaction per aggregate.**
If you need to update two aggregates simultaneously, you use **Domain Events** and eventual consistency. This keeps the system scalable and prevents long-lived database locks across massive object graphs.

## IV. Anti-Corruption Layer (ACL)
When integrating with legacy systems or external APIs, use an **ACL**. This is a translation layer that prevents the messy outside model from leaking into your clean domain model.

---
**See Also:**
- [Hexagonal Architecture](HexagonalArchitecture) — The structural home for the domain model.
- [CQRS Pattern](CqrsPattern) — Separating read models from write (aggregate) models.
- [Event Driven Architecture](EventDrivenArchitecture) — Handling cross-aggregate communication.
