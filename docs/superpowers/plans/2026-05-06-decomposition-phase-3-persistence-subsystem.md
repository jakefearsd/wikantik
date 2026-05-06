# Phase 3: PersistenceSubsystem extraction — implementation plan

**Spec:** [docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md](../specs/2026-05-05-wikantik-main-decomposition-design.md)
**Status:** complete (2026-05-06)
**Estimated effort:** 4–5 days
**Goal:** centralize the `DataSource` and every JDBC repository / DAO under a single `PersistenceSubsystem` boundary. After Phase 3, no consumer outside Persistence holds raw JDBC handles or constructs `Jdbc*Repository` classes itself; downstream subsystems receive narrow repository interfaces. The 1561-line `JdbcKnowledgeRepository` is decomposed along its real usage seams.

## Scope

**In:**

1. **`PersistenceSubsystem.Deps` + `.Services`** records housing every repository/DAO currently registered or constructed inline in `WikiEngine.initialize()` and `KnowledgeSubsystemFactory.create()`.
2. **`PersistenceSubsystemFactory.create(Deps) → Services`** — pure factory. Inputs: `DataSource`, `WikiProperties` (only for repo-level config), and any cross-subsystem repos the Knowledge subsystem currently constructs as collaborators.
3. **`DataSource` creation moved out of `WikiEngine`** into the factory. The engine still bootstraps the `DataSource` from `wikantik.datasource` JNDI / properties (one-time JDBC plumbing), but every repository sees `DataSource` only via Persistence.
4. **`JdbcKnowledgeRepository` decomposition** along its public-API seams (4 cohesive repositories):
   - **`KgNodeRepository`** — `upsertNode`, `getNode`, `getNodeByName`, `deleteNode`, `queryNodes`, `searchNodes` (overloads), `getAllNodes` (both forms), `upsertNodeWithProvenance`, `deleteNodesByProvenance`, `getDistinctNodeTypes`, `countNodes`, `getNodeNames`.
   - **`KgEdgeRepository`** — `upsertEdge`, `upsertEdgeWithProvenance`, `deleteEdge`, `deleteEdgesByProvenance`, `getAllEdges` (both forms), `getEdgesForNode`, `diffAndRemoveStaleEdges`, `queryEdgesWithNames`, `getDistinctRelationshipTypes`, `countEdges`.
   - **`KgProposalRepository`** — `insertProposal`, `getProposal`, `listProposals`, `listProposalsFiltered`, `updateProposalStatus`, `applyMachineVerdict`, `applyHumanVerdict`, `recordReview`, `listReviews`, `getProposalsForJudging`, `updateTierByProvenance`, `countPendingProposals`, `countPendingUnjudgedProposals`, `upsertConsolidatedProposal`.
   - **`KgRejectionRepository`** — `insertRejection`, `isRejected`, `listRejections`, `deleteRejection`.
   - **`clearAll()`** stays on a small `KgRepositoryAdmin` facade or moves onto `Services` itself for the integration-test path.
5. **Migrate consumers** — every `new JdbcKnowledgeRepository(ds)` site disappears. `DefaultKnowledgeGraphService`, `JudgeRunner`, `KgMaterializationService`, etc. take the narrow interfaces they actually use.
6. **Update tests** that construct repositories directly so they receive the narrow interface (or build a `PersistenceSubsystem.Services` from a Testcontainers DataSource).

**Out:**

- Splitting per-repo into a separate `wikantik-persistence` Maven module (the spec defers this until the public surface stabilises). Phase 3 keeps the new types in `wikantik-main` under `com.wikantik.persistence.subsystem.*`.
- Migrating CRUD that lives outside `wikantik-main` — `wikantik-knowledge` has its own MCP server but reaches repositories through service interfaces. Already clean; no change.
- Touching `wikantik-event` or `wikantik-cache` (no repositories).

## Design

### `PersistenceSubsystem` shape

