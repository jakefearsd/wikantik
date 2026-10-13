---
date: '2026-03-29'
status: active
title: Constructor Injection
related:
- TestStubConversion
- WikantikDevelopment
cluster: wikantik-development
canonical_id: 01KQ0P44NXNZ4RNF95JAC2TABN
type: article
tags:
- development
- testing
- refactoring
- dependency-injection
summary: Testability refactor from service locator pattern to constructor dependency
  injection
---
# Constructor Injection

> **Status (2026-10-13):** The initial wave described below shipped on 2026-03-29. `WikiEngine.getManager()/setManager()` still exists as a thin service locator, but it is frozen: `DecompositionArchTest` allows no new `getManager` callers, and new services register through `engine.setManager(Type.class, impl)` from their wiring helper (ADR-0008 in the repository's `docs/adr/`). See [ADR-001](ADR-001) for the interface extraction this work built on.

The constructor injection initiative refactored high-value classes from the service locator pattern (calling `engine.getManager()` internally) to accepting dependencies via constructor parameters.

## Motivation

The service locator pattern made unit testing difficult — tests had to create a full WikiEngine even when only one manager was needed. Constructor injection allows tests to pass lightweight stubs directly.

## Classes Refactored

A first wave of high-value classes was converted on 2026-03-29, including the core page, reference, rendering, recent-articles, difference and variable managers (`DefaultPageManager`, `DefaultReferenceManager`, `DefaultRenderingManager`, `DefaultRecentArticlesManager`, `DefaultDifferenceManager`, `DefaultVariableManager`), the auth managers, `LuceneSearchProvider`, `WikiSession`, `AttachmentServlet` and `SecurityVerifier`. Each class gained a constructor accepting its dependencies while maintaining backwards compatibility through the existing no-argument constructor, which falls back to service location.

[{Relationships}]
