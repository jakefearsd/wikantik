# Phase 1: KnowledgeSubsystem promotion — implementation plan

**Spec:** [docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md](../specs/2026-05-05-wikantik-main-decomposition-design.md)
**Status:** ready
**Estimated effort:** 5–7 days
**Goal:** finish what `KnowledgeGraphServiceFactory.Services` started. Establish the `KnowledgeSubsystem` boundary, make every Knowledge-flavored service reachable via constructor injection (or a typed lookup at the servlet boundary), and demonstrate isolated subsystem testing without `WikiEngine`. Set the pattern that Phases 2–8 will reuse.

## Scope

**In:** the 25 services currently registered in `WikiEngine.managers` that the spec assigns to `KnowledgeSubsystem`:

| Service | Today (registered as) | Lives in |
|---|---|---|
| `KnowledgeGraphService` | interface (`DefaultKnowledgeGraphService` impl) | wikantik-main |
| `KgProposalJudgeService`, `JudgeRunner`, `KgMaterializationService` | interface + impls | wikantik-main |
| `KgJudgeTimeoutRepository` | interface (`Jdbc*` impl) | wikantik-main |
| `BootstrapEntityExtractionIndexer`, `AsyncEntityExtractionListener` | concrete | wikantik-main |
| `KgInclusionPolicy`, `ReconciliationJobRunner` | concrete | wikantik-main |
| `KgClusterPolicyRepository`, `KgExcludedPagesRepository` | concrete | wikantik-main |
| `HubProposalService`, `HubDiscoveryService`, `HubOverviewService` | concrete | wikantik-main |
| `HubProposalRepository`, `HubDiscoveryRepository` | concrete | wikantik-main |
| `ContentChunkRepository`, `ChunkProjector`, `ChunkEntityMentionRepository` | concrete | wikantik-main |
| `MentionIndex`, `NodeMentionSimilarity` | concrete | wikantik-main |
| `ForAgentProjectionService`, `RetrievalQualityRunner`, `ContentIndexRebuildService` | concrete | wikantik-main |
| `PageGraphService` | interface | wikantik-main |
| `KgProposalJudgeService` consumers + admin-MCP tool consumers | as above | wikantik-rest, wikantik-admin-mcp, wikantik-knowledge |

**Out:**
- `StructuralIndexService`, `StructuralIndexEventListener`, `PageVerificationDao`, `TrustedAuthorsDao` — Page Subsystem, Phase 5.
- `BootstrapEmbeddingIndexer`, `EmbeddingIndexService`, all `wikantik.search.hybrid.*` — Search Subsystem, Phase 7.
- `WikiEngine.initialize()` simplification — Phase 9.
- Decomposing the God-classes inside Knowledge (e.g., `JdbcKnowledgeRepository` 1561 lines) — Phase 3 covers it as a Persistence concern.

## Design

### `KnowledgeSubsystem` shape

