**Status:** consumed by Phase 12 Ckpt 1 (2026-05-14). Authoritative input record; do not edit.

# KnowledgeSubsystem.Services Field Audit

Audit of each field in `KnowledgeSubsystem.Services` to inform the `KnowledgeSubsystemFactory.rebuildFromExisting(engine, existing)` method for Phase 12 Ckpt 1.

## Field Inventory

| # | Field | Java Type | Current Source in `rebuildFromManagers` | Hot-Swappable via `setManager`? | Derived? (upstream fields) | Side-Effect Risk |
|---|-------|-----------|----------------------------------------|--------------------------------|--------------------------|-----------------|
| 1 | `kgService` | `KnowledgeGraphService` | KnowledgeSubsystemBridge.java:108 | Yes | No | None |
| 2 | `judgeService` | `KgProposalJudgeService` | KnowledgeSubsystemBridge.java:126 | Yes | No | None |
| 3 | `judgeRunner` | `JudgeRunner` | KnowledgeSubsystemBridge.java:127 | Yes | No | **Scheduled task** (calls `schedule()` in factory:126) |
| 4 | `kgMaterialization` | `KgMaterializationService` | KnowledgeSubsystemBridge.java:128 | Yes | No | None |
| 5 | `judgeTimeoutRepository` | `KgJudgeTimeoutRepository` | KnowledgeSubsystemBridge.java:129 | Yes | No | None |
| 6 | `hubProposalService` | `HubProposalService` | KnowledgeSubsystemBridge.java:130 | Yes | No | None |
| 7 | `hubDiscoveryService` | `HubDiscoveryService` | KnowledgeSubsystemBridge.java:131 | Yes | No | None |
| 8 | `hubOverviewService` | `HubOverviewService` | KnowledgeSubsystemBridge.java:132 | Yes | No | None |
| 9 | `hubProposalRepository` | `HubProposalRepository` | KnowledgeSubsystemBridge.java:133 | Yes | No | None |
| 10 | `hubDiscoveryRepository` | `HubDiscoveryRepository` | KnowledgeSubsystemBridge.java:134 | Yes | No | None |
| 11 | `contentChunkRepository` | `ContentChunkRepository` | KnowledgeSubsystemBridge.java:135 | Yes | No | None |
| 12 | `chunkProjector` | `ChunkProjector` | KnowledgeSubsystemBridge.java:136 | Yes | No | None |
| 13 | `mentionIndex` | `MentionIndex` | KnowledgeSubsystemBridge.java:137 | Yes | No | **DataSource lifecycle** (holds connection for lazy reads) |
| 14 | `nodeMentionSimilarity` | `NodeMentionSimilarity` | KnowledgeSubsystemBridge.java:138 | Yes | No | **DataSource lifecycle** (holds connection for centroid reads) |
| 15 | `frontmatterDefaultsFilter` | `FrontmatterDefaultsFilter` | KnowledgeSubsystemBridge.java:139 | Yes | No | None |
| 16 | `hubSyncFilter` | `HubSyncFilter` | KnowledgeSubsystemBridge.java:140 | Yes | No | None |
| 17 | `contextRetrievalService` | `ContextRetrievalService` | KnowledgeSubsystemBridge.java:141 | **No (intentionally excluded line 463)** | No | Unknown (post-construction wiring) |
| 18 | `forAgentProjectionService` | `ForAgentProjectionService` | KnowledgeSubsystemBridge.java:142 | Yes | No | **Memoized cache** (wikantik.forAgentCache, 1h TTL) |
| 19 | `bootstrapEntityExtractionIndexer` | `BootstrapEntityExtractionIndexer` | KnowledgeSubsystemBridge.java:143 | Yes | No | **Background indexer** (extraction pipeline startup) |
| 20 | `kgInclusionPolicy` | `KgInclusionPolicy` | KnowledgeSubsystemBridge.java:144 | Yes | No | None |
| 21 | `reconciliationJobRunner` | `ReconciliationJobRunner` | KnowledgeSubsystemBridge.java:145 | Yes | No | **Scheduled task** (policy reconciliation cron) |
| 22 | `retrievalQualityRunner` | `RetrievalQualityRunner` | KnowledgeSubsystemBridge.java:146 | Yes | No | **Scheduled task** (nightly retrieval-quality CI) |
| 23 | `kgCurationOps` | `KgCurationOps` | KnowledgeSubsystemBridge.java:113–123 | No | Yes (upstream: `kgService`, `pageManager`, `KgExcludedPagesRepository`) | None |

## Findings

1. **Total Fields**: 23 fields in `Services`.

2. **Direct `engine.getManager()` Reads**: 22 fields (all except `kgCurationOps`). These are the "easy case" for `rebuildFromExisting` — direct swap from the manager registry when the upstream dependency is hot-swapped.

3. **Derived Fields**: 1 field (`kgCurationOps`). This is the "tricky case": the bridge constructs it from `kgService`, `pageManager` (via `engine.getManager`), and `KgExcludedPagesRepository` (optional fallback). It requires per-field rebuild logic: when either `kgService` or `pageManager` is hot-swapped, `rebuildFromExisting` must reconstruct `kgCurationOps` — but it cannot call the factory's full `create(deps)` path (which would rerun side effects). Instead, it must call a minimal factory method that recomposes the facade without touching scheduler or background-indexer state.

4. **Coherence Gap — ContextRetrievalService**: Field 17 is intentionally excluded from the `setManager` consumer map (line 463 comment: "ContextRetrievalService intentionally excluded"). This is a known exception: the service is wired post-construction by `ContextRetrievalServiceInitializer` servlet listener and does not trigger subsystem rebuilds. `rebuildFromExisting` must respect this — keep the existing instance and only swap when explicitly re-wired by the initializer.

5. **Side-Effect Risk Fields**: Six fields carry side-effect re-invocation risk if naively re-instantiated without factory context:
   - `judgeRunner` (line 126: `schedule()` starts the judge cron; re-instantiation would spawn duplicate schedulers)
   - `mentionIndex` (lazy DataSource holder for mention reads)
   - `nodeMentionSimilarity` (lazy DataSource holder for centroid reads)
   - `forAgentProjectionService` (memoized cache with 1h TTL — cache state must not be reset mid-request)
   - `bootstrapEntityExtractionIndexer` (background extraction pipeline; re-instantiation would restart indexing)
   - `reconciliationJobRunner` (policy reconciliation cron; re-instantiation would spawn duplicate schedulers)
   - `retrievalQualityRunner` (nightly CI scheduler; re-instantiation would spawn duplicate schedulers)

   **Action**: `rebuildFromExisting` must preserve these instances and only reconstruct their upstream dependencies if the underlying manager changed. For `kgCurationOps` (derived), the rebuild method must create a minimal facade without triggering factory side effects.