```java
package com.wikantik.persistence.subsystem;

public final class PersistenceSubsystem {

    public record Deps(
        DataSource dataSource,
        WikiProperties properties        // some repos read tunables (chunking thresholds, etc.)
    ) {}

    public record Services(
        // Knowledge graph, decomposed:
        KgNodeRepository kgNodes,
        KgEdgeRepository kgEdges,
        KgProposalRepository kgProposals,
        KgRejectionRepository kgRejections,

        // Existing single-purpose repositories (moved):
        HubProposalRepository hubProposals,
        HubDiscoveryRepository hubDiscovery,
        ContentChunkRepository contentChunks,
        ChunkEntityMentionRepository chunkEntityMentions,
        KgNodeEmbeddingRepository kgNodeEmbeddings,
        KgJudgeTimeoutRepository judgeTimeouts,
        KgExcludedPagesRepository kgExcludedPages,
        KgClusterPolicyRepository kgClusterPolicy,
        RetrievalQualityDao retrievalQualityDao,

        // Page-graph spine DAOs (moved):
        PageCanonicalIdsDao pageCanonicalIds,
        PageVerificationDao pageVerification,
        TrustedAuthorsDao trustedAuthors
    ) {}
}
```

### `KnowledgeSubsystem.Deps` evolution

After Phase 3:

```java
public record Deps(
    PersistenceSubsystem.Services persistence,   // ← replaces DataSource
    CoreSubsystem.Services core,
    PageManager pageManager,
    PageSaveHelper pageSaveHelper,
    HubOverviewService.LuceneMlt luceneMlt
) {}
```

Knowledge no longer constructs any `Jdbc*Repository` itself; it pulls them off `deps.persistence()`.

### `JdbcKnowledgeRepository` decomposition

The class today is a multi-table god-class with cross-table verdict logic mixed in. The four-way split aligns with the actual table partition (`kg_nodes`, `kg_edges`, `kg_proposals` + `kg_proposal_reviews`, `kg_rejections`). Methods that touch two tables (`applyMachineVerdict` / `applyHumanVerdict` / `upsertConsolidatedProposal` — these write to nodes/edges as a side-effect of a proposal apply) stay on `KgProposalRepository` but take collaborator references to `KgNodeRepository` / `KgEdgeRepository`. That keeps each repo's connection-handling local while making the cross-table coupling explicit.

Public interfaces in `wikantik-api` aren't strictly required for Phase 3 — keep the impl class as the contract for now. A follow-up can promote them to `wikantik-api` if the tests prove they want fakes.

### `DataSource` lifecycle

