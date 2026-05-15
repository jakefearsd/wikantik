---
summary: Technical guide to Dependency Injection (DI) and Inversion of Control (IoC).
  Covers constructor injection, lifecycles (Singleton/Transient), and Guice/Spring
  patterns.
date: 2025-05-15T00:00:00Z
cluster: design-patterns
auto-generated: false
canonical_id: 01KQ0P44PH044M4T08CZ5W1NBJ
type: article
title: Dependency Injection Patterns
tags:
- design-patterns
- dependency-injection
- ioc
- decoupling
- software-architecture
status: active
hubs:
- ArchitectureHub
---

# Dependency Injection: Decoupling Systems

Dependency Injection (DI) is a design pattern that implements **Inversion of Control (IoC)**, allowing components to receive their dependencies from an external assembler rather than creating them internally.

## 1. Core Injection Modalities

*   **Constructor Injection (Recommended):** Dependencies are passed via the class constructor.
    *   *Benefit:* Ensures the object is always in a valid state and allows for `final` (immutable) fields.
*   **Setter Injection:** Dependencies are provided via public setter methods.
    *   *Use Case:* Optional dependencies or those with sensible defaults.
*   **Field Injection:** Using annotations (e.g., `@Inject`, `@Autowired`) directly on private fields.
    *   *Warning:* Generally an anti-pattern. It makes unit testing harder (requires reflection) and hides dependencies from the class contract.

## 2. Lifecycles and Scoping

The IoC container manages the lifetime of the injected objects:
| Scope | Description | Use Case |
| :--- | :--- | :--- |
| **Singleton** | One instance per container. | Stateless services, database pools. |
| **Transient** | New instance created for every injection. | Lightweight, stateful objects. |
| **Request/Session**| One instance per HTTP lifecycle. | User context, authentication tokens. |

## 3. Concrete Implementation: Google Guice

In the Wikantik codebase ([GEMINI.md](GEMINI)), we use Guice for DI.
*   **Modules:** Classes that define the bindings (`bind(Interface.class).to(Implementation.class)`).
*   **The Injector:** The object that bootstraps the graph and provides the root service.
*   **Concrete Tip:** Use **Provider Methods** (`@Provides`) for complex object creation that requires logic or external configuration.

## 4. Why DI? (The Testing Argument)

The primary value of DI is testability.
*   **Stubbing:** In a unit test, you can inject a **Mock** implementation of a repository into a service. 
*   **Isolating Failure:** By mocking the database, you verify the service's business logic in isolation, ensuring the test fails only if the logic is wrong, not because the database is down.

---
**See Also:**
- [WikiEngine DI Migration](GEMINI) — Project-specific Guice context.
- [Api Design Best Practices](ApiDesignBestPractices) — Building decoupled services.
- [Cloud Networking](CloudNetworking) — Infrastructure dependency parallels.
