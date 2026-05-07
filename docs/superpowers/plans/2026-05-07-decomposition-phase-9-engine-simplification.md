# Phase 9: WikiEngine simplification + registry deletion — implementation plan

**Spec:** [docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md](../specs/2026-05-05-wikantik-main-decomposition-design.md)
**Status:** ready
**Estimated effort:** 4–5 days
**Goal:** delete what the prior phases made dead. `WikiEngine.managers` map + `getManager(Class<T>)` method gone. `WikiEngine.initialize()` becomes a 30–50 line DAG-build. `TestEngine` either thin or replaced by `WikiSubsystems.forTesting(...)`. ArchUnit forbids `engine.getManager(...)` anywhere.

## Current state (start-of-phase)

- `WikiEngine.java`: **1909 LOC** (target: <300)
- Production `getManager` callers across the 5 modules: **63**
  - wikantik-main: 51 (big bulk — internal cross-manager wiring inside WikiEngine itself, plus a few outside)
  - wikantik-rest: 9
  - wikantik-knowledge: 2
  - wikantik-observability: 1
  - wikantik-admin-mcp + wikantik-tools: 0 each (clean already)
- Phase 8 residuals that still need a home:
  - `StructuralIndexService`, `PageGraphService` — need a `PageGraphSubsystem.Services`
  - `ContentIndexRebuildService`, `NewsPageGenerator` — admin-side services
  - `CachingManager` — utility manager
  - `ReferenceManager` mostly migrated by Phase 8 but a few internal callers remain

## Scope

**In:**
1. **Extract `PageGraphSubsystem`.** Distinct from `PageSubsystem` per CLAUDE.md's Page Graph vs Knowledge Graph distinction. Services: `StructuralIndexService`, `PageGraphService`, `ReferenceManager` (move from PageSubsystem), `ContentIndexRebuildService` (the rebuild jobs are page-graph operations).
2. **Decide where `NewsPageGenerator` and `CachingManager` go.**
   - `NewsPageGenerator` → likely `RenderingSubsystem.Services` (it's a content-rendering helper) or a new `ContentSubsystem`.
   - `CachingManager` → `CoreSubsystem.Services` (it's a cross-cutting utility).
3. **Migrate the remaining 63 production callers** to typed subsystem accessors.
4. **Strip `WikiEngine.managers` map + `getManager` API.** Internal references inside `WikiEngine.initialize()` and helpers swap to direct field access on the just-constructed subsystem services records.
5. **Reduce `WikiEngine.java` to <300 LOC.** A lot of the bulk is helper methods that belong on subsystem factories. Move them.
6. **Add `WikiSubsystems.forTesting(...)`** factory that takes a sparse list of overrides and produces a fully-stubbed bundle. Replaces most `TestEngine.setManager(mock)` patterns. Old patterns can stay in the short term; new tests use the typed factory.
7. **`TestEngine` becomes thin.** Either: delete it and have tests construct subsystems directly, OR keep a 50-LOC convenience shell that calls `WikiSubsystems.forTesting`. Pick whichever keeps existing tests compiling with the smallest churn.
8. **ArchUnit final guard:** `no_get_manager_anywhere` — the API is forbidden. Freeze any unavoidable bootstrap exception (there should be zero or one).

**Out:**
- WikiContext decomposition (Phase 10).
- Provider abstraction overhaul (PageProvider/AttachmentProvider lifecycles stay as-is for now).
- Any test-engine work beyond the minimum needed to keep the suite green.

## Checkpoints

| Ckpt | Duration | Concurrency | Model | Description |
|------|----------|-------------|-------|-------------|
| 1 — Extract PageGraphSubsystem | 1 day | single | Sonnet | Scaffold; add Services + Factory + Bridge with `StructuralIndexService` + `PageGraphService` + `ReferenceManager` + `ContentIndexRebuildService`. Migrate the ~12 callsites referencing those services. |
| 2 — Place residual services | half day | single | Haiku | `NewsPageGenerator` → `RenderingSubsystem.Services`. `CachingManager` → `CoreSubsystem.Services`. Migrate their callers. |
| 3 — Migrate residual `wikantik-main` callers | 1 day | single | Sonnet | The 51 production callsites inside `wikantik-main` that aren't `WikiEngine.initialize` itself. Target: zero `getManager` outside the engine init. |
| 4 — Slim `WikiEngine.initialize()` | 1 day | single | Sonnet | Move helpers onto subsystem factories. Reduce WikiEngine.java toward <500 LOC (full <300 may require a follow-up). |
| 5 — Delete the registry + ArchUnit final ban | half day | single | Opus | Drop `managers` map + `getManager(Class)` from WikiEngine + Engine interface. Add `WikiSubsystems.forTesting(...)`. Make TestEngine thin. ArchUnit `no_get_manager_anywhere`. |
| 6 — Close-out | half day | single | Opus | Metrics, spec update, plan complete. |

Sub-agents must run the **full IT reactor** before each commit (`mvn clean install -Pintegration-tests -fae` from primary working dir). Per memory `feedback_subagent_worktree_cleanup.md`, clean up `.claude/worktrees/agent-*` before each `bin/metrics/measure.sh`. Per memory `reference_docker_cleanup.md`, clear stale pgvector containers on port 55432 before re-running IT.

## Risks

1. **Snapshot invalidation already burned us once in Phase 8.** Any new `Services` field added in Ckpts 1+2 needs a corresponding `setManager`-invalidation entry in `WikiEngine.setManager`. Once Ckpt 5 deletes setManager itself, the question becomes moot — but Ckpts 1–4 still need the discipline.
2. **TestEngine churn.** Many tests construct TestEngine and call `setManager(mock)`. The minimum-churn path is: keep the Mock-via-setManager shim alive until Ckpt 5; then in Ckpt 5 the shim becomes a one-line wrapper around `WikiSubsystems.forTesting(...)`. Don't try to migrate every test in one pass — leave tests using the shim, just make the shim work without a `managers` map.
3. **`getManager` calls inside `WikiEngine.initialize()` are circular.** The engine is constructing the registry while reading from it. Untangling means each subsystem-factory takes only the subsystems it depends on (per the DAG already established by Phases 1–7). Some helpers like `initReferenceManager` move onto `PageGraphSubsystemFactory`.
4. **`Engine` interface change.** `Engine.getManager(Class)` is on the public API surface. Removing it breaks any external plugin that called it. Decision: delete from `Engine` interface (this is a planned breaking change per the spec). Any plugin that compiled against the old API gets a deprecation note in the close-out.

## Done when

- `WikiEngine.java` < 500 LOC (relaxed from spec's <300 if the further trim turns into Phase 9.5).
- Zero `engine.getManager(` calls in production code (excluding nothing — even bootstrap goes through subsystem factories).
- `WikiEngine.managers` map deleted, `getManager(Class)` method deleted from WikiEngine + Engine interface.
- ArchUnit `no_get_manager_anywhere` rule passing with zero frozen violations.
- `WikiSubsystems.forTesting(...)` exists.
- `TestEngine` is < 100 LOC or deleted.
- Full IT reactor green.

If WikiEngine.java doesn't get below 500 LOC by Ckpt 4, document the residual god-class and fold it into Phase 10 (alongside WikiContext).