```java
package com.wikantik.knowledge.subsystem;

public final class KnowledgeSubsystem {

    /** What the subsystem requires from upstream subsystems / globals. */
    public record Deps(
        DataSource dataSource,
        Properties properties,
        SystemPageRegistry systemPageRegistry,        // Core (Phase 2)
        PageManager pageManager,                      // Page (Phase 5)
        PageSaveHelper pageSaveHelper,                // Page (Phase 5)
        HubOverviewService.LuceneMlt luceneMlt,       // Search (Phase 7) — optional, may be null
        MeterRegistry meterRegistry                   // Core (Phase 2) — optional
    ) {}

    /** What the subsystem exposes to downstream consumers. */
    public record Services(
        KnowledgeGraphService kgService,
        KgProposalJudgeService judgeService,                    // null when disabled
        JudgeRunner judgeRunner,                                // null when disabled
        KgMaterializationService kgMaterialization,
        KgJudgeTimeoutRepository judgeTimeoutRepository,
        BootstrapEntityExtractionIndexer bootstrapExtractor,
        AsyncEntityExtractionListener extractionListener,
        KgInclusionPolicy kgInclusionPolicy,
        ReconciliationJobRunner reconciliationJobRunner,
        KgClusterPolicyRepository kgClusterPolicyRepository,
        KgExcludedPagesRepository kgExcludedPagesRepository,
        HubProposalService hubProposalService,
        HubDiscoveryService hubDiscoveryService,
        HubOverviewService hubOverviewService,
        HubProposalRepository hubProposalRepository,
        HubDiscoveryRepository hubDiscoveryRepository,
        ContentChunkRepository contentChunkRepository,
        ChunkProjector chunkProjector,
        ChunkEntityMentionRepository chunkEntityMentionRepository,
        MentionIndex mentionIndex,
        NodeMentionSimilarity nodeMentionSimilarity,
        ForAgentProjectionService forAgentProjectionService,
        RetrievalQualityRunner retrievalQualityRunner,
        ContentIndexRebuildService contentIndexRebuildService,
        PageGraphService pageGraphService,
        FrontmatterDefaultsFilter frontmatterDefaultsFilter,    // Rendering will consume; co-located today
        HubSyncFilter hubSyncFilter                             // same
    ) {}
}

public final class KnowledgeSubsystemFactory {
    public static KnowledgeSubsystem.Services create( KnowledgeSubsystem.Deps deps ) { ... }
}
```

The body of `KnowledgeSubsystemFactory.create` is essentially the existing `KnowledgeGraphServiceFactory.Services create(...)` body, refactored to:
- Take a `Deps` record instead of seven parameters.
- Stop returning a flat record that mixes Knowledge + Rendering filters; instead return only Knowledge-flavored services and expose `frontmatterDefaultsFilter` and `hubSyncFilter` as Knowledge outputs (they are Knowledge-driven filters that the Rendering subsystem will consume in Phase 6 — for Phase 1 they ride along).

### `WikiSubsystems` accessor

```java
package com.wikantik;

/** Bundle of all extracted subsystems' Services records. Stashed on the
 *  ServletContext at boot so servlets can reach subsystems without going
 *  through the legacy WikiEngine.managers map. */
public record WikiSubsystems(
    KnowledgeSubsystem.Services knowledge
    // PersistenceSubsystem.Services persistence,   // Phase 3
    // AuthSubsystem.Services auth,                 // Phase 4
    // PageSubsystem.Services page,                 // Phase 5
    // RenderingSubsystem.Services rendering,       // Phase 6
    // SearchSubsystem.Services search,             // Phase 7
    // CoreSubsystem.Services core                  // Phase 2
) {
    public static final String SERVLET_CONTEXT_ATTRIBUTE = "com.wikantik.WikiSubsystems";
}
```

### Servlet bridge

`com.wikantik.bootstrap.WikiBootstrapServletContextListener` already builds the engine and stashes things on `ServletContext`. Phase 1 adds:

```java
// Inside contextInitialized after the engine is up:
final WikiSubsystems subsystems = new WikiSubsystems( engine.getKnowledgeSubsystem() );
servletContext.setAttribute( WikiSubsystems.SERVLET_CONTEXT_ATTRIBUTE, subsystems );
```

`WikiEngine.getKnowledgeSubsystem()` is a new accessor returning the `KnowledgeSubsystem.Services` record stashed during `initialize()`. The `managers.put(...)` calls for KG-flavored services stay during the migration (they delegate to the same Services record), so legacy `getManager()` callers keep working until they're migrated one by one.

`RestServletBase` gains a protected helper:

```java
protected WikiSubsystems getSubsystems() {
    final ServletContext ctx = getServletContext();
    return (WikiSubsystems) ctx.getAttribute( WikiSubsystems.SERVLET_CONTEXT_ATTRIBUTE );
}
```

Servlets migrate from `getEngine().getManager(KgXxx.class)` to `getSubsystems().knowledge().xxx()`.

### Bridge invariants

While Phase 1 is in flight, both lookup paths are alive:
- `engine.getManager(KnowledgeGraphService.class)` → still works, delegates to the Services record.
- `getSubsystems().knowledge().kgService()` → new path, what consumers should be using.

