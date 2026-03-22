# ADR-001: Extract Manager Interfaces to wikantik-api

**Status:** Proposed
**Date:** 2026-03-22
**Deciders:** jakefear

## Context

The MCP module (`wikantik-mcp`) depends directly on manager interfaces defined in `wikantik-main`:

- `com.wikantik.pages.PageManager` (referenced by 165 files)
- `com.wikantik.references.ReferenceManager` (41 files)
- `com.wikantik.attachment.AttachmentManager` (37 files)
- `com.wikantik.content.SystemPageRegistry` (41 files)

These interfaces are the contracts that define what a wiki engine provides. They belong in `wikantik-api` — the module that's supposed to define the public API. Instead, they live in `wikantik-main` alongside their implementations, which means:

1. **MCP can't compile without wikantik-main** — it imports concrete package paths from main
2. **No alternative implementations** — you can't build a lightweight engine that satisfies the same contracts
3. **"API" module is incomplete** — `wikantik-api` defines `Engine`, `Page`, `Context`, `PageProvider` but NOT the managers that tie them together
4. **Test isolation impossible** — testing a single manager requires the full `WikiEngine` with all 25+ managers initialized

## Analysis

### What's clean to move

**SystemPageRegistry** — Only 2 methods, imports only `com.wikantik.api.engine.Initializable`. Could move to `wikantik-api` today with zero complications.

**InternalModule** — Empty marker interface in `com.wikantik.modules`. Trivial to move.

### What has dependency chains

**PageManager** — Its method signatures reference:
- `PageLock` (`com.wikantik.pages.PageLock`) — used in `lockPage()`, `unlockPage()`, `getCurrentLock()`, `getActiveLocks()`
- `PageSorter` (`com.wikantik.pages.PageSorter`) — used in `getPageSorter()`
- Both are in wikantik-main. Moving PageManager requires moving these too.

**ReferenceManager** — Extends `InternalModule` (in wikantik-main) and `PageFilter` (in wikantik-api). Moving requires InternalModule to move first.

**AttachmentManager** — Not fully analyzed but likely has similar dependency chains with attachment-specific types.

### Scale of the change

- ~200+ files need import updates (165 for PageManager alone)
- 4-6 dependent types need to move alongside the interfaces
- Each moved type may pull additional types
- All tests reference the old package names
- JSP pages and plugins may also reference old names (need backward-compat shims)

## Decision

Deferred. The refactoring is architecturally correct but too large for incremental execution. The existing coupling is functional and doesn't block current development.

## Recommended approach when ready

### Phase 1: SystemPageRegistry (1-2 hours)
Move `SystemPageRegistry` to `wikantik-api` as proof of concept. It has zero dependency chains. Establishes the `com.wikantik.api.managers` package and the pattern for future moves.

### Phase 2: InternalModule + ReferenceManager (4-6 hours)
Move `InternalModule` (marker interface, trivial), then `ReferenceManager`. ReferenceManager has 41 references — manageable with sed.

### Phase 3: PageManager (8-12 hours)
Move `PageLock`, `PageSorter`, then `PageManager`. The 165-file import update is mechanical but requires careful testing. Leave backward-compat shims:
```java
package com.wikantik.pages;
/** @deprecated Use com.wikantik.api.managers.PageManager */
@Deprecated
public interface PageManager extends com.wikantik.api.managers.PageManager {}
```

### Phase 4: AttachmentManager (4-6 hours)
Similar to Phase 2. Analyze dependency chain first.

### Phase 5: Remove wikantik-main from MCP compile scope (2-4 hours)
Once all manager interfaces are in wikantik-api, change MCP's `pom.xml` dependency on wikantik-main from `provided` to `runtime`. MCP compiles against API only, main is provided at deployment.

## Related findings

### WikiEngine as service locator
77 calls to `engine.getManager(X.class)` across 41 files. This is the root cause — all component discovery goes through WikiEngine. Full decoupling would eventually replace this with constructor injection or a DI container.

### Test infrastructure coupling
`TestEngine` extends `WikiEngine` and initializes all 25+ managers for every test. Decoupling managers would enable lightweight test fixtures that initialize only the managers a test needs.

### Event system
Synchronous, singleton-based. Well-designed but could benefit from async option for I/O-bound listeners (webhooks, remote notifications).

### Module dependency graph (current)
```
wikantik-api ← wikantik-event, wikantik-util
wikantik-main ← wikantik-api, wikantik-event, wikantik-cache, wikantik-http
wikantik-mcp ← wikantik-api, wikantik-main (should be API-only)
```

### No circular dependencies
Verified: no module depends on a module that depends back on it. The dependency graph is acyclic.
