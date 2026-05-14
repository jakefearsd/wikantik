---
title: KnowledgeSubsystemBridge consistency fix (Phase 12)
date: 2026-05-14
status: draft
revision: 2 — scope corrected after callsite-inventory audit revealed 280 production callers
---

# KnowledgeSubsystemBridge consistency fix

## What changed in revision 2

Revision 1 proposed retiring all 8 `*SubsystemBridge` classes. A pre-implementation
inventory found **280 production call sites of `*Bridge.fromLegacyEngine(engine)`
across ~100 files in 6 modules** — not the ~14 sites the original spec assumed.
Per-bridge count: Page 91 · Core 77 · Auth 43 · Rendering 25 · PageGraph 23 ·
Search 11 · Knowledge 8.

The bridges are **not vestigial.** The `Engine` interface lives in `wikantik-api`,
which deliberately cannot depend on subsystem modules (would create cyclic
dependencies). Production callers — plugins, `Default*Manager` classes, servlets,
filters, REST resources — hold an `Engine` reference and have no other ergonomic
way to reach typed subsystems. The bridges encapsulate the `Engine → WikiEngine`
cast and the typed-accessor fallback. Phase 11 Ckpt 5 (`c1ab97508`) intentionally
preserved this pattern after making 7 of the 8 bridges thin factory-delegation
shims.

Retiring all 8 bridges means either adding 280 `(WikiEngine) engine` casts (ugly,
brittle, removes a well-defined encapsulation point) or migrating all 280 callers
to constructor injection (multi-week refactor across plugin / manager / servlet
APIs). Neither matches the cost/benefit of the original audit observation.

This revision narrows Phase 12 to the **one genuine architectural inconsistency**
the audit identified: `KnowledgeSubsystemBridge` is the only bridge that reads
the `WikiEngine` manager registry manually instead of delegating to its paired
factory. The other 7 bridges share a uniform `factory.create(synthDepsFromEngine(e))`
shape after Phase 11. Fix the asymmetry, document the bridges as the supported
`Engine → typed-subsystem` API, close the architectural question.

## Problem

After Phase 11 Ckpt 5, every `*SubsystemBridge` follows this shape:

```java
public static XSubsystem.Services fromLegacyEngine(Engine engine) {
    if (!(engine instanceof WikiEngine we)) return /* defensive empty */;
    final var typed = we.getXSubsystem();
    if (typed != null) return typed;
    return rebuildFromManagers(we);
}

public static XSubsystem.Services rebuildFromManagers(WikiEngine engine) {
    return XSubsystemFactory.create(synthDepsFromEngine(engine));
}

private static XSubsystem.Deps synthDepsFromEngine(WikiEngine engine) {
    return new XSubsystem.Deps(
        // read fields from other typed subsystems via *Bridge.fromLegacyEngine
        // or via engine.getYSubsystem() accessors
        ...);
}
```

Seven bridges (Core, Auth, Page, Rendering, Search, PageGraph) all match. They
were converted to this shape in Phase 11 Ckpt 5 (commit `c1ab97508`) which
delivered ~150 LOC of CPD deduplication.

`KnowledgeSubsystemBridge` is the outlier. Its `rebuildFromManagers` reads 23
manager fields directly from `WikiEngine` and synthesises a `KnowledgeSubsystem.Services`
record by hand:

```java
public static KnowledgeSubsystem.Services rebuildFromManagers(WikiEngine engine) {
    final KgCurationOps kgCurationOps = /* synthesised inline */;
    return new KnowledgeSubsystem.Services(
        engine.getManager(KnowledgeGraphService.class),
        engine.getManager(KgProposalJudgeService.class),
        engine.getManager(JudgeRunner.class),
        engine.getManager(KgMaterializationService.class),
        // ... 19 more getManager calls ...
        kgCurationOps);
}
```

The Phase 11 commit message explained why: `KnowledgeSubsystemFactory.create` has
side effects (cron scheduler, repository wiring, embedding-service startup) that
would re-fire on every `rebuildFromManagers` call. Manual synthesis was the safe
default at the time.

Three architectural consequences of leaving this as-is:

1. **No CPD deduplication for Knowledge.** The other 6 bridges share scaffolding
   via their factory adapters; Knowledge's 23-field reader is unique LOC.
2. **`KnowledgeSubsystemBridgeTest`'s contract differs from the others.** Tests
   document the manual-read invariant; the other 7 bridges' tests document the
   factory-delegation invariant. New contributors must learn two patterns.
