# Phase 12: subsystem bridge retirement — implementation plan

**Spec:** [docs/superpowers/specs/2026-05-14-subsystem-bridge-retirement-design.md](../specs/2026-05-14-subsystem-bridge-retirement-design.md)
**Status:** draft
**Estimated effort:** ~1 day end-to-end (Ckpt 2 sub-tasks run in parallel)
**Goal:** delete the 8 `*SubsystemBridge` classes and move their `rebuildFromManagers` logic into private `WikiEngine` helpers; migrate production callers to typed accessors.

## File-by-file inventory

The seven delegating bridges (Phase 11 Ckpt 5 — `c1ab97508`):

| Bridge | LOC | Paired factory | Synth chain |
|---|---|---|---|
| `CoreSubsystemBridge` | ~80 | `CoreSubsystemFactory.create` | reads typed Core fields |
| `AuthSubsystemBridge` | ~75 | `AuthSubsystemFactory.create` | depends on Core |
| `PageSubsystemBridge` | ~70 | `PageSubsystemFactory.create` | depends on Core, Persistence |
| `RenderingSubsystemBridge` | ~70 | `RenderingSubsystemFactory.create` | depends on Core, Page |
| `SearchSubsystemBridge` | ~70 | `SearchSubsystemFactory.create` | depends on Core, Page |
| `PageGraphSubsystemBridge` | ~70 | `PageGraphSubsystemFactory.create` | depends on Core, Page, Persistence |
| `PersistenceSubsystem*` | — | no bridge file; `WikiEngine.getPersistenceSubsystem()` is direct | n/a |

The eighth (manual reader, not a factory delegator):

| Bridge | LOC | Note |
|---|---|---|
| `KnowledgeSubsystemBridge` | ~100 | 23-field manual read from typed `mgr_*` fields; factory excluded due to cron + repo side effects (Phase 11 Ckpt 5 commit message) |

Production caller sites (8 files, 14 call sites):

| File | Bridges called |
|---|---|
| `wikantik-rest/.../RestServletBase.java` | Core, Auth, Page, Rendering, Search, PageGraph, Knowledge (6 bridge calls in one method, lines 162-195) |
| `wikantik-admin-mcp/.../McpToolRegistry.java` | Knowledge |
| `wikantik-knowledge/.../KnowledgeMcpInitializer.java` | Knowledge |
| `wikantik-main/.../DefaultContextRetrievalService.java` | Knowledge |
| `wikantik-main/.../plugin/RelationshipsPlugin.java` | Knowledge |
| `wikantik-tools/.../SearchWikiTool.java` | Knowledge |
| `wikantik-main/.../bootstrap/WikiBootstrapServletContextListener.java` | Knowledge (shutdown path, defensive null-check critical) |
| `wikantik-main/.../WikiEngine.java` | All 8 (via the per-class consumer map at lines 449+ in `setManager`) |

Test files referencing bridges:

| File | Action in this phase |
|---|---|
| `KnowledgeSubsystemBridgeTest.java` | merge into new `WikiEngineSetManagerRebuildTest` |
| `CoreSubsystemBridgeTest.java` … `PageGraphSubsystemBridgeTest.java` (6 tests) | merge into new `WikiEngineSetManagerRebuildTest` |
| `SearchWikiToolTest.java` | comment-only reference; update wording |

## Ckpt 0 — Setup (Opus, ~30 min)

1. Create a worktree at `.worktrees/phase-12-bridge-retirement`.
2. Run `grep -rn "fromLegacyEngine\|rebuildFromManagers" wikantik-*/src/main` and persist the exact line numbers as `phase-12-callsite-snapshot.txt` in the worktree. Used by Ckpt 2 to detect drift.
3. Add ArchUnit rule `no_wikiengine_cast_outside_main` to `wikantik-archtest`: enforces `(WikiEngine)` cast usages live only in `wikantik-main`. Should pass at zero today.
4. Run a baseline `mvn clean install -DskipITs` and record `loc_main` from `bin/metrics/decomposition-progress.json`.

**Done when:** worktree exists; snapshot file committed; ArchUnit rule passes; baseline metric captured.

## Ckpt 1 — Shadow: 8 `rebuild*Subsystem` helpers on `WikiEngine` (Sonnet, ~3 hrs)

