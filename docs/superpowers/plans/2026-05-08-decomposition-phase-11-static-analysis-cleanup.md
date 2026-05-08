# Phase 11: static-analysis cleanup

**Spec:** [docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md](../specs/2026-05-05-wikantik-main-decomposition-design.md) — Phase 11 is the post-decomposition health pass.
**Status:** ready
**Goal:** address concrete findings from the post-Phase-10 PMD/SpotBugs/CPD scan. Reduce `WikiEngine.setManager` complexity; cut SpotBugs noise; fix real bugs (security, sync, leaks); deduplicate bridge ⇄ factory mirrors; chip at the remaining god classes.

## Findings recap (from `/tmp/static-analysis.log` runs against current main)

- **PMD complexity**: 165 violations. Worst NPath: `WikiEngine.setManager` at **191M paths**.
- **SpotBugs**: 450 bugs total; 88% are record-exposes-component noise; ~50 are real.
- **CPD**: 27 duplication blocks at 50-token threshold, biggest cluster is bridge ⇄ factory mirrors.
- **God classes**: 38; top WikiEngine (WMC=392), HubOverviewService (146), DefaultKnowledgeGraphService (126).

## Checkpoint sequence

Every checkpoint runs the **full IT reactor** before commit. **Do not push if IT fails** — diagnose and fix in place.

### Ckpt 1 — `WikiEngine.setManager` switch → class→writer map (Sonnet, 1 day)

The 75-arm `if (clazz == X.class) { mgr_X = ...; ... } else if (clazz == Y.class) ...` switch produces NPath = 191M. Replace with a `Map<Class<?>, BiConsumer<WikiEngine, Object>>` built once at static-init (or in the constructor). Each entry assigns the typed mgr_* field for that class. setManager becomes:

```java
public <T> void setManager(Class<T> clazz, T manager) {
    final BiConsumer<WikiEngine, Object> writer = TYPED_FIELD_WRITERS.get(clazz);
    if (writer != null) writer.accept(this, manager);
    // snapshot rebuild blocks remain (they're domain-specific groupings, not the flat 75-arm switch)
}
```

The 6 snapshot-rebuild blocks (auth, page, core, rendering, search, knowledge, pageGraph) stay as-is — they're already gated on `if (this.<sub> != null)` from the post-Phase-9 fix. The improvement is purely on the field-write path.

Same for `readTypedField` — replace switch with a `Map<Class<?>, Function<WikiEngine, Object>>`.

**Done when:** PMD `WikiEngine.setManager` NPath drops below 50; full IT green; `WikiEngineTest#engineBoot_renderingPipelineConvertsMarkdownToHtml` still passes (boot ordering preserved).

### Ckpt 2 — SpotBugs record-exposes-component noise filter (Haiku, half day)

395 of 450 SpotBugs warnings are `EI_EXPOSE_REP` / `EI_EXPOSE_REP2` on records (Services, Deps, HubDrilldown, etc.). A record's accessors expose its components by language design — these warnings are noise.

Add a SpotBugs filter (`build-support/spotbugs-exclude.xml` or per-module exclude file) that suppresses `EI_EXPOSE_REP*` for classes matching the patterns:
- `*.subsystem.*$Services`
- `*.subsystem.*$Deps`
- Any class annotated as a Java record

Wire the exclude into the SpotBugs Maven plugin config. Verify the bug count drops from 450 to ~50 and the remaining warnings are real.

**Done when:** `mvn -pl wikantik-main spotbugs:spotbugs` reports < 100 bugs; full IT green.

### Ckpt 3 — Triage priority-1 + SECURITY + MT_CORRECTNESS bugs (Sonnet, half day)

Five concrete bugs to fix:

1. `HubOverviewService.synthesizeHubNode:56` — `DM_DEFAULT_ENCODING`. Use `StandardCharsets.UTF_8` explicitly.
2. `DefaultLuceneIndexer:82` — `MS_MUTABLE_ARRAY`. Make the public static array unmodifiable (or copy on read).
3. `KgProposalRepository.applyMachineVerdict:49` — `SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING`. SQL injection risk; bind parameters or whitelist the dynamic fragment.
4. `WikiEngine:111` — `IS2_INCONSISTENT_SYNC`. Some field is read with sync and written without (or vice-versa). Audit field accesses; either synchronize the missing path or document why it's safe.
5. `XMLUserDatabase:69` — `IS2_INCONSISTENT_SYNC`. Same diagnosis pattern.