3. **The Phase 11 close-out left this as a known asymmetry** with no recorded
   path to fix it. The breadcrumb commit `5845353a3` ("docs(mcp): breadcrumb for
   Phase 9 KnowledgeSubsystemBridge retirement") is now mis-named — there is no
   Phase-9 retirement; just an asymmetry waiting for resolution.

## Goals

1. Make `KnowledgeSubsystemBridge` structurally match the other 7 bridges so the
   bridge layer presents one consistent pattern.
2. Preserve the property that calling `rebuildFromManagers` does NOT re-fire the
   cron scheduler, repository wiring, or embedding-service startup (the reason
   it was excluded from Phase 11 Ckpt 5).
3. Document the `*SubsystemBridge` layer as the supported `Engine → typed-subsystem`
   accessor pattern in `wikantik-main`, with `wikantik-archtest` rules pinning the
   contract.
4. Update the decomposition design doc to formally close the bridge architecture
   question with "8 bridges, uniform pattern, deletion deferred indefinitely
   pending Engine-API redesign."

## Non-goals

- Retiring any of the 8 bridges. (See revision-2 rationale above.)
- Migrating any of the 280 production call sites of `*Bridge.fromLegacyEngine`
  to typed accessors or constructor injection.
- Touching the `Engine` API interface.
- Touching `WikiEngine.setManager(Class, T)` or its consumer-map dispatcher.
- Adding `getXSubsystem()` methods to the `Engine` API. (Would create cyclic
  module dependencies — Engine lives in `wikantik-api`.)
- Decomposing `WikiEngine` further. (Phase 13 territory; see decomposition
  design doc.)

## Current state at start-of-phase

`KnowledgeSubsystemBridge.java` — ~100 LOC, two public methods:

- `fromLegacyEngine(Engine)` matches the standard shape (typed accessor first,
  fall back to `rebuildFromManagers`).
- `rebuildFromManagers(WikiEngine)` reads 23 manager fields directly. **This is
  the only place in the bridge layer that calls `engine.getManager(...)` instead
  of going through a factory.**

`KnowledgeSubsystemFactory.java` exists and has a `create(Deps)` method. Its
constructor side effects are:

- Builds repositories from a `DataSource` reference held in `Deps.persistence()`.
- Schedules `JudgeRunner` cron via a `ScheduledExecutorService`.
- Starts `BootstrapEntityExtractionIndexer` if enabled.

These side effects must NOT re-fire on every `rebuildFromManagers` call.

## Design

### Option A — Adapter factory method (recommended)

Add a second factory method `KnowledgeSubsystemFactory.rebuildFromExisting(WikiEngine, KnowledgeSubsystem.Services existing)` that synthesises a new `Services` record from existing field values + the engine's current manager registry, without re-invoking any side-effectful construction. The bridge's `rebuildFromManagers` delegates to this.

Mechanically:

```java
// New in KnowledgeSubsystemFactory.java
static KnowledgeSubsystem.Services rebuildFromExisting(
        WikiEngine engine,
        KnowledgeSubsystem.Services existing) {
    // existing may be null on first init; in that case fall back to a
    // direct manager-read just like today.
    if (existing == null) return readFromManagerRegistry(engine);

    // Most fields stay the same; only the field whose typed manager was
    // hot-swapped via setManager has changed. Read each field from the
    // registry: if non-null, prefer the registry value (the test just
    // installed it); otherwise keep the existing field.
    return new KnowledgeSubsystem.Services(
        preferRegistry(engine, KnowledgeGraphService.class, existing.kgService()),
        preferRegistry(engine, KgProposalJudgeService.class, existing.judgeService()),
        // ... 21 more fields ...
        rebuildKgCurationOps(engine, existing));
}

private static <T> T preferRegistry(WikiEngine engine, Class<T> klass, T existing) {
    final T fromRegistry = engine.getManager(klass);
    return fromRegistry != null ? fromRegistry : existing;
}
```

Then `KnowledgeSubsystemBridge.rebuildFromManagers` becomes:

```java
public static KnowledgeSubsystem.Services rebuildFromManagers(WikiEngine engine) {
    return KnowledgeSubsystemFactory.rebuildFromExisting(
        engine, engine.getKnowledgeSubsystem());
}
```

Three-line method body. Matches the shape of the other 7 bridges (single delegate
to factory). No cron / repo / extractor side effects re-fire because
`rebuildFromExisting` reuses the existing instances for unchanged fields and only
swaps the hot-swapped one.

### Option B — Document the asymmetry instead

Leave the code alone. Add a long-form javadoc on `KnowledgeSubsystemBridge`
explaining why it differs from the other 7 bridges. Add an ArchUnit rule
"KnowledgeSubsystemBridge is the only allowed caller of `WikiEngine.getManager`
outside `WikiEngine` itself" to pin the asymmetry as intentional rather than
accidental.

Effort: ~1 hour. Doesn't actually fix the inconsistency, but closes the question
as "intentional, documented, enforced."

### Decision

**Option A is recommended** because it produces structural consistency at low
cost. The `preferRegistry` helper is ~5 LOC and makes the side-effect-avoidance
explicit. The bridge file shrinks to ~30 LOC matching the other 7 in size.

Option B is the fallback if Option A's `rebuildFromExisting` implementation
encounters a Knowledge-specific field that legitimately requires side-effectful
re-construction (e.g. a derived service that has no stable identity through a
hot-swap). In that case, fall back to Option B with the discovered field
documented in the javadoc.

### Test impact

`KnowledgeSubsystemBridgeTest` already covers:
- `rebuildFromManagers_readsFromRegistryDirectly` — verifies hot-swapped fields
  surface in the snapshot.
- `rebuildFromManagers_toleratesUnregisteredManagers` — verifies null fields stay
  null.

Both contracts must still hold after the refactor. Add one new test:
`rebuildFromManagers_doesNotReInvokeFactorySideEffects` — uses a mocked
`ScheduledExecutorService` injected into the test's `WikiEngine` to verify zero
new `schedule` calls fire during a snapshot rebuild.

The other 7 `*BridgeTest` files remain untouched.

### Documentation

Update `docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md`
with a new section between Phase 11 and the "Phase plan" close-out:

```
### Bridge architecture (post-Phase-11 — stable design)

The 8 `*SubsystemBridge` classes are the supported way for production callers
holding an `Engine` reference to reach typed subsystem services. They survive
Phase 11 by design: the `Engine` API interface deliberately cannot depend on
subsystem modules.

Every bridge follows the pattern: fromLegacyEngine(Engine) prefers the typed
WikiEngine accessor; falls back to a side-effect-free rebuildFromManagers.

KnowledgeSubsystemBridge was the lone asymmetry until Phase 12 (2026-05-XX)
moved its body into KnowledgeSubsystemFactory.rebuildFromExisting.

Retiring the bridges would require either 280 (WikiEngine) casts or a
multi-week Engine-API redesign with subsystem accessors. Neither is justified
by the current cost/benefit. The bridges are a stable design.
```

## Risks

- **Field identity through hot-swap.** Some `KnowledgeSubsystem.Services` fields
  may not be hot-swappable via `setManager` (e.g. derived services whose
  identity depends on a constructor argument from the swapped manager). If
  `preferRegistry` returns the new value but its dependents still point at the
  old, the snapshot is inconsistent. Mitigation: per-field audit in Ckpt 1 to
  identify any derived services; for each, decide either (a) re-derive when its
  source changes, or (b) document as non-hot-swappable in the test contract.
- **Mocked factory side effects in tests.** The other 7 bridges' tests don't
  worry about side effects because their factories are pure. Knowledge's factory
  is not. The new `doesNotReInvokeFactorySideEffects` test must mock or stub the
  side-effectful pieces (`ScheduledExecutorService`, repository constructors,
  extractor startup) to assert zero re-invocation. Brittle if the factory's
  internals change.

## Done when

- `KnowledgeSubsystemBridge.rebuildFromManagers` body is ≤ 10 LOC and delegates
  to `KnowledgeSubsystemFactory.rebuildFromExisting`.
- `KnowledgeSubsystemFactory.rebuildFromExisting` exists with `preferRegistry`
  semantics and zero side-effect re-invocation.
- New test `rebuildFromManagers_doesNotReInvokeFactorySideEffects` ships and
  passes.
- Existing `KnowledgeSubsystemBridgeTest` tests still pass with no modification.
- Decomposition design doc gains a "Bridge architecture (post-Phase-11)"
  section documenting bridges as a stable design.
- `mvn clean install -Pintegration-tests -fae` is green.

## Phases beyond this

Phase 12 (this) closes the asymmetry but explicitly does NOT retire the bridges.
Other decomposition tail items remain candidates for future phases:

- **Phase 13 — WikiEngine slimming (est. 2 days).** `WikiEngine.java` is ~2,000
  LOC vs the Phase 9 goal of "under 300 LOC". Extract `WikiEngineBootstrap`,
  reify `mgr_*` fields, extract `SubsystemSnapshotRegistry`.
- **Phase 14 — WikiContext slimming (est. 1–2 days).** Phase 10 left
  `WikiContext.java` at 875 LOC due to delegation boilerplate. Migrate ~50 call
  sites to the scope classes directly.
- **Phase 15 — ArchUnit freeze sweep (est. 1 day).** 129 frozen legacy
  violations. Re-evaluate, delete the rest.

**Phase 16 (conditional) — Engine API redesign.** If a future scope change
demands that production callers stop holding `Engine` references and start
holding typed-subsystem services directly (constructor injection across plugins,
managers, filters, servlets), THEN the bridges become retirable. Estimated
multi-week effort. Not currently justified by any product or operational
concern.
