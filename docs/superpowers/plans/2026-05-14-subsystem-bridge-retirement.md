# Phase 12: KnowledgeSubsystemBridge consistency fix — implementation plan

**Spec:** [docs/superpowers/specs/2026-05-14-subsystem-bridge-retirement-design.md](../specs/2026-05-14-subsystem-bridge-retirement-design.md)
**Status:** draft (revision 2 — scope corrected after callsite-inventory audit)
**Estimated effort:** ~half day (Ckpt 1 parallelism is limited; Ckpt 2 is independent)
**Goal:** make `KnowledgeSubsystemBridge.rebuildFromManagers` delegate to a new `KnowledgeSubsystemFactory.rebuildFromExisting` adapter so the 8 bridges share one structural pattern; document the bridges as a stable design.

## What revision 2 dropped

Revision 1 proposed retiring all 8 `*SubsystemBridge` classes. A callsite
inventory found **280 production callers across 100 files in 6 modules** —
the bridges are the supported `Engine → typed-subsystem` accessor pattern,
not vestigial code. Retiring them would require either 280 `(WikiEngine)`
casts or a multi-week `Engine` API redesign. Neither is justified.

Phase 12 narrows to the one architectural inconsistency from Phase 11 Ckpt 5:
`KnowledgeSubsystemBridge` reads the manager registry manually instead of
delegating to its paired factory, because the factory has side effects. Fix
the asymmetry by adding a side-effect-free `rebuildFromExisting` factory
adapter.

## Ckpt 0 — Field audit (Haiku, ~30 min)

Read `KnowledgeSubsystem.Services` and `KnowledgeSubsystemFactory.create`.
Produce a table mapping each of the 23 Services fields to:
- Its current source in `KnowledgeSubsystemBridge.rebuildFromManagers` (always
  `engine.getManager(X.class)` today, but verify).
- Whether the field is hot-swappable via `WikiEngine.setManager(X.class, T)` —
  consult the setManager dispatcher map at `WikiEngine.java:449+`.
- Whether the field is a derived service whose identity depends on another
  field (e.g. `KgCurationOps` is synthesised from `KgService + PageManager +
  PageSaveHelper + KgExcludedPagesRepository`).

Output: a markdown table written to
`docs/superpowers/plans/2026-05-14-bridge-field-audit.md`. This is the
authoritative input for Ckpt 1.

**Done when:** the field-audit doc lists all 23 fields with three columns
(source / hot-swappable / derived-from), reviewed before Ckpt 1 starts.

## Ckpt 1 — Add `KnowledgeSubsystemFactory.rebuildFromExisting` (Sonnet, ~2 hrs)

Add a new public static method to `wikantik-main/src/main/java/com/wikantik/knowledge/subsystem/KnowledgeSubsystemFactory.java`:

```java
public static KnowledgeSubsystem.Services rebuildFromExisting(
        final WikiEngine engine,
        final KnowledgeSubsystem.Services existing) {
    if (existing == null) {
        // First-init path — no existing snapshot to preserve. Mirror the
        // bridge's current direct-read shape.
        return readFromManagerRegistry(engine);
    }
    return new KnowledgeSubsystem.Services(
        preferRegistry(engine, KnowledgeGraphService.class, existing.kgService()),
        // ... 22 more fields, derived from the Ckpt 0 audit table ...
        rebuildKgCurationOps(engine, existing));
}

private static <T> T preferRegistry(WikiEngine engine, Class<T> klass, T existing) {
    final T fromRegistry = engine.getManager(klass);
    return fromRegistry != null ? fromRegistry : existing;
}

private static KgCurationOps rebuildKgCurationOps(
        WikiEngine engine, KnowledgeSubsystem.Services existing) {
    // ... same logic as today's bridge but reads upstream from existing
    //     when the upstream manager hasn't been swapped ...
}

// readFromManagerRegistry is the old bridge body, moved here verbatim
// for the first-init path.
private static KnowledgeSubsystem.Services readFromManagerRegistry(WikiEngine engine) { ... }
```

**No new side effects.** No `schedule` calls, no repository construction, no
extractor startup. Only field-by-field synthesis from existing instances and the
manager registry.

For each derived service identified in Ckpt 0 (e.g. `KgCurationOps`), implement
a per-field rebuild helper that:
- If none of its upstream managers were swapped (i.e. all `preferRegistry`
  calls returned the `existing` value), return the existing derived service.
- Otherwise, reconstruct the derived service from the new upstream values.

**Verification:** `mvn test-compile -pl wikantik-main -q` clean. No production
behaviour change yet — bridge still calls its own `rebuildFromManagers`.

**Done when:** `rebuildFromExisting` compiles cleanly; signatures match the
audit table; no IDE warnings on missing fields.

## Ckpt 2 — Rewire bridge + add side-effect test (Sonnet, ~1 hr)

### 2.1 — Trim the bridge body

`wikantik-main/src/main/java/com/wikantik/knowledge/subsystem/KnowledgeSubsystemBridge.java`:

```java
public static KnowledgeSubsystem.Services rebuildFromManagers(WikiEngine engine) {
    return KnowledgeSubsystemFactory.rebuildFromExisting(
        engine, engine.getKnowledgeSubsystem());
}
```

Delete the now-dead 23-field synthesis body. Update the bridge's class-level
javadoc to match the other 7 bridges' shape ("delegates to `XSubsystemFactory.rebuildFromExisting`").

### 2.2 — Add side-effect-absence test

New test method in `wikantik-main/src/test/java/com/wikantik/knowledge/subsystem/KnowledgeSubsystemBridgeTest.java`:

```java
@Test
void rebuildFromManagers_doesNotReInvokeFactorySideEffects() {
    // Build a WikiEngine with a pre-populated KnowledgeSubsystem snapshot.
    // Track invocations on the cron ScheduledExecutorService, the
    // BootstrapEntityExtractionIndexer, and repository constructors via
    // Mockito spies.
    final WikiEngine engine = TestEngine.build();
    final ScheduledExecutorService scheduler = spyOn(...);
    final BootstrapEntityExtractionIndexer extractor = spyOn(...);

    // Hot-swap one manager.
    engine.setManager(KnowledgeGraphService.class, mock(KnowledgeGraphService.class));

    // The setManager consumer fired rebuildFromManagers under the hood.
    // Assert zero new schedule / extractor-start calls.
    verify(scheduler, never()).schedule(any(), anyLong(), any());
    verify(scheduler, never()).scheduleAtFixedRate(any(), anyLong(), anyLong(), any());
    verify(extractor, never()).start();
    // Add similar verifications for any other side-effectful constructor or
    // start method identified in Ckpt 0.
}
```

Existing tests (`rebuildFromManagers_readsFromRegistryDirectly`,
`rebuildFromManagers_toleratesUnregisteredManagers`) keep passing without
modification.

### 2.3 — Verification

`mvn test -pl wikantik-main -Dtest=KnowledgeSubsystemBridgeTest` — three test
methods all green.

**Done when:** bridge's `rebuildFromManagers` body ≤ 10 LOC; three tests green;
side-effect test asserts zero re-invocation.

## Ckpt 3 — Documentation + close-out (Opus, ~30 min)

### 3.1 — Decomposition design doc

Append a new section to `docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md` between Phase 11 and the "Phase plan" close-out:

```
### Bridge architecture (post-Phase-12 — stable design)

The 8 *SubsystemBridge classes are the supported way for production callers
holding an Engine reference to reach typed subsystem services. They survive
Phase 11+ by design: the Engine API interface in wikantik-api deliberately
cannot depend on subsystem modules.

Every bridge follows one pattern: fromLegacyEngine(Engine) prefers the typed
WikiEngine accessor and falls back to a side-effect-free rebuildFromManagers
that delegates to its paired XSubsystemFactory.

KnowledgeSubsystemBridge was the lone asymmetry until Phase 12 (commit XXX)
moved its body into KnowledgeSubsystemFactory.rebuildFromExisting, restoring
structural consistency with the other 7 bridges.

Retiring the bridges would require either 280 (WikiEngine) casts at call sites
or a multi-week Engine-API redesign exposing typed-subsystem accessors. Neither
is justified by current product or operational concerns. The bridges are a
stable design.
```

### 3.2 — Update breadcrumb commit message

`5845353a3 docs(mcp): breadcrumb for Phase 9 KnowledgeSubsystemBridge retirement`
predicted retirement that didn't happen. Add a brief note in the
decomposition design doc's Phase 11 section noting the breadcrumb is
superseded by the Phase 12 consistency fix.

### 3.3 — Metrics

Update `bin/metrics/decomposition-progress.json`:
- `subsystem_bridges_uniform_pattern: 7 → 8`
- `loc_main` delta (small — `rebuildFromExisting` body moves from bridge to
  factory; the delta is the factory growth minus the bridge shrink)

### 3.4 — Final verification

`mvn clean install -Pintegration-tests -fae` green. Push.

**Done when:** design doc updated; metrics file updated; full IT green; commits
pushed.

## Concurrency summary

```
Ckpt 0 (Haiku, 30m) ─→ Ckpt 1 (Sonnet, 2h) ─→ Ckpt 2 (Sonnet, 1h) ─→ Ckpt 3 (Opus, 30m)
```

Sequential. The work is too small for meaningful parallelism — each ckpt's
output is the next ckpt's input.

**Total wall-clock:** ~4 hours.

## Done when (phase-level)

- `KnowledgeSubsystemBridge.rebuildFromManagers` body ≤ 10 LOC, delegates to
  `KnowledgeSubsystemFactory.rebuildFromExisting`.
- `KnowledgeSubsystemFactory.rebuildFromExisting` exists, with field-by-field
  audit table preserved as `docs/superpowers/plans/2026-05-14-bridge-field-audit.md`.
- `rebuildFromManagers_doesNotReInvokeFactorySideEffects` test passes.
- Decomposition design doc has a new "Bridge architecture (post-Phase-12)"
  section formally documenting the bridges as a stable design.
- Full IT reactor green.
- Commits pushed.
