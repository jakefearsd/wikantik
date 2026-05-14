---
title: Subsystem bridge retirement (Phase 12)
date: 2026-05-14
status: draft
---

# Subsystem bridge retirement

## Problem

After Phases 1–11 of the wikantik-main decomposition (complete 2026-05-08),
the `WikiEngine.managers` map is gone, `getManager(Class<T>)` no longer
exists on the `Engine` API interface, every cross-cutting service flows
through a typed `*Subsystem.Services` record, and ArchUnit forbids new
registry-style lookups.

Eight `*SubsystemBridge` adapter classes survive that work:

- `CoreSubsystemBridge`
- `AuthSubsystemBridge`
- `PersistenceSubsystem` (no separate bridge file — direct accessor on `WikiEngine`)
- `PageSubsystemBridge`
- `RenderingSubsystemBridge`
- `SearchSubsystemBridge`
- `PageGraphSubsystemBridge`
- `KnowledgeSubsystemBridge`

Phase 11 Ckpt 5 (commit `c1ab97508`) made seven of them thin shims that
delegate to their paired `*SubsystemFactory.create(...)` with a
`synthDepsFromEngine` adapter. `KnowledgeSubsystemBridge` was intentionally
excluded from that delegation pass because its factory is a
full-construction pipeline (cron schedulers, repository wiring, embedding
service startup) — not a registry reader — so re-running it for every
caller would have unpredictable side effects.

The bridges now serve a single residual purpose: they bridge the gap
between a caller holding only the `Engine` API reference and the typed
`Wiki*Subsystem.Services` snapshots stashed on `WikiEngine` at boot.
Every call site that retains a bridge dependency takes one of two forms:

1. `((Wiki|Some)Bridge).fromLegacyEngine(engine).xService()` — production
   lookups (REST resources, MCP wiring, plugins).
2. `(Wiki|Some)Bridge.rebuildFromManagers(engine)` — called only by
   `WikiEngine.setManager(Class, T)` (line 465 in `WikiEngine.java`) to
   keep a typed snapshot coherent after a test-fixture hot-swap.

Both forms are stable but architecturally redundant: `WikiEngine` already
has typed `getKnowledgeSubsystem()` / `getCoreSubsystem()` / etc.
accessors that production callers can use directly when they cast
`Engine` → `WikiEngine`. The bridge mostly exists to encapsulate that
cast and to host the test-fixture rebuild path.

This spec retires the bridges in a single coordinated cut. After this
phase, production callers reach typed subsystems through `WikiEngine`
directly; the test-fixture rebuild logic moves into `WikiEngine` itself
as a private helper. No public surface changes.

## Goals

1. Delete the eight `*SubsystemBridge` classes from production.
2. Production callers acquire typed subsystem services through one of:
   - `((WikiEngine) engine).getXSubsystem()` (when the caller has only an
     `Engine` reference).
   - Constructor injection (preferred for new code).
3. Keep `WikiEngine.setManager(Class, T)` hot-swap coherence for the 178
   test files that depend on it — the `rebuildFromManagers` logic moves
   into a private method on `WikiEngine` itself.
4. Replace the two test files that reference the bridge directly with
   tests against the new `WikiEngine` internal rebuild path.
5. No production behaviour change. No new public API. No new failure
   modes during engine startup or shutdown.

## Non-goals

- Decomposing `WikiEngine` further (it is still ~2,000 LOC; that is its
  own follow-up).