For each fix, write a regression test where feasible.

**Done when:** SpotBugs reports 0 priority-1 / SECURITY / MT_CORRECTNESS bugs; full IT green.

### Ckpt 4 — CloseResource sweep (Haiku, half day)

PMD reports 9 `CloseResource` sites. Each is an `AutoCloseable` opened without try-with-resources. Walk every site, convert to try-with-resources, run a quick `mvn test -pl <touched module>` per file as you go.

**Done when:** PMD CloseResource count = 0; full IT green.

### Ckpt 5 — Bridge ⇄ Factory dedup (Sonnet, 1 day)

Each `*SubsystemBridge.rebuildFromManagers(WikiEngine)` mirrors its `*SubsystemFactory.create(Deps)`. CPD reports 3 large mirrors; the same pattern exists in all 8 subsystems.

Refactor: bridge.rebuildFromManagers delegates to factory.create with a `Deps` synthesized from the engine. Concrete shape:

```java
// In each bridge:
public static Services rebuildFromManagers(WikiEngine engine) {
    return SubsystemFactory.create(synthDepsFromEngine(engine));
}
```

Where `synthDepsFromEngine` reads the engine's typed accessors. The bridge still owns the `Engine instanceof WikiEngine` guard and the all-null fallback.

**Done when:** CPD duplication for bridge ⇄ factory pairs drops to zero; all subsystem unit tests green; full IT green.

### Ckpt 6 — Split god classes outside WikiEngine (Sonnet → Opus, 2 days)

Three top god classes need decomposition. Each gets its own commit:

- **6a** `HubOverviewService` (WMC=146, 750 LOC) — likely splits into HubProposalProjector + HubMemberQuery + HubRecentChangesAdapter.
- **6b** `DefaultKnowledgeGraphService` (WMC=126, 705 LOC) — likely splits into KgQueryFacade + KgGraphTraversal + KgIngestionGateway.
- **6c** `BootstrapEntityExtractionIndexer` (WMC=113, 766 LOC) — likely splits into ExtractionScheduler + ExtractionRunner + JudgeOrchestrator.

For each: read the class, propose the split, do the extraction, update callers, IT-verify.

**Done when:** each target class drops below WMC=80; god_classes_over_800 stays ≤ 4; full IT green.

### Ckpt 7 — PMD lint sweep (Haiku, half day)

Mechanical cleanup:
- 71 `UnnecessaryImport`
- 34 `UnnecessaryModifier`
- 11 `UnusedPrivateField`
- 8 `LiteralsFirstInComparisons` (e.g. `x.equals("foo")` → `"foo".equals(x)`)
- 7 `CollapsibleIfStatements`
- 4 `UnusedPrivateMethod`

Single sweep, run unit tests + full IT, push.

**Done when:** PMD lint count drops by >130; full IT green.

### Ckpt 8 — Close-out (Opus, half day)

Re-run the full static-analysis suite, capture metrics, update spec, mark plan complete.

**Done when:** `bin/metrics/decomposition-progress.json` has a `phase_11_close` block; spec marked.

## Constraints (apply to every checkpoint)

- Per CLAUDE.md: never `git add -A`; never silent catches.
- Per saved memory `feedback_full_it_after_targeted_fix.md`: full IT reactor before commit.
- Per saved memory `reference_docker_cleanup.md`: clear stale pgvector containers before IT.
- **DO NOT push if IT fails.** Diagnose and fix in place; never rationalize a real failure as pre-existing.
- Per saved memory `feedback_subagent_worktree_cleanup.md`: clean up worktrees between checkpoints.

## Done when

- All 8 checkpoints land green.
- PMD `setManager` NPath < 50.
- SpotBugs total < 100, with 0 priority-1 / SECURITY / MT_CORRECTNESS.
- CloseResource = 0.
- 3 god classes split.
- PMD lint count < 60.
- `phase_11_close` metrics captured.
