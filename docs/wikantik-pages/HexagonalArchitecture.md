---
status: official
cluster: wikantik-development
type: article
title: Hexagonal Architecture
date: '2026-05-04'
summary: How Wikantik uses the Hexagonal Architecture pattern to decouple core logic
  from infrastructure.
canonical_id: 01KQTD4FF0P3V91F9GMSC7XPV6
verified_at: '2026-05-04T21:10:44.598011331Z'
verified_by: gemini-cli-mcp-client
---
# Hexagonal Architecture in Wikantik

Wikantik follows the **Hexagonal Architecture** (also known as **Ports and Adapters**) pattern. This architectural style emphasizes the separation of the core business logic (the Domain) from external concerns like databases, user interfaces, and third-party services.

## The Core Philosophy
The primary goal of Hexagonal Architecture in Wikantik is to ensure that the core wiki logic remains independent of the technologies used to deliver it. This makes the system:
- **Testable:** The domain can be tested in isolation using stubs.
- **Replaceable:** The database (Postgres) or frontend (React) could be swapped with minimal impact on the core engine.
- **Decoupled:** Modules have clear boundaries and responsibilities.

## Structure in Wikantik

The platform's 18-module Maven structure mirrors the Hexagonal pattern:

### 1. The Domain and Ports (`wikantik-api`)
This is the center of the hexagon. It contains:
- **Entities:** `WikiPage`, `WikiContext`, `User`.
- **Ports (Manager Interfaces):** `PageManager`, `ReferenceManager`, `GroupManager`. These define the "contracts" that the core engine needs to function.

### 2. The Core Engine (`wikantik-main`)
Provides the "inside-the-hexagon" implementation of the wiki logic, such as Markdown rendering, link resolution, and the knowledge graph service.

### 3. Adapters (The "Outside" World)
Adapters connect the hexagon to external systems:
- **Persistence Adapters:** `VersioningFileProvider` (File system), `JDBCUserDatabase` (PostgreSQL).
- **Driving Adapters (Input):** `wikantik-rest` (REST API), `wikantik-admin-mcp` (Model Context Protocol).
- **UI Adapter:** `wikantik-frontend` (React SPA).

## Implementation Detail: ADR-001
The transition to a clean Hexagonal Architecture was solidified by **ADR-001**, which extracted the core manager interfaces into `wikantik-api`. This allowed the MCP modules to depend only on the interfaces (the Ports), rather than the full implementation in `wikantik-main`.

## Benefits for AI Agents
For AI agents working on the codebase, this architecture provides a clear map. If an agent needs to understand the "rules" of the system, it looks at `wikantik-api`. If it needs to understand how the system "talks" to the world, it looks at the various adapter modules.

## See Also
- [System Architecture](WikantikArchitecture) — The full 18-module breakdown.
- [Constructor Injection](ConstructorInjection) — The mechanism for wiring adapters to ports.
- [Design Patterns Overview](DesignPatternsOverview) — Other patterns used in the platform.
