# Phase 6: RenderingSubsystem extraction + SpamFilter decomposition — implementation plan

**Spec:** [docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md](../specs/2026-05-05-wikantik-main-decomposition-design.md)
**Status:** complete (2026-05-07)
**Estimated effort:** 4 days
**Goal:** wall off the parser / plugin / filter / diff pipeline behind a typed `RenderingSubsystem.Services` surface. Decompose `SpamFilter` (1003 LOC) along its actual responsibilities. Migrate the ~25 production callsites of `RenderingManager` / `PluginManager` / `FilterManager` / `DifferenceManager` to the typed accessor.

Smaller phase than 5 by blast radius. The rendering subsystem's internals are already cleanly separated; Phase 6 is mostly the typed-bundle wrap, the SpamFilter split, and the consumer migration.

## Scope

**In:**
1. **`RenderingSubsystem.Deps` + `.Services`** records under `com.wikantik.render.subsystem.*`.
2. **`RenderingSubsystemFactory.create(Deps) → Services`** — pure factory consumed by `WikiEngine.initialize()` after Page (Rendering depends on Page for the page-save filter chain seam).
3. **`Services` exposes:**
   - `RenderingManager renderingManager` (the `DefaultRenderingManager` instance)
   - `PluginManager pluginManager`
   - `FilterManager filterManager`
   - `DifferenceManager differenceManager`
   - The four extracted `SpamFilter` helpers (Ckpt 3 — see below)