- Adding `getXSubsystem()` accessors to the `Engine` API interface (would
  pull every subsystem record into the API module's dependency graph).
- Touching `*SubsystemFactory.create(...)` — the factories already match
  their callers; the bridges just stop wrapping them.
- Migrating callers off the `Engine` interface entirely (constructor
  injection is preferred for new code; existing callers stay on `Engine`
  with a single internal cast).
- Test-fixture overhaul. `TestEngine.setManager(...)` keeps its current
  shape; only the implementation of the rebuild side effect moves.

## Current state at start-of-phase

`KnowledgeSubsystemBridge` (~ 100 LOC, two public methods):
- `fromLegacyEngine(Engine)` → tries `wikiEngine.getKnowledgeSubsystem()`,
  falls back to `rebuildFromManagers`. Used by 7 production callers
  (`RestServletBase`, `McpToolRegistry`, `KnowledgeMcpInitializer`,
  `DefaultContextRetrievalService`, `RelationshipsPlugin`, `SearchWikiTool`,
  `WikiBootstrapServletContextListener` shutdown path).
- `rebuildFromManagers(WikiEngine)` → reads 23 typed `mgr_*` fields off
  `WikiEngine`. Called by `WikiEngine.setManager(...)` line 465 to keep
  the snapshot coherent after a hot-swap. Also the fallback inside
  `fromLegacyEngine` when the typed snapshot is `null`.

The other seven bridges (`CoreSubsystemBridge`, `AuthSubsystemBridge`,
`PageSubsystemBridge`, `RenderingSubsystemBridge`, `SearchSubsystemBridge`,
`PageGraphSubsystemBridge`, and the implicit `PersistenceSubsystem`
accessor) all delegate to their paired `*SubsystemFactory.create(...)`
via a private `synthDepsFromEngine` adapter (Phase 11 Ckpt 5,
`c1ab97508`). Each is ≤ 50 LOC. Each has a paired `*BridgeTest` covering
the delegation contract.

178 test files use `TestEngine.setManager(...)` to install a mocked
service. After the call, `WikiEngine.setManager` triggers a snapshot
rebuild via the bridge. Two test files reference the bridge directly:
`KnowledgeSubsystemBridgeTest` and `SearchWikiToolTest`.

ArchUnit's `no_get_manager_anywhere` rule passes with zero violations
in `wikantik-main`. The freeze store still carries 129 violations from
Phases 1–10 (legacy, frozen).

## Design

### Production lookup path

Every production caller that today does

```java
KnowledgeSubsystemBridge.fromLegacyEngine(engine).kgService();
```

rewrites to

```java
((WikiEngine) engine).getKnowledgeSubsystem().kgService();
```

For callers that already have a `WikiEngine` reference (most servlets
hold the engine via the bootstrapped `WikiSubsystems` servlet-context
attribute), the cast is a no-op. For callers that hold only an
`Engine` (plugins, the MCP tool registry), a single guarded cast at the
top of the method replaces the bridge call:

```java
if (!(engine instanceof WikiEngine we)) {
    // Same defensive return the bridge previously provided.
    return null;
}
final var kg = we.getKnowledgeSubsystem();
```

This pattern repeats in seven sites for `KnowledgeSubsystemBridge` and
in roughly the same number of sites for each of the other seven bridges.

### Hot-swap coherence

`WikiEngine.setManager(Class<T>, T)` currently dispatches to a per-class
`BiConsumer<WikiEngine, Object>` rebuild map (Phase 11 Ckpt 1). For
Knowledge-layer classes the consumer body is:

```java
e -> { if (e.knowledgeSubsystem != null) e.knowledgeSubsystem =
    KnowledgeSubsystemBridge.rebuildFromManagers(e); };
```

After this phase, the call moves to a private `WikiEngine` method:

```java
private static KnowledgeSubsystem.Services rebuildKnowledgeSubsystem(WikiEngine e) {
    // body verbatim from KnowledgeSubsystemBridge.rebuildFromManagers
}
```

and the consumer becomes

```java
e -> { if (e.knowledgeSubsystem != null) e.knowledgeSubsystem =
    rebuildKnowledgeSubsystem(e); };
```

The seven other bridges receive the same treatment — their
`synthDepsFromEngine` + `factory.create(deps)` logic moves into a
matching private `WikiEngine.rebuild*Subsystem` static helper, then the
bridge file is deleted.

### Test impact

`TestEngine.setManager(...)` continues to work unchanged. The rebuild
side effect now lives in `WikiEngine` private methods that
`setManager(...)` already calls. Tests neither know nor care.

Two tests need targeted updates:

1. `KnowledgeSubsystemBridgeTest` — rewrites against
   `WikiEngine.rebuildKnowledgeSubsystem(engine)` via package-private
   visibility (the test moves to the same package as `WikiEngine`).
2. `SearchWikiToolTest` — the bridge reference is in a comment only; the
   comment updates to describe the typed `getKnowledgeSubsystem()` path.

### Order of operations

1. Move the eight `rebuildFromManagers` bodies into private static
   methods on `WikiEngine` (one per subsystem). Behaviour-zero step —
   the bridge classes still exist and still delegate, but now they
   delegate to `WikiEngine.rebuild*` instead of doing the work
   themselves.
2. Rewrite every production caller of `*Bridge.fromLegacyEngine(engine)`
   to `((WikiEngine) engine).getXSubsystem()` (or constructor injection
   where the caller is a fresh helper class).
3. Delete the eight `*SubsystemBridge` classes.
4. Update `KnowledgeSubsystemBridgeTest` and `SearchWikiToolTest`.
5. Run ArchUnit + full IT reactor. Update the freeze store if any
   frozen violations naturally drop out.
6. Update the decomposition design doc to record Phase 12 outcome.

## Risks

- **Cast safety in plugins.** `RelationshipsPlugin` receives `WikiContext`
  and reaches `context.getEngine()`. The current bridge tolerates a
  non-`WikiEngine` engine by returning an all-null services record. The
  replacement cast must keep the same defensive behaviour — return `null`
  from a typed accessor rather than throwing `ClassCastException`. Easy
  to get wrong if a reviewer assumes "production always has WikiEngine."
- **Shutdown ordering.** `WikiBootstrapServletContextListener` reaches
  the bridge during context destroy to close `JudgeRunner` and
  `RetrievalQualityRunner`. The replacement must work when the typed
  snapshot has already been torn down — use the direct typed accessor
  with a null-check.
- **WikiContext.getEngine() return type.** `WikiContext` currently
  returns `Engine` (the API). All bridge callers cast to `WikiEngine`.
  If `WikiContext.getEngine()` ever begins returning a different concrete
  type (e.g. for an embedded engine variant), the casts break silently.
  Add an ArchUnit rule "any `(WikiEngine)` cast lives only in
  `wikantik-main`" so external module casts are forbidden — this lets
  future-us see the breakage at build time.
- **Test-fixture invisibility.** The bridge tests currently document
  what `rebuildFromManagers` does. Moving the logic to private
  `WikiEngine` methods means the public surface shrinks and the test
  set shrinks alongside it. Risk: a future refactor of
  `WikiEngine.setManager` accidentally drops the rebuild call without
  any failing test. Mitigation: package-private exposure plus a single
  `WikiEngineSetManagerRebuildTest` that mocks one service per
  subsystem and asserts the snapshot stays coherent after every
  `setManager` call.
- **ArchUnit freeze store churn.** The freeze contains 129 legacy
  violations. Deleting bridges may naturally retire some — recompute
  the freeze rather than letting CI surface them as "fixed" violations.

## Done when

- The eight `*SubsystemBridge.java` files are deleted.
- Production callers compile and run with `((WikiEngine) engine).getXSubsystem()`
  or constructor-injected services.
- `WikiEngine` carries one private `rebuild*Subsystem(WikiEngine)` method
  per subsystem; `setManager` dispatchers call those.
- `KnowledgeSubsystemBridgeTest` becomes `WikiEngineKnowledgeRebuildTest`
  (or merges into a single `WikiEngineSetManagerRebuildTest`).
- `mvn clean install -Pintegration-tests -fae` is green.
- ArchUnit `no_get_manager_anywhere` still passes; new rule "no
  `(WikiEngine)` cast outside `wikantik-main`" added and passing.
- `bin/metrics/decomposition-progress.json` records `subsystem_bridges`
  going from 8 → 0.
- Decomposition design doc gets a "Phase 12" section recording outcome
  and final metrics.

## Phases beyond this

After Phase 12, the remaining decomposition tail:

- **WikiEngine slimming (Phase 13, est. 2 days).** `WikiEngine.java` is
  still ~2,000 LOC (vs the Phase 9 goal of "under 300 LOC"). The
  `initialize()` body, the 23 typed `mgr_*` field declarations, and the
  per-class `setManager` writer map dominate. Candidates: extract a
  `WikiEngineBootstrap` helper for `initialize()`; reify `mgr_*` fields
  into a typed record; extract a `SubsystemSnapshotRegistry` for the
  rebuild dispatcher.
- **WikiContext slimming (Phase 14, est. 1–2 days).** Phase 10 split
  `WikiContext` into `RequestScope` + `PageScope` + `RenderingScope` but
  `WikiContext.java` grew from 821 → 875 LOC because of delegation
  boilerplate. The delegation can drop if call sites adopt the scope
  classes directly; ~50 call sites across `wikantik-rest` and
  `wikantik-main`.
- **ArchUnit freeze sweep (Phase 15, est. 1 day).** 129 frozen
  legacy violations from Phases 1–10. Re-evaluate which still apply;
  delete the rest.

None of these is blocking. Phase 12 is the natural next pick because
it closes the open architectural question ("why do we still have
bridges?") with the smallest blast radius.
