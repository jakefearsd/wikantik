---
date: '2026-05-22'
summary: A deep dive into Hexagonal Architecture (Ports and Adapters) in Wikantik,
  focusing on decoupling core domain logic from infrastructure and framework leakage.
cluster: wikantik-development
auto-generated: false
canonical_id: 01KQTD4FF0P3V91F9GMSC7XPV6
type: article
title: 'Ports and Adapters: Hexagonal Architecture'
status: active
tags:
- hexagonal-architecture
- ports-and-adapters
- software-architecture
- decoupling
hubs:
- WikantikDevelopment
---

# Ports and Adapters: The Hexagonal Core

**Hexagonal Architecture** (also known as **Ports and Adapters**) is an architectural pattern that moves from a layered model to a "centered" model. The goal is to isolate the application's core logic (the Domain) from external concerns—UI, databases, and third-party APIs—by using explicit interfaces (Ports) and implementations (Adapters).

## I. The Core Philosophy: Dependency Inversion

In a traditional layered architecture, the Business layer depends on the Data Access layer. Hexagonal Architecture flips this:
*   **The Domain is Sovereign:** It defines **Ports** (interfaces) that describe what it needs.
*   **Infrastructure is an Implementation Detail:** External systems provide **Adapters** that implement those ports.

## II. Anatomy of the Hexagon

### 1. The Inside: Domain and Ports
The core contains only business logic. It has zero dependencies on frameworks (like Spring) or drivers.
*   **Driving Ports (Inbound):** Interfaces defining what the outside can do to the domain (e.g., `PageService.save()`).
*   **Driven Ports (Outbound):** Interfaces defining what the domain needs from the outside (e.g., `PageRepository`).

### 2. The Outside: Adapters
Adapters bridge the gap between the Port and the external technology.
*   **Primary Adapters (Driving):** The REST API, the CLI, or the Admin UI. They call the Domain's driving ports.
*   **Secondary Adapters (Driven):** The PostgreSQL implementation of a repository, the MailGun implementation of a notification service.

## III. Concrete Example: Decoupling Page Storage

In Wikantik, the `PageManager` interface is a **Driven Port**. The engine doesn't care if pages are stored in a Git repo, a database, or a cloud bucket.

### The Port (`wikantik-api`)
```java
public interface PageRepository {
    void persist(WikiPage page);
    Optional<WikiPage> fetch(String id);
}
```

### The Adapter (`wikantik-main`)
```java
public class JdbcPageRepository implements PageRepository {
    private final JdbcTemplate jdbc; // Implementation detail

    @Override
    public void persist(WikiPage page) {
        jdbc.update("INSERT INTO pages...", page.getId(), page.getContent());
    }
}
```

## IV. Technical Integrity: Preventing Leakage

A common failure in "Hexagonal" systems is **Framework Leakage**. If your `WikiPage` entity in the core hexagon contains JPA annotations (`@Entity`, `@Table`), you have leaked infrastructure into the domain.

**The Wikantik Standard:**
1.  **Pure Entities:** Entities in `wikantik-api` are POJOs or Records with zero annotations.
2.  **Mapping Layers:** The Adapter is responsible for mapping Domain Entities to Persistence Entities (e.g., mapping a `WikiPage` to a `PageDbo`).
3.  **No Persistence in Domain:** Domain logic never calls `save()` or `commit()`. It returns events or updated state; the Application Service (the driving port implementation) handles the persistence orchestration.

---
**See Also:**
- [Domain Driven Design](DomainDrivenDesign) — How to model the "Inside" of the hexagon.
- [System Architecture](WikantikArchitecture) — The 18-module breakdown of Wikantik.
- [Constructor Injection](ConstructorInjection) — Wiring adapters to ports without coupling.