Behaviour-zero refactor. Bridges still exist and still callable; `setManager`'s dispatcher stops going through them.

### 1.1 — For each of the 7 delegating bridges

Add a private static method to `WikiEngine.java`:

```java
private static CoreSubsystem.Services rebuildCoreSubsystem(WikiEngine e) {
    return CoreSubsystemFactory.create(synthCoreDeps(e));
}
private static CoreSubsystem.Deps synthCoreDeps(WikiEngine e) {
    // body copied verbatim from CoreSubsystemBridge.synthDepsFromEngine,
    // but cross-bridge calls (e.g. CoreSubsystemBridge.fromLegacyEngine)
    // become typed accessors: e.getCoreSubsystem(), e.getPersistenceSubsystem(), ...
}
```

Repeat for `Auth`, `Page`, `Rendering`, `Search`, `PageGraph`. Total: 14 new private static methods on `WikiEngine` (one `rebuild*` + one `synth*Deps` per bridge), about 250 LOC of body relocation.

### 1.2 — Knowledge bridge body relocation

Copy `KnowledgeSubsystemBridge.rebuildFromManagers` verbatim into a private static `WikiEngine.rebuildKnowledgeSubsystem(WikiEngine e)`. Reads from the 23 typed `mgr_*` fields the engine already exposes. No factory call (matches the existing exclusion documented in commit `c1ab97508`).

### 1.3 — Rewire the `setManager` consumer map

The map at `WikiEngine.java:449+` currently dispatches:

```java
rebuildKnowledge = e -> { if (e.knowledgeSubsystem != null)
    e.knowledgeSubsystem = KnowledgeSubsystemBridge.rebuildFromManagers(e); };
```

Rewrite to:

```java
rebuildKnowledge = e -> { if (e.knowledgeSubsystem != null)
    e.knowledgeSubsystem = rebuildKnowledgeSubsystem(e); };
```

Eight lines change, one per subsystem.

### 1.4 — Verification

Run `mvn clean install -Pintegration-tests -fae`. Must be green. Bridges still exist but `setManager` no longer routes through them.

**Done when:** 8 `rebuild*Subsystem` + 7 `synth*Deps` private methods on `WikiEngine`; setManager dispatcher rewired; full IT green.

## Ckpt 2 — Caller migration (parallel sub-checkpoints, file-disjoint)

Each sub-ckpt rewrites one caller file from `*Bridge.fromLegacyEngine(engine).xService()` to `((WikiEngine) engine).getXSubsystem().xService()`, with a defensive `instanceof WikiEngine` guard where the engine could be a non-`WikiEngine` (plugins, tests).

The pattern at each call site:

```java
// before
final var kg = KnowledgeSubsystemBridge.fromLegacyEngine(engine).kgService();

// after
if (!(engine instanceof WikiEngine we)) return /* same defensive fallback */;
final var kg = we.getKnowledgeSubsystem().kgService();
```

For files with multiple bridge calls (`RestServletBase`), hoist the cast once at the top of the method.

### 2a — `RestServletBase.getSubsystems()` (Haiku, ~30 min)

File: `wikantik-rest/src/main/java/com/wikantik/rest/RestServletBase.java`
Lines 162-195. Six bridge calls in one method. Replace all six with typed accessors via a single `WikiEngine` cast hoisted to the top of `getSubsystems()`. Keep the existing `engine instanceof WikiEngine` guard — that's already there for Persistence.

Verification: `mvn test -pl wikantik-rest -Dtest='*Test'`.

### 2b — admin-mcp callers (Haiku, ~30 min)

File: `wikantik-admin-mcp/src/main/java/com/wikantik/mcp/McpToolRegistry.java`
Line 117. Single Knowledge bridge call. Replace with typed accessor.

Also delete the obsolete `// future: switch to engine.getKnowledgeSubsystem() when KnowledgeSubsystemBridge retires in Phase 9` comment at lines 113-114.

Verification: `mvn test -pl wikantik-admin-mcp -Dtest='*Test'`.

### 2c — knowledge module callers (Haiku, ~30 min)

Files:
- `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/KnowledgeMcpInitializer.java` line 95
- `wikantik-main/src/main/java/com/wikantik/knowledge/DefaultContextRetrievalService.java` line 134

Both call `KnowledgeSubsystemBridge.fromLegacyEngine(engine)` once. Defensive cast required because both can be invoked with a mocked `Engine` in tests.

