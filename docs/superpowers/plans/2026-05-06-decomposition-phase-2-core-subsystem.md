# Phase 2: CoreSubsystem extraction — implementation plan

**Spec:** [docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md](../specs/2026-05-05-wikantik-main-decomposition-design.md)
**Status:** complete (2026-05-06)
**Estimated effort:** 4–5 days
**Goal:** stop reaching for cross-cutting infrastructure (properties, event bus, leaf managers) through `WikiEngine`. Establish `CoreSubsystem` — the foundation every other subsystem depends on. After Phase 2, `WikiProperties` is the single-source-of-truth for configuration reads, and the small leaf managers are typed services on the same bundle.

## Scope

**In:**
1. **`CoreSubsystem.Services` record** with these fields:
   - `WikiProperties` (new typed wrapper around `java.util.Properties`)
   - `WikiEventBus` (interface re-exposing the existing `WikiEventManager` static singleton as an injectable service)
   - `SystemPageRegistry`, `RecentArticlesManager`, `BlogManager` (existing leaf managers, now reachable via the Services record)
   - `MeterRegistry` (re-exposed; today it lives in `ObservabilityLifecycleExtension`'s static accessor)
2. **Migrate consumers:**
   - 22 `engine.getManager(SystemPageRegistry|RecentArticlesManager|BlogManager.class)` callsites in production code → `getSubsystems().core().xxx()`.
   - 41 `engine.getWikiProperties()` / `engine.getProperties()` callsites → `getSubsystems().core().properties()`.
   - Direct event-bus statics → `getSubsystems().core().eventBus()`. (Count to be measured during the phase; the static surface is `WikiEventManager.fireEvent(...)` etc.)
3. **Subsystem-isolation test** for `CoreSubsystemFactory`.
4. **Delete bridge entries** for the 3 leaf managers in `WikiEngine.initialize()` once consumers migrate.
5. **Knowledge subsystem becomes a Core consumer** — `KnowledgeSubsystem.Deps` adds an explicit `core()` reference; `WikiEngine.initKnowledgeGraph` wires it through. This proves the dependency direction.

**Out:**
- Migrating Properties readers in non-`-main` modules (defer to phase tail-cleanup as in Phase 1).
- Lifting `MeterRegistry` to a top-level service consumer-by-consumer; if it becomes a bottleneck we'll raise it, but most consumers already get a registry passed in.
- Consolidating `wikantik-event`'s public surface — leave `WikiEventManager` static for backwards compat; the new `WikiEventBus` interface routes through it.

## Design

### `CoreSubsystem` shape

```java
package com.wikantik.core.subsystem;

public final class CoreSubsystem {

    public record Deps(
        Properties rawProperties,
        ServletContext servletContext,    // may be null for non-servlet engines
        MeterRegistry meterRegistry       // may be null; factory falls back to SimpleMeterRegistry
    ) {}

    public record Services(
        WikiProperties properties,
        WikiEventBus eventBus,
        MeterRegistry meterRegistry,
        SystemPageRegistry systemPageRegistry,
        RecentArticlesManager recentArticlesManager,
        BlogManager blogManager
    ) {}
}
```

### `WikiProperties` design

A small typed wrapper:

```java
public interface WikiProperties {
    String get( String key );
    String get( String key, String defaultValue );
    int getInt( String key, int defaultValue );
    long getLong( String key, long defaultValue );
    boolean getBoolean( String key, boolean defaultValue );
    Properties asProperties();          // escape hatch for legacy callers
    Iterable<String> propertyNames();
}
```

The default implementation wraps a `java.util.Properties` instance. The `asProperties()` accessor is a deliberate escape hatch — Phase 2 doesn't try to convert *every* reader (some consume the raw `Properties` to pass through to other libraries); it converts the typed-read patterns and leaves passes-through alone, marked as "legacy passthrough" comments.

### `WikiEventBus` design

An interface over the existing `WikiEventManager` mechanism:

```java
public interface WikiEventBus {
    void fireEvent( WikiEvent event );
    void register( WikiEventListener listener, Object source );
    void unregister( WikiEventListener listener, Object source );
}
```

Default implementation delegates to `WikiEventManager` static methods. The interface is for testability — components that depend on the bus can be tested with a fake event bus rather than a live static singleton.

### `WikiSubsystems` evolution

```java
public record WikiSubsystems(
    CoreSubsystem.Services core,            // NEW in Phase 2
    KnowledgeSubsystem.Services knowledge
) { ... }
```

Order matters: `core` precedes `knowledge` because the Knowledge subsystem now depends on Core. `WikiEngine.initialize()` builds Core first, then passes its services into `KnowledgeSubsystem.Deps`.

### Knowledge subsystem becomes a Core consumer

Today `KnowledgeSubsystem.Deps` declares:
```java
public record Deps(
    DataSource dataSource,
    Properties properties,
    SystemPageRegistry systemPageRegistry,
    PageManager pageManager,
    PageSaveHelper pageSaveHelper,
    HubOverviewService.LuceneMlt luceneMlt,
    MeterRegistry meterRegistry
) {}
```

Phase 2 changes the `properties`, `systemPageRegistry`, and `meterRegistry` fields to come from a `CoreSubsystem.Services core` reference. The cleanest signature:

```java
public record Deps(
    DataSource dataSource,
    CoreSubsystem.Services core,             // ← replaces properties + systemPageRegistry + meterRegistry
    PageManager pageManager,                 // Page subsystem (Phase 5)
    PageSaveHelper pageSaveHelper,           // Page subsystem
    HubOverviewService.LuceneMlt luceneMlt   // Search subsystem (Phase 7)
) {}
```

This is the **first cross-subsystem dependency** in the codebase. Establishing the pattern correctly here is what every subsequent phase reuses.

## Checkpoint plan

Each checkpoint = one commit + a full IT run before commit. Subagents (Sonnet) for bulk migrations with mechanical patterns; Opus only for design decisions and integration glue.

### Checkpoint 1 — Subsystem scaffolding (Opus)

- `com.wikantik.core.subsystem.CoreSubsystem` (`Deps` + `Services` records).
- `com.wikantik.core.subsystem.CoreSubsystemFactory.create(Deps) → Services`.
- `com.wikantik.core.subsystem.WikiProperties` interface + `DefaultWikiProperties` impl.
- `com.wikantik.core.subsystem.WikiEventBus` interface + `DefaultWikiEventBus` impl.
- `WikiSubsystems` record adds `core()` field; constructor argument order: `core`, `knowledge`.
- `WikiEngine.initialize()`:
  - Build `CoreSubsystem.Services` from `(props, servletContext, meterRegistry)` after the SystemPageRegistry/RecentArticlesManager/BlogManager are constructed.
  - Stash `WikiSubsystems(coreSubsystem, knowledgeSubsystem)` on the ServletContext.
  - Legacy `managers.put(...)` for the 3 leaf managers stays as bridge.
- Subsystem-isolation test: `CoreSubsystemFactoryTest` proves `create(Deps) → Services` works without `WikiEngine`.

**Behaviour change:** zero. The new types exist; consumers haven't migrated.

**Verification:** full IT green; ArchUnit unchanged.

### Checkpoint 2 — Migrate the 3 leaf managers (subagent, Sonnet)

22 callsites across production code. Map:
- `engine.getManager(SystemPageRegistry.class)` → `getSubsystems().core().systemPageRegistry()`
- `engine.getManager(RecentArticlesManager.class)` → `getSubsystems().core().recentArticlesManager()`
- `engine.getManager(BlogManager.class)` → `getSubsystems().core().blogManager()`

For non-servlet callers (plugins), use `CoreSubsystemBridge.fromLegacyEngine(engine).xxx()` (mirroring `KnowledgeSubsystemBridge`).

**Verification:** zero `getManager(SystemPageRegistry|RecentArticles|Blog.class)` callers in production; full IT green.

### Checkpoint 3 — `WikiProperties` migration (subagent, Sonnet — but gated)

41 `engine.getWikiProperties()` / `engine.getProperties()` callsites in main. Map them to `getSubsystems().core().properties()`.

The subagent should:
1. Migrate the typed-read patterns (`props.getProperty("X")`, `Integer.parseInt(props.getProperty("X", "10"))` → `properties.get("X")`, `properties.getInt("X", 10)`).
2. Leave passes-through (`new Foo(props)`) as legacy with a `properties.asProperties()` shim and a tracked TODO.
3. Compile-check after each file.

**Risk:** Properties consumers are scattered across many files; the Sonnet agent might miss test-fixture impacts. Do a smaller subset first (just one module, e.g. wikantik-rest), verify, then expand.

**Verification:** the subset migrates cleanly; full IT green.

### Checkpoint 4 — `WikiEventBus` migration (Opus)

The event bus is more delicate — it has lifecycle implications (unregister on shutdown, weak references in `WikiEventManager`). Approach:
- Build `DefaultWikiEventBus` that delegates to `WikiEventManager` static methods.
- Migrate the most-used event-firers in wikantik-main (a focused list, ~15 callsites).
- Keep `WikiEventManager` as the implementation; the new bus is the public face.

**Verification:** full IT green; existing event-driven flows (page save, judge runner trigger, async listeners) all still work.

### Checkpoint 5 — KnowledgeSubsystem.Deps adopts `core()` (Opus)

- Change `KnowledgeSubsystem.Deps` to take `CoreSubsystem.Services core` instead of separate `properties`, `systemPageRegistry`, `meterRegistry`.
- `KnowledgeSubsystemFactory.create(Deps)` now reads from `deps.core()` for the affected fields.
- `WikiEngine.initialize()` wires the change.
- `KnowledgeSubsystemFactoryTest` updated to build a fake `CoreSubsystem.Services` instead of passing the three pieces individually.

This proves the cross-subsystem dependency pattern is clean.

**Verification:** the isolation tests still pass; full IT green.

### Checkpoint 6 — Delete bridge registrations (Opus)

For each of `SystemPageRegistry`, `RecentArticlesManager`, `BlogManager`:
- Confirm zero `engine.getManager(X.class)` callers in production.
- Delete `managers.put(X.class, ...)` from `WikiEngine.initialize()`.
- Update `CoreSubsystemBridge` (if introduced) to prefer the typed accessor first.

**Verification:** full IT green; ArchUnit baseline shrinks.

### Checkpoint 7 — Close-out + metrics (Opus)

- Capture metrics: `bin/metrics/measure.sh --label phase_2_close`.
- Document the deltas in the close-out commit.
- Update spec's Phase 2 status.

## Risks

1. **Properties pass-through callers.** Some code constructs other components by handing them a raw `Properties`. We can't migrate those without changing the downstream API. The plan is to leave those as `properties.asProperties()` shims with TODO markers and burn them down in later phases.

2. **Event-bus lifecycle.** `WikiEventManager` uses `WeakReference` for listeners — components that register and don't unregister still rely on a strong reference being held *somewhere* to keep them alive. The new bus interface must preserve this; the impl is a thin delegation, so it does. Watch for tests that register a listener and immediately discard the registering object.

3. **Cross-subsystem dependency creep.** The Knowledge → Core dependency is the first edge of the DAG. If we get the shape wrong here, every subsequent edge inherits the mistake. Spend the time on Checkpoint 5 to get this right.

4. **Properties-bound config defaults.** Many `getInt(props, "X", N)` patterns rely on falling back to `N` when the property isn't set. The new `WikiProperties.getInt(key, default)` must match the legacy `TextUtil.getIntegerProperty` semantics exactly (defaults to N on missing OR malformed). Test edge cases.

## Done when

- `bin/metrics/measure.sh` reports the 3 leaf managers no longer in `WikiEngine.managers`.
- `engine.getManager(SystemPageRegistry|RecentArticles|Blog.class)` callers = 0 in production.
- `engine.getWikiProperties()` callers in `-main` modules: a measured-and-documented reduction (target: 75%+ migrated; the rest are passes-through marked with TODOs).
- `KnowledgeSubsystem.Deps` takes `CoreSubsystem.Services core` instead of separate fields.
- `CoreSubsystemFactoryTest` runs without `TestEngine`.
- Phase 3 (Persistence) plan can begin against this foundation.
