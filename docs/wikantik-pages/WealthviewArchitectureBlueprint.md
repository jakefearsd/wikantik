---
canonical_id: 01KQRW1416ME9FTMXTD4WXJRQ9
date: 2026-05-03T00:00:00Z
cluster: wealthview
type: blueprint
tags:
- wealthview
- architecture
- maven
- spring-boot
- react
- postgresql
- system-design
title: Wealthview Architecture Blueprint
- type: part-of
  target_id: 01KQR44WKHVES95QKN9731B09
- type: derived-from
  target_id: 01KQ0P44WQHVES95QKN9731B08
summary: A high-density technical blueprint of the Wealthview system architecture.
  Details the Maven multi-module structure, strict dependency rules, and the React/Spring
  Boot/PostgreSQL tech stack for RAG-driven development.
status: active
---

# Wealthview Architecture Blueprint

This blueprint defines the structural constraints and technology choices for the Wealthview platform. It is designed to ensure that RAG agents generate code that adheres to the project's strict modularity and security standards.

## 1. Technology Stack

| Layer | Technology |
| :--- | :--- |
| **Frontend** | React 19, TypeScript, Vite, React Router, Recharts, Axios |
| **Backend** | Java 21, Spring Boot 3.5, Spring Security (JWT), JPA/Hibernate |
| **Database** | PostgreSQL 16 (UUID Primary Keys, Flyway migrations) |
| **Testing** | JUnit 5, Mockito, Testcontainers (Postgres), Vitest |

## 2. Backend Module Structure (Maven)

Wealthview follows a strict multi-module Maven architecture to enforce separation of concerns.

| Module | Responsibility | Dependency Rules |
| :--- | :--- | :--- |
| `wealthview-api` | REST Controllers, Security Config, Exception Handlers | Depends on `wealthview-core` |
| `wealthview-core` | Services, Business Logic, Domain DTOs | Depends on `wealthview-persistence` |
| `wealthview-persistence` | JPA Entities, Repositories, Flyway Migrations | Leaf module (no internal deps) |
| `wealthview-import` | CSV/OFX Parsers, Finnhub/Zillow Clients | Depends on `wealthview-core` |
| `wealthview-projection` | Deterministic & Monte Carlo Engines | Depends on `wealthview-core` |
| `wealthview-app` | Spring Boot Main, Configs, Packaging | Depends on **all** modules |

### Strict Modularity Rule
`wealthview-api` must **never** depend directly on `wealthview-persistence`. Controllers only interact with Services; Repositories are private to the persistence/core boundary.

## 3. Data Integrity and Security

### Tenant Isolation
Every table (except global prices/tax data) contains a `tenant_id: UUID` foreign key. A **Spring Security Filter** injects the `tenant_id` from the JWT into the `SecurityContext`, which is then used by Hibernate's `@Filter` or Repository methods to enforce row-level isolation.

### Entity ID Strategy
All primary keys are **UUID** (`gen_random_uuid()`). This prevents ID scanning and simplifies merging data from offline imports.

### Holdings Recomputation
Holdings are **never stored as primary truth**. They are auto-computed by aggregating `TransactionEntity` rows. 
- **Trigger**: Any Change (CUD) to `transactions` triggers an async recomputation of the affected `holding` row.
- **Manual Override**: If `is_manual_override` is true, the recomputation logic skips that specific symbol for that account.

## 4. RAG Implementation Hook

When building for Wealthview, the agent should follow this hierarchy:
1.  Define the **JPA Entity** in `wealthview-persistence`.
2.  Define the **Service and DTO** in `wealthview-core`.
3.  Define the **REST Endpoint** in `wealthview-api`.

**Prompt Example:**
> "Following the `WealthviewArchitectureBlueprint`, add a new feature to track 'Private Equity' investments. Create the `PrivateEquityEntity` with UUID keys and tenant isolation, a service to calculate IRR, and a controller to expose the data."

## See Also
- [[FintechDataIngestionBlueprint]] — Data sync implementation.
- [[ObservabilityAndMonitoringBlueprint]] — Production operational standards.
- [[SoftwareArchitecture]] — General distributed system patterns.