Verification: `mvn test -pl wikantik-knowledge,wikantik-main -Dtest='KnowledgeMcpInitializerTest,DefaultContextRetrievalServiceTest'`.

### 2d — tools module (Haiku, ~30 min)

File: `wikantik-tools/src/main/java/com/wikantik/tools/SearchWikiTool.java`
Lines 24 (import) and 69 (call). Single Knowledge bridge call. Also update the comment in `SearchWikiToolTest.java` that references `KnowledgeSubsystemBridge.fromLegacyEngine` (no behaviour change).

Verification: `mvn test -pl wikantik-tools`.

### 2e — wikantik-main internal callers (Sonnet, ~45 min)

Files:
- `wikantik-main/src/main/java/com/wikantik/plugin/RelationshipsPlugin.java` line 57 — plugin receives `WikiContext`, must tolerate a non-`WikiEngine` engine; cast must be guarded.
- `wikantik-main/src/main/java/com/wikantik/bootstrap/WikiBootstrapServletContextListener.java` lines 153, 158 — **shutdown path; the typed subsystem snapshot may have already been torn down**, so the typed-accessor call must null-check the returned `KnowledgeSubsystem.Services` and skip `judgeRunner` / `retrievalQualityRunner` close calls when null. This is the highest-risk site in the migration.

Verification: `mvn test -pl wikantik-main -Dtest='RelationshipsPluginTest,WikiBootstrapServletContextListenerTest'` then full IT for the boot/shutdown path.

### Concurrency

2a, 2b, 2c, 2d, 2e all run in parallel — file-disjoint, no `WikiEngine` churn (Ckpt 1 finished). Each sub-ckpt produces an independent commit on the worktree branch. Subagents must verify `phase-12-callsite-snapshot.txt` matches the actual file before touching it (drift detection).

**Done when:** every callsite from the inventory is rewritten; no `*Bridge.fromLegacyEngine` references remain in `wikantik-*/src/main`; per-module `mvn test` is green.

## Ckpt 3 — Delete bridge files (Haiku, ~30 min)

After all Ckpt 2 sub-ckpts land:

```bash
git rm wikantik-main/src/main/java/com/wikantik/core/subsystem/CoreSubsystemBridge.java
git rm wikantik-main/src/main/java/com/wikantik/auth/subsystem/AuthSubsystemBridge.java
git rm wikantik-main/src/main/java/com/wikantik/page/subsystem/PageSubsystemBridge.java
git rm wikantik-main/src/main/java/com/wikantik/render/subsystem/RenderingSubsystemBridge.java
git rm wikantik-main/src/main/java/com/wikantik/search/subsystem/SearchSubsystemBridge.java
git rm wikantik-main/src/main/java/com/wikantik/pagegraph/subsystem/PageGraphSubsystemBridge.java
git rm wikantik-main/src/main/java/com/wikantik/knowledge/subsystem/KnowledgeSubsystemBridge.java
# Also delete the seven paired *BridgeTest.java files
git rm wikantik-main/src/test/java/com/wikantik/*/subsystem/*BridgeTest.java
```

Verification: `mvn clean install -DskipITs` — must compile + green at unit level.

If a compile failure surfaces a missed caller, log it as a Ckpt 2 follow-up rather than fix it inline (preserves the "deletion is mechanical" invariant for review).

**Done when:** all 7 `*Bridge.java` files deleted; 6+ paired test files deleted; build green.

## Ckpt 4 — Snapshot-coherence test (Sonnet, ~45 min)

Add `wikantik-main/src/test/java/com/wikantik/WikiEngineSetManagerRebuildTest.java` covering: for each of the 8 typed subsystems, calling `setManager(SomeServiceClass.class, mock)` after `WikiEngine.initialize(...)` triggers a coherent snapshot rebuild that surfaces the mocked service through the corresponding `getXSubsystem()` accessor.

Eight test methods, one per subsystem. Each:

```java
@Test
void setManager_rebuilds_knowledge_subsystem_snapshot() {
    final WikiEngine engine = TestEngine.build();
    final KnowledgeGraphService mock = mock(KnowledgeGraphService.class);
    engine.setManager(KnowledgeGraphService.class, mock);
    assertSame(mock, engine.getKnowledgeSubsystem().kgService());
}
```

