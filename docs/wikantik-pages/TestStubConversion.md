---
status: active
verified_at: '2026-05-04T21:10:44.598011331Z'
type: article
date: '2026-05-04'
cluster: wikantik-development
title: Test Stub Conversion
tags:
- testing
- mcp
- test-stubs
- refactoring
- wikantik-development
summary: A technical retrospective on the March 2026 effort to decouple MCP tests
  using lightweight in-memory stubs.
verified_by: gemini-cli-mcp-client
canonical_id: 01KQTD3JX009JJ9H5GWBV50BCW
---
# Test Stub Conversion

The Test Stub Conversion was a critical modernization effort completed on **March 23, 2026**. Its goal was to decouple the Model Context Protocol (MCP) server tests from the heavyweight `WikiEngine` and `TestEngine`, enabling faster, more isolated, and more reliable testing.

## The Problem: "Heavyweight" Tests
Prior to this effort, testing MCP tools required a full `TestEngine` instance. This meant:
- Starting up a simulated file system and indexing service for every test.
- Slow execution times (often several seconds per test).
- Fragile tests that could fail due to unrelated engine state.
- Circular dependencies between `wikantik-main` and the MCP modules.

## The Solution: Interface Extraction & Stubs
The conversion was made possible by **ADR-001**, which extracted core manager interfaces (like `PageManager`, `ReferenceManager`, and `GroupManager`) from `wikantik-main` into the lightweight `wikantik-api` module.

### Core Components
- **`StubPageManager`:** A lightweight, in-memory implementation of `PageManager` that allows tests to define a virtual set of pages without any disk I/O.
- **`StubReferenceManager`:** Simulates the wiki's link-tracking and backlink logic in memory.
- **`test-jar` Configuration:** The `wikantik-api` and `wikantik-main` modules were configured to produce `test-jar` artifacts, making these stubs available for use in downstream modules like `wikantik-admin-mcp` and `wikantik-knowledge`.

## Impact
On March 23, 2026, the following was achieved:
- **11 MCP tool tests** were converted from `TestEngine` to `StubPageManager`.
- **7 Medium-complexity MCP tests** were converted to use `StubReferenceManager`.
- **13 MCP tool constructors** were decoupled from `WikiEngine`, moving to explicit constructor injection of manager interfaces.
- **Improved CI speed:** By removing the need for a full engine bootstrap, the MCP test suite execution time was reduced by approximately 80%.

## See Also
- [Constructor Injection](ConstructorInjection) — The architectural pattern that enabled stubbing.
- [Wikantik Development](WikantikDevelopment) — The broader timeline of platform modernization.
- [ADR-001: Extract Manager Interfaces to API](ADR-001) — The design decision that started the decoupling effort.