Today `WikiEngine.initialize` resolves the `DataSource` from JNDI (or builds one from properties), then hands it to `KnowledgeSubsystemFactory`. Phase 3 keeps the resolution in the engine boot path (it's plumbing, not domain) and feeds the `DataSource` into `PersistenceSubsystemFactory.create(Deps)`. The engine no longer holds the raw `DataSource` reference past the factory call — `PersistenceSubsystem.Services` is the only authoritative source.

## Checkpoint plan

Each checkpoint = one commit + the full IT reactor before commit.

### Checkpoint 1 — Scaffolding (Opus)

- `com.wikantik.persistence.subsystem.PersistenceSubsystem` (`Deps` + `Services` records, with the *existing* repositories only — no `JdbcKnowledgeRepository` decomposition yet).
- `PersistenceSubsystemFactory.create(Deps)` constructs each repository.
- `WikiSubsystems` adds `persistence()` field, ordered `(core, persistence, knowledge)`.
- `WikiEngine.initialize()` builds Persistence after Core, before Knowledge; passes it to Knowledge through `KnowledgeSubsystem.Deps` (a new field, alongside the existing `dataSource` for now).
- `PersistenceSubsystemFactoryTest` against Testcontainers Postgres: every `Services` field is non-null after `create()`.
- ArchUnit unchanged (no migrations yet).

**Behaviour change:** zero. All consumers still use the existing repository instances.

### Checkpoint 2 — Migrate repository construction sites (subagent, Sonnet)

- `KnowledgeSubsystemFactory.create(Deps)` reads each repository off `deps.persistence()` instead of `new JdbcXxxRepository(ds)`.
- `WikiEngine.initialize` reads `kgClusterPolicyRepository`, `kgExcludedPagesRepository`, page-graph spine DAOs etc. off `coreSubsystem` -> wait, off `persistence`. (One pass through every `new Jdbc*Repository` / `new *Dao` callsite.)
- Drop `dataSource` from `KnowledgeSubsystem.Deps` once Knowledge no longer needs it directly.

**Verification:** zero `new JdbcKnowledgeRepository|new HubProposalRepository|new HubDiscoveryRepository|new ContentChunkRepository|new ChunkEntityMentionRepository|new KgNodeEmbeddingRepository|new RetrievalQualityDao|new PageCanonicalIdsDao|new PageVerificationDao|new TrustedAuthorsDao|new KgClusterPolicyRepository|new KgExcludedPagesRepository|new JdbcKgJudgeTimeoutRepository` outside `PersistenceSubsystemFactory` and tests.

### Checkpoint 3 — Decompose `JdbcKnowledgeRepository` (Opus)

The big surgery. Approach:

1. Create `KgNodeRepository`, `KgEdgeRepository`, `KgProposalRepository`, `KgRejectionRepository` — each a new `public final class` with the same constructor (`DataSource dataSource`).
2. Move the implementations method-by-method, keeping signatures + SQL identical. Cross-table verdict methods live on `KgProposalRepository` and take `KgNodeRepository` / `KgEdgeRepository` constructor refs.
3. Leave `JdbcKnowledgeRepository` as a thin facade for the duration of Checkpoint 3 — every method delegates to one of the new repos. This keeps the diff reviewable and the IT suite green while consumers migrate.
4. `PersistenceSubsystem.Services` adds the four new fields; the old `JdbcKnowledgeRepository` field stays as well, marked `@Deprecated`.
5. New unit tests for `KgNodeRepositoryTest` etc. cover the same surface previously exercised through `JdbcKnowledgeRepository` (split or shared the existing `JdbcKnowledgeRepositoryTest` cases).

**Behaviour change:** zero. SQL unchanged.

### Checkpoint 4 — Migrate consumers to narrow repos (Opus)

- `DefaultKnowledgeGraphService` switches its constructor from `JdbcKnowledgeRepository repo` to four narrow refs.
- `JudgeRunner`, `KgMaterializationService`, `ConsolidatedProposalService` (and any other holder) follow.
- Tests update accordingly. The deprecated facade gets zero callers.

**Verification:** `grep` for `JdbcKnowledgeRepository` returns the file itself + the `Services` field + tests transitioning to narrow types.

### Checkpoint 5 — Delete the facade (Opus)

- Remove `JdbcKnowledgeRepository` from `PersistenceSubsystem.Services` and delete the file.
- Remove the deprecated registry entries (if any remain after Ckpt 2).
- Refresh ArchUnit baseline; the freeze should *shrink*.
- `bin/metrics/measure.sh --label phase_3_close`.

### Checkpoint 6 — Close-out + spec update (Opus)

- Update `2026-05-05-wikantik-main-decomposition-design.md` Phase 3 section with status + metric deltas.
- Mark this plan complete.

## Risks

1. **Cross-table verdict logic.** `applyMachineVerdict` / `applyHumanVerdict` / `upsertConsolidatedProposal` mutate `kg_nodes`, `kg_edges`, AND `kg_proposals` in a single transaction. Splitting nodes/edges off changes who owns the connection. Mitigation: keep the verdict methods on `KgProposalRepository` (which owns the proposal connection) and have it call into the node/edge repositories as helpers, threading the live `Connection` through. SQL stays in one transaction.

2. **`clearAll()` for IT teardown.** Used by IT setup. Either move to a per-repo `truncate()` and call them in order, or stash a small `KgRepositoryAdmin` on `Services`. Pick the simpler form when the surface is clearer.

3. **`DataSource` ownership.** Multiple subsystems construct repositories today; Phase 3 makes Persistence the only owner. If anything outside the new Phase 3 surface holds a raw `DataSource`, it gets the typed repo it actually uses. Watch for `wikantik-rest` admin endpoints that take a `DataSource` directly — none expected, but verify.

4. **JDBC driver / connection pool config.** Phase 3 doesn't change pool tuning. The PersistenceSubsystemFactory consumes whatever `DataSource` the engine boot resolved; tuning lives in `Tomcat ROOT.xml` as today.

## Done when

- `PersistenceSubsystem.Services` produces every JDBC repository / DAO via a single factory.
- `JdbcKnowledgeRepository` is deleted; four narrow repositories take its place.
- No `new Jdbc*Repository` / `new *Dao` construction outside `PersistenceSubsystemFactory` and Persistence-internal helpers.
- `bin/metrics/measure.sh` shows `god_classes_over_800` drops by 1 (JdbcKnowledgeRepository at 1561 LOC removed).
- Phase 4 (AuthSubsystem) plan can begin against the new Persistence foundation.