The test lives in the same package as `WikiEngine` so it can verify private rebuild helpers fire (via the public side effect on `getXSubsystem()`).

This is the regression net the deleted `*BridgeTest` files used to provide.

Verification: `mvn test -pl wikantik-main -Dtest=WikiEngineSetManagerRebuildTest`.

**Done when:** 8 test methods green; coverage matches the pre-deletion `*BridgeTest` suite.

## Ckpt 5 — Close-out (Opus, ~30 min)

1. Update `bin/metrics/decomposition-progress.json`: add `subsystem_bridges: 8 → 0`, `loc_main` delta, `archunit_frozen_violations` delta (recompute freeze first).
2. Update `docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md`: add a Phase 12 section under the Phase 11 entry recording shipped commits + metrics.
3. Update this plan's `Status:` to `complete (YYYY-MM-DD)`.
4. Refresh the ArchUnit freeze store via `mvn test -pl wikantik-archtest -Dfreeze.refresh=true` (or equivalent) — some violations from `*Bridge.fromLegacyEngine` callers may naturally retire.
5. Final `mvn clean install -Pintegration-tests -fae` green.
6. Commit + push.

**Done when:** metrics + design doc + plan updated; full IT reactor green; commits pushed.

## Concurrency summary

```
Ckpt 0 ─→ Ckpt 1 ─→ ┌→ Ckpt 2a (RestServletBase, Haiku)             ┐
       (Opus)  (Sonnet)│                                              │
                       ├→ Ckpt 2b (admin-mcp, Haiku)                  ├─→ Ckpt 3 ─→ Ckpt 4 ─→ Ckpt 5
                       ├→ Ckpt 2c (knowledge, Haiku)                  │  (Haiku)   (Sonnet)  (Opus)
                       ├→ Ckpt 2d (tools, Haiku)                      │
                       └→ Ckpt 2e (main internal, Sonnet)             ┘
```

Critical-path total: ~5 hours wall-clock if Ckpt 2 sub-ckpts truly run in parallel. Single-developer total: ~1 working day.

## Risks (cross-checkpoint)

- **Ckpt 1 dependency ordering inside `WikiEngine`.** When `synth*Deps` for one subsystem needs another's typed snapshot (e.g. `synthAuthDeps` needs `getCoreSubsystem()`), the snapshot must be non-null by the time the helper runs. `WikiEngine.initialize()` already builds in DAG order (Phase 9 cleanup), so this should hold — but Ckpt 1's IT run is the only check. If it fails, the inversion can be papered over by reading from the `mgr_*` fields directly in the synth helper, matching what the bridge already does.
- **Ckpt 2e shutdown ordering.** `WikiBootstrapServletContextListener` runs during context destroy. The typed snapshot field may already be null because `WikiEngine.shutdown()` cleared it. Today the bridge tolerates this via its fallback path. The replacement code must null-check `getKnowledgeSubsystem()` and skip the close calls. Add a unit test that simulates "snapshot already torn down" before the migration.
- **Subagent parallelism on shared `WikiEngine.java`.** Ckpt 1 must be a single sequential commit by one agent; do NOT shard the 8 `rebuild*` helpers across parallel sub-ckpts in Ckpt 1 because they all edit `WikiEngine.java`. Ckpt 2 is safe to parallelize because every sub-ckpt touches different files.
- **Worktree cleanup.** Per memory `feedback_subagent_worktree_cleanup.md`: every `.worktrees/agent-*` from Ckpt 2 sub-agents must be force-removed via `git worktree remove -f -f` before Ckpt 5 measures metrics.

## Done when (phase-level)

- All 7 `*Bridge.java` files deleted (8 if you count `KnowledgeSubsystemBridge`).
- 6+ `*BridgeTest.java` files deleted.
- `WikiEngine.java` carries 8 private `rebuild*Subsystem` static methods (plus 7 paired `synth*Deps`).
- `WikiEngineSetManagerRebuildTest` ships with 8 test methods (one per subsystem).
- Zero production callers of `*Bridge.fromLegacyEngine` (grep enforced).
- ArchUnit `no_get_manager_anywhere` still at zero violations; new `no_wikiengine_cast_outside_main` passing.
- Full IT reactor green.
- `bin/metrics/decomposition-progress.json` updated; design doc updated.