When the last `getManager()` caller for a given KG service is gone, the `managers.put(...)` line for that service is removed in the same commit. By the end of Phase 1, all KG-related entries in `WikiEngine.managers` are gone; the ArchUnit freeze store has a bunch of entries removed.

## Checkpoint plan (each = one commit + full IT)

Each checkpoint must end with `mvn clean install -Pintegration-tests -fae` green before commit. Tooling: subagents (Sonnet) for bulk migrations with a mechanical pattern; Opus only for design decisions and integration glue.

### Checkpoint 1 — Subsystem scaffolding (Opus)

Files:
- `wikantik-main/src/main/java/com/wikantik/knowledge/subsystem/KnowledgeSubsystem.java` (new) — the `Deps` and `Services` records, the `KnowledgeSubsystemFactory.create(...)` method.
- `wikantik-main/src/main/java/com/wikantik/WikiSubsystems.java` (new) — top-level record + ServletContext attribute key.
- `wikantik-main/src/main/java/com/wikantik/WikiEngine.java` — `initialize()` calls `KnowledgeSubsystemFactory.create(deps)`, stashes the result, exposes `getKnowledgeSubsystem()`. Legacy `managers.put(...)` calls for KG services still happen, populated from the new Services record.
- `wikantik-main/src/main/java/com/wikantik/bootstrap/WikiBootstrapServletContextListener.java` — stash `WikiSubsystems` on ServletContext.
- `wikantik-rest/src/main/java/com/wikantik/rest/RestServletBase.java` — add `getSubsystems()` helper.
- The old `KnowledgeGraphServiceFactory` becomes a deprecated thin wrapper that delegates to `KnowledgeSubsystemFactory`. (Deletion in Checkpoint 9.)

**Behaviour change:** none. Same services, same wiring, same registrations.

**Verification:** full IT green; ArchUnit baseline unchanged.

### Checkpoint 2 — Subsystem-level integration test (Opus)

Files:
- `wikantik-main/src/test/java/com/wikantik/knowledge/subsystem/KnowledgeSubsystemFactoryTest.java` (new)

Test does:
1. Build a `KnowledgeSubsystem.Deps` with Testcontainers PG, mock `PageManager`, mock `PageSaveHelper`, null `LuceneMlt`, real `SimpleMeterRegistry`.
2. Call `KnowledgeSubsystemFactory.create(deps)`.
3. Assert every field of the returned `Services` record is non-null.
4. Exercise one service end-to-end (e.g., create a node via `kgService`, verify it persists).

**Verification:** test passes; full IT green.

### Checkpoint 3 — Migrate `wikantik-rest` consumers (subagent, Sonnet)

Subagent task: in `wikantik-rest`, replace every `engine.getManager(KgXxx.class)` (where `KgXxx` is in the Phase 1 inventory above) with `getSubsystems().knowledge().xxx()`. List of expected files to touch:
- `AdminKnowledgeResource` (heaviest)
- `AdminHubDiscoveryResource`
- `KgPolicyAdminResource`
- `KnowledgeMcpResource` (if present)
- Any other servlet that imports a KG class

Subagent prompt boundaries:
- Read each file fully before editing.
- Do not change behaviour — only change the lookup mechanism.
- Preserve null-handling: `getManager()` returns null when service unavailable; `getSubsystems().knowledge()` is non-null but `judgeService()` etc. can still be null when disabled. Audit each call site.
- Run `mvn test-compile -pl :wikantik-rest -am -DskipTests` after each file.

**Verification:** count of `engine.getManager(` callers in `wikantik-rest` for the Phase 1 services drops to zero; full IT green.

### Checkpoint 4 — Migrate `wikantik-admin-mcp` consumers (subagent, Sonnet)

Same pattern, narrower scope. The admin MCP tools instantiate via a factory; they receive the engine. Likely refactor: tool factory takes `WikiSubsystems` instead of `WikiEngine`.