4. **Decompose `SpamFilter` (1003 LOC)** along the three-plus responsibilities the existing class already segregates (re-audited from the source — the spec's 3-way split was a sketch; the real seams are 4 and the bot-trap utilities sit cleanly to the side):
   - **`SpamRateLimiter`** — page-changes-per-minute, similar-modifications, IP rate-limit + ban tracking. Owns the in-memory IP-ban list and ban-time TTL.
   - **`SpamPatternMatcher`** — word/IP regex blacklist loading + matching, URL-count limit, UTF-8 trap. Owns the spam-patterns and IP-patterns collections.
   - **`SpamExternalSignals`** — Akismet integration + the bot-trap hidden-field check.
   - **`SpamPolicy`** — score / eager strategy selector that aggregates verdicts from the three components above and decides redirect vs. allow. Owns the `errorPage` config and the score thresholds.
   - **`SpamFilter` facade** — keeps the public `PROP_*` constants + the static helpers (`getBotFieldName`, `getSpamHash`, `getHashFieldName`, `isValidUserProfile`) + the `preSave` entry that delegates to `SpamPolicy`. ~250 LOC.
5. **Migrate consumers** — every `engine.getManager(RenderingManager|PluginManager|FilterManager|DifferenceManager.class)` callsite outside the rendering subsystem's own internals → `getSubsystems().rendering().xxx()` (servlets) or `RenderingSubsystemBridge.fromLegacyEngine(engine).xxx()` (non-servlet). 35 callsites total across 25 files.
6. **Subsystem-isolation test** for `RenderingSubsystemFactory` — Mockito stubs for the four manager-level objects; verifies the factory wires them and the SpamFilter helpers correctly.

**Out:**
- The plugin-context refactor (per the spec's Phase 6 note: "Plugins receive their dependencies via plugin context, not engine.getManager()"). The plugin context lives in `wikantik-api` and gets touched by every plugin; that's a separate refactor pass that earns its own checkpoint or even its own phase. Phase 6 leaves the plugin-context shape unchanged.
- Splitting `wikantik-render` / `wikantik-plugin` / `wikantik-filter` into separate Maven modules. Stay in `wikantik-main` under `com.wikantik.render.subsystem.*` (mirror earlier subsystems).
- `MarkdownParser` and `MarkdownRenderer` internals — they're already small (~150 LOC each) and don't carry getManager calls.

## Design

### `RenderingSubsystem` shape

```java
package com.wikantik.render.subsystem;

public final class RenderingSubsystem {

    public record Deps(
        CoreSubsystem.Services core,
        AuthSubsystem.Services auth,
        PageSubsystem.Services page,
        Engine engine                                    // legacy seam — filter registration + plugin instantiation still go through Engine.getManager during boot
    ) {}

    public record Services(
        // Manager-level interfaces (wikantik-api):
        RenderingManager   renderingManager,
        PluginManager      pluginManager,
        FilterManager      filterManager,
        DifferenceManager  differenceManager,

        // Decomposed SpamFilter helpers (Ckpt 3):
        SpamRateLimiter    spamRateLimiter,
        SpamPatternMatcher spamPatternMatcher,
        SpamExternalSignals spamExternalSignals,
        SpamPolicy         spamPolicy
    ) {}
}
```

### `WikiSubsystems` evolution

Order: `(core, persistence, auth, page, rendering, knowledge)`. Knowledge currently consumes the FilterManager indirectly (via the `WikiEngine` reference inside the page-save filter); after Phase 6 this could become an explicit `RenderingSubsystem.Services` field on `KnowledgeSubsystem.Deps`, but only if the audit shows Knowledge needs more than the page-save flow Page already mediates. Defer to a Phase 6 follow-up if useful.

### `SpamFilter` decomposition

The current 1003-LOC class already segregates four buckets internally; the decomposition just turns each bucket into a class with constructor-injected collaborators. The `preSave` method, which today fans out to inline checks against ALL buckets, becomes the body of `SpamPolicy.evaluate(Context, String)` returning a verdict the facade applies. Patterns + IP-list state moves to `SpamPatternMatcher` and is re-loaded from the wiki-pages on the existing schedule (today triggered by save-time `updatePatterns`). Akismet `OkHttp` client moves to `SpamExternalSignals`.

Static helpers (`getBotFieldName`, `getSpamHash`, `getHashFieldName`, `isValidUserProfile`) stay on the facade — JSPs reference them statically and they don't carry state.

### Cross-subsystem dependency edges so far

```
Core ─────┬── PersistenceSubsystem
          │
          ├── AuthSubsystem
          │
          ├── PageSubsystem ─────── KnowledgeSubsystem
          │       │
          │       └── RenderingSubsystem
          │
ServletContext / DataSource ──┘
```

Rendering becomes the fifth typed subsystem. Knowledge depends on Page for the page-save flow; Rendering depends on Page because it owns the FilterManager's pre/post-save chain that drives Page's lifecycle. Dependency direction: Rendering → Page → Persistence → Core.

## Checkpoint plan

Each checkpoint = one commit + the full IT reactor before commit.

| Ckpt | Duration | Concurrency | Model |
|------|----------|-------------|-------|
| 1 — Scaffold | half day | single | Opus |
| 2 — Migrate consumers | half day | 2× parallel | Sonnet/Haiku |
| 3 — Decompose SpamFilter | 1.5 days | single | Sonnet (Opus only if mid-surgery surprises) |
| 4 — Wire decomposed SpamFilter into Services | half day | single | Opus |
| 5 — Verification + close-out | half day | single | Opus |

### Checkpoint 1 — Scaffold (Opus)

- `com.wikantik.render.subsystem.RenderingSubsystem` (`Deps` + `Services` records — the four SpamFilter helper slots stubbed `null` until Ckpt 4).
- `RenderingSubsystemFactory.create(Deps)` reads the four manager-level objects from the engine's legacy registry initially. Returns a populated `Services`.
- `RenderingSubsystemBridge.fromLegacyEngine(Engine)` mirroring the other bridges.
- `WikiSubsystems` adds `rendering()` field, ordered `(core, persistence, auth, page, rendering, knowledge)`.
- `WikiEngine.initialize()` builds Rendering after Page and BEFORE `initKnowledgeGraph` so KG can evolve to consume Rendering in a Phase 6 follow-up.
- `WikiEngine.setManager()` invalidates the snapshot when a `RenderingManager` / `PluginManager` / `FilterManager` / `DifferenceManager` is hot-swapped.
- `RestServletBase.getSubsystems()` reads the typed accessor + bridge fallback.
- `RenderingSubsystemFactoryTest` — Mockito stubs round-trip through the factory.

**Behaviour change:** zero. Decomposition is deferred to Ckpt 3.

### Checkpoint 2 — Migrate consumers (parallel ×2 Sonnet/Haiku)

35 production `engine.getManager(Rendering|Plugin|Filter|Difference Manager.class)` callsites across 25 files. Two concurrent agents along the standard module seam:

**Agent A — REST / MCP / tools / knowledge / observability / api** (~10 callsites in 8 files): RestServletBase subclasses → `getSubsystems().rendering().xxx()`. Filters that don't extend RestServletBase → `RenderingSubsystemBridge.fromLegacyEngine(engine).xxx()`.

**Agent B — wikantik-main interior** (~25 callsites in 17 files): all → `RenderingSubsystemBridge.fromLegacyEngine(engine).xxx()`.

Mechanical work — Haiku fits both slices.

SKIP list (bootstrap-order or self-references):
- WikiEngine.java (engine itself)
- DefaultRenderingManager, DefaultPluginManager, DefaultFilterManager, DefaultDifferenceManager (the impl classes themselves)
- Anything inside `com.wikantik.render.subsystem.*` (already correct)
- Anything under /test/

### Checkpoint 3 — Decompose `SpamFilter` (Sonnet, single agent)

Surgery — but cleaner than DefaultPageManager's because the existing class already segregates the four buckets via private helpers. Approach:

1. **Create the four new helpers** under `com.wikantik.render.subsystem.spam.*`:
   - `SpamRateLimiter` (interface + `DefaultSpamRateLimiter` impl) — owns the IP-ban map + the ban-time TTL.
   - `SpamPatternMatcher` (interface + `DefaultSpamPatternMatcher` impl) — owns the regex collections + the wiki-page-loaded pattern lists; takes `PageSubsystem.Services page` as a collaborator for the pattern reload.
   - `SpamExternalSignals` (interface + `DefaultSpamExternalSignals` impl) — Akismet client + bot-trap hidden-field check.
   - `SpamPolicy` (interface + `DefaultSpamPolicy` impl) — strategy selection; takes the three above as collaborators; produces a `Verdict(allow|redirect, reason)` record.
2. **Move methods + their private helpers verbatim.** SQL/regex strings unchanged. Session message keys unchanged. Akismet API call body unchanged.
3. **`SpamFilter` becomes a facade** of ~250 LOC: keeps every `PROP_*` constant + the four static utility methods + `initialize` (which now constructs the four helpers) + `preSave` (which now calls `spamPolicy.evaluate(...)` and applies the verdict).
4. **Move tests where they're now naturally exercised** — most `SpamFilterTest` cases stay against the facade; tests that exercise just one bucket (e.g. `SpamFilterIPBanTest`) split off into per-helper tests if the surface clearly benefits.

**Behaviour change:** zero. Strategy = score still picks the same threshold; strategy = eager still rejects on the first failed bucket; same redirect target for the same reason.

### Checkpoint 4 — Wire decomposed SpamFilter into Services (Opus)

After Ckpt 3 the four helpers exist. This checkpoint:

- Extends `RenderingSubsystem.Services` with the four helper fields.
- `RenderingSubsystemFactory.create()` extracts the helpers off the existing `SpamFilter` instance (`((DefaultFilterManager) filterManager).getFilter(SpamFilter.class)` or similar — audit during the work) and exposes them on the bundle.
- `RenderingSubsystemBridge` follows the same pattern.
- `RenderingSubsystemFactoryTest` adds assertions that all eight Services fields are non-null.

### Checkpoint 5 — Verification + close-out (Opus)

- Re-run `bin/metrics/measure.sh --label phase_6_close`.
- Update spec § Phase 6 with status + metric deltas.
- Mark this plan complete.
- Refreeze ArchUnit baseline if migrations changed the violation set.

## Risks

1. **`SpamFilter`'s `preSave` is the page-save reject path.** If the decomposition introduces a behaviour drift in any of the four buckets — even a single missed branch — the wiki silently rejects (or accepts) saves it shouldn't. Mitigation: copy method bodies verbatim, run the existing `SpamFilterTest` / `SpamFilter*Test` suite after each helper extraction, plus a manual save→Selenide-flow check after Ckpt 3.

2. **Static spam-trap helpers cross JSP boundary.** `getBotFieldName`, `getSpamHash`, `getHashFieldName` are referenced by edit-page JSPs that emit hidden form fields. They MUST stay on `SpamFilter` with the same signatures and the same return semantics (the hash is part of the form-validation contract). No movement.

3. **Filter registration order.** `DefaultFilterManager` registers page-save filters at engine boot via `addPageFilter(filter, priority)`. Phase 6 doesn't move the registration sites (those stay in `WikiEngine.initialize` + the per-subsystem factories that build their own filters). The decomposed SpamFilter still registers as a single `SpamFilter` instance at its current priority (-1009).

4. **Akismet call site.** The Akismet OkHttp client construction has TLS-handshake side effects on the first call; moving it to `SpamExternalSignals` shifts when "first call" happens during boot. Mitigation: lazy-initialize the client on first use (matches today's behaviour) regardless of where the class lives.

5. **Plugin context (deferred).** The spec calls for plugins to receive dependencies via plugin context, not `engine.getManager()`. That refactor is deferred — it touches every plugin class + the `wikantik-api`'s `PluginContent` interface. Out of Phase 6 scope; tracked as a follow-up.

## Done when

- `RenderingSubsystem.Services` produces the four manager-level objects + four SpamFilter helpers.
- `SpamFilter` is decomposed to ~250 LOC + four helpers each <300 LOC.
- 35 production `engine.getManager(Rendering|Plugin|Filter|Difference Manager.class)` callsites are migrated; auth-internal-style bootstrap-order exemptions documented (none expected — Rendering builds late in the boot sequence).
- `god_classes_over_800` drops by 1 (SpamFilter from 1003 LOC to ~250 LOC).
- Phase 7 (SearchSubsystem) plan can begin against the new Rendering foundation.