**Verification:** zero KG `getManager()` callers in wikantik-admin-mcp; full IT green.

### Checkpoint 5 — Migrate `wikantik-knowledge` consumers (subagent, Sonnet)

The knowledge MCP tools live in this module. Same migration pattern. After this checkpoint, the only remaining KG `getManager()` callers are in `wikantik-main` (e.g., from event listeners). 

**Verification:** zero KG `getManager()` callers in wikantik-knowledge; full IT green.

### Checkpoint 6 — Migrate `wikantik-main` internal consumers (Opus or subagent)

Inside wikantik-main itself, services that consume Knowledge services should already receive them via constructor (per the design that's been growing). Audit and convert any remaining `engine.getManager(KgXxx.class)` callers in `wikantik-main/src/main` to constructor injection. This is generally a small list because most KG-flavored services were already constructor-built.

**Verification:** ArchUnit freeze store shrinks; remaining frozen violations are non-KG; full IT green.

### Checkpoint 7 — Delete bridge registrations (Opus)

For each `managers.put(KgXxx.class, ...)` line in `WikiEngine.initialize()` whose target service is no longer reached via `getManager()`, delete the line. Run a `grep` for each class to confirm zero callers before each deletion. If a deletion breaks a hidden consumer, the IT will catch it.

After this checkpoint:
- `WikiEngine.managers` no longer contains any KG-flavored service.
- ArchUnit freeze store is updated (regenerate by deleting the store file and running tests once with `freeze.refreeze=true`).

**Verification:** zero `managers.put(KgXxx)` lines in WikiEngine; ArchUnit baseline reflects the win; full IT green.

### Checkpoint 8 — Metrics + close-out (Opus)

- Run `bin/metrics/measure.sh --label phase_1 > /tmp/phase1.json`. Merge into `bin/metrics/decomposition-progress.json` (manually for now; later phases may automate).
- Update the spec's "Phase 1" subsection if any boundary tweak surfaced.
- Confirm: subsystem-isolation test runs without TestEngine; `KnowledgeGraphServiceFactory` is either deleted or a thin deprecated alias; `engine.getManager(KgXxx)` callers = 0 across the repo.

**Verification:** full IT green.

## Test plan

Unit tests:
- `KnowledgeSubsystemFactoryTest` (new) — Checkpoint 2 above.
- All existing KG tests remain green at every checkpoint.

Integration tests:
- Full reactor IT after every checkpoint.
- `wikantik-it-test-rest`'s admin REST tests exercise the most consumer migrations, so they're the canary.

Subsystem isolation contract:
- After Phase 1, Knowledge tests should be able to instantiate the subsystem with mocks and not depend on `TestEngine` for setup. We'll measure this by counting `TestEngine` references in `wikantik-main/src/test/java/com/wikantik/knowledge/`.

## Rollback

Each checkpoint is a single commit and a single subagent task. If a checkpoint regresses the IT and a quick fix isn't apparent, `git revert` the offending commit and re-plan. The subsystem scaffolding (Checkpoint 1) is the riskiest — if it breaks, everything stops and we re-design before retrying.

## Subagent strategy

| Task | Agent | Model | Reason |
|---|---|---|---|
| Read 25 services + collect callers | Explore | Sonnet | Scoped read-only survey |
| Migrate file-by-file mechanically | general-purpose | Sonnet | Pattern is rote; need full-tree visibility |
| Code review my own diffs | code-reviewer | Sonnet | Independent eyes catch what I miss |
| Final summary / spec update | Opus (me) | — | Judgement calls only |

Concurrency stays at 1. Each subagent dispatch waits for prior tests to be green.

## Done when

- `bin/metrics/measure.sh` reports zero registered KG-flavored managers.
- ArchUnit freeze store no longer contains entries for KG `getManager()` callers.
- `KnowledgeSubsystemFactoryTest` runs in isolation without `TestEngine`.
- `WikiEngine.initialize()` no longer constructs KG services directly — delegates to `KnowledgeSubsystemFactory.create(deps)`.
- Phase 2 plan can begin against this foundation.
