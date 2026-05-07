# Phase 7: SearchSubsystem extraction + LuceneSearchProvider decomposition — implementation plan

**Spec:** [docs/superpowers/specs/2026-05-05-wikantik-main-decomposition-design.md](../specs/2026-05-05-wikantik-main-decomposition-design.md)
**Status:** ready
**Estimated effort:** 5 days
**Goal:** wall off Lucene + hybrid retrieval + embeddings behind a typed `SearchSubsystem.Services` surface. Decompose `LuceneSearchProvider` (1251 LOC) into three cohesive collaborators along its actual seams (write/read/lifecycle). Migrate the 8 production callsites of `SearchManager` to the typed accessor.

Smaller phase by callsite count than 5 — `SearchManager` is referenced by only 8 production files. The center of the work is the LuceneSearchProvider three-way split.

## Scope

**In:**
1. **`SearchSubsystem.Deps` + `.Services`** records under `com.wikantik.search.subsystem.*`.
2. **`SearchSubsystemFactory.create(Deps) → Services`** — pure factory consumed by `WikiEngine.initialize()`. `SearchSubsystem` depends on Core, Persistence, Page (for content), and Knowledge (for the graph rerank step).
3. **`Services` exposes:**
   - `SearchManager searchManager`
   - `SearchProvider searchProvider` (the configured impl — typically `LuceneSearchProvider`; escape hatch for hybrid retrieval consumers that need the raw provider)
   - The three decomposed Lucene helpers (Ckpt 3 — see below)
   - Hybrid services: `HybridSearchService`, `QueryEmbedder`, `QueryEntityResolver`, `GraphRerankStep`, `GraphProximityScorer`, `Breaker` (already cleanly encapsulated; the Services bundle gives consumers typed access)
   - In-memory indexes: `InMemoryChunkVectorIndex`, `InMemoryGraphNeighborIndex`
   - `EmbeddingIndexService`, `OllamaEmbeddingClient`, `EmbeddingConfig` (the embedding pipeline)
   - `BootstrapEmbeddingIndexer`, `AsyncEmbeddingIndexListener`
   - `FrontmatterMetadataCache`
4. **Decompose `LuceneSearchProvider` (1251 LOC)** along its three real responsibilities (the spec's three-way split is correct):
   - **`LuceneIndexer`** (write side): `pageRemoved`, `reindexPage`, attachment-indexing helpers, `clearIndex`, the index-update queue + reindex-thread that drains it. Owns the `IndexWriter` lifecycle.
   - **`LuceneSearcher`** (read side): `findPages` overloads, `moreLikeThis`, query parsing, scored-hits assembly, highlighting, snippet extraction. Owns the `IndexReader` / `SearcherManager` refresh cycle.
   - **`LuceneIndexLifecycle`** (cross-cutting): open/close/optimize, directory-cache state, the `documentCount` / `lastUpdateInstant` / `getTotalSearchCount` / `getZeroResultSearchCount` / `getLastQueryElapsedMillis` / `getReindexQueueDepth` accessors. Owns the `Directory` reference.
   - **`LuceneSearchProvider`** facade: implements `SearchProvider`; constructors build the three helpers; public methods one-line-delegate. Target shrink: ~250 LOC.
5. **Migrate consumers** — every `engine.getManager(SearchManager.class)` callsite (8 production files: 1 in wikantik-rest, 1 in wikantik-knowledge, 6 in wikantik-main). Behaviour zero.
6. **Subsystem-isolation test** for `SearchSubsystemFactory` against an in-memory Lucene `Directory` + Mockito stubs for upstream services.

**Out:**
- Hybrid-retrieval algorithmic changes. Move services into the bundle; don't reshape their internals. The hybrid pipeline (BM25 → embeddings → graph rerank) is already well-encapsulated.
- Splitting `wikantik-search` into a separate Maven module. Stay in `wikantik-main` under `com.wikantik.search.subsystem.*`.
- The `RetrievalQualityRunner` ownership question — it consumes Search but lives in Knowledge for now. Phase 7 leaves the ownership where it is.

## Design

### `SearchSubsystem` shape

```java
package com.wikantik.search.subsystem;

public final class SearchSubsystem {

    public record Deps(
        CoreSubsystem.Services core,
        PersistenceSubsystem.Services persistence,
        PageSubsystem.Services page,
        KnowledgeSubsystem.Services knowledge,
        Engine engine                                    // legacy seam — Lucene init reads engine ServletContext for index path
    ) {}

    public record Services(
        // Manager + provider:
        SearchManager  searchManager,
        SearchProvider searchProvider,

        // Decomposed Lucene helpers (Ckpt 3):
        LuceneIndexer        luceneIndexer,
        LuceneSearcher       luceneSearcher,
        LuceneIndexLifecycle luceneIndexLifecycle,

        // Hybrid retrieval:
        HybridSearchService  hybridSearch,
        QueryEmbedder        queryEmbedder,
        QueryEntityResolver  queryEntityResolver,
        GraphRerankStep      graphRerankStep,
        GraphProximityScorer graphProximityScorer,

        // In-memory indexes:
        InMemoryChunkVectorIndex   chunkVectorIndex,
        InMemoryGraphNeighborIndex graphNeighborIndex,

        // Embedding pipeline:
        EmbeddingIndexService     embeddingIndexService,
        OllamaEmbeddingClient     embeddingClient,
        BootstrapEmbeddingIndexer bootstrapEmbeddingIndexer,
        AsyncEmbeddingIndexListener asyncEmbeddingIndexListener,

        // Cache:
        FrontmatterMetadataCache frontmatterMetadataCache
    ) {}
}
```

The Services record is wide (15 fields). That's correct — Search has more knobs than the other subsystems. Most consumers will use `searchManager` + maybe `hybridSearch`; the rest are escape hatches for specialized callers (admin endpoints that show index stats, the bootstrap indexer CLI, etc.).

### `WikiSubsystems` evolution

Order: `(core, persistence, auth, page, rendering, search, knowledge)`. Search depends on Knowledge for the graph-rerank step, so Search must build AFTER Knowledge — but Knowledge today references Search through `LuceneMlt` (an opaque functional seam). Phase 7 keeps that seam: Knowledge takes a `LuceneMlt` lambda built by Search, but Search builds AFTER Knowledge so the dependency direction (Search → Knowledge) is honoured. The `LuceneMlt` builder gets resolved later in `WikiEngine.initialize` after both are constructed.

Wait — re-read: the spec puts Search AFTER Knowledge in the dependency declaration ("`SearchSubsystem` depends on Knowledge"). But Knowledge today receives a `LuceneMlt` from `WikiEngine` at construction time, which is built from the LuceneSearchProvider. That's a concrete cycle. Resolution: Knowledge's `LuceneMlt` field becomes optional (already is — Phase 1 made it nullable), and a Phase 7 follow-up wires it post-construction via a setter on `HubOverviewService` once Search is built. For Phase 7 itself, just construct Search after Knowledge and let the existing `null` LuceneMlt path stay until the post-construction wiring lands.

### `LuceneSearchProvider` decomposition

The class today already segregates its three concerns via private helpers and the index-update queue lives self-contained in `pageRemoved` / `reindexPage` flow. Approach:

1. **`LuceneIndexer`** (interface + `DefaultLuceneIndexer` impl, ~480 LOC):
   - `void reindexPage(Page page)`
   - `synchronized void pageRemoved(Page page)`
   - `synchronized void clearIndex()`
   - `int getReindexQueueDepth()`
   - The reindex-thread + queue.
   - The attachment-indexing helpers (private today — keep package-private on the impl).

2. **`LuceneSearcher`** (interface + `DefaultLuceneSearcher` impl, ~520 LOC):
   - `Collection<SearchResult> findPages(String query, Context)`
   - `Collection<SearchResult> findPages(String query, int flags, Context)`
   - `List<MoreLikeThisHit> moreLikeThis(String seedDocName, int max, Set<String> excludes)`
   - Query-parser + analyzer access.
   - Highlight / snippet helpers.
   - The `MoreLikeThisHit` record stays here.

3. **`LuceneIndexLifecycle`** (interface + `DefaultLuceneIndexLifecycle` impl, ~150 LOC):
   - Open / close / optimize.
   - `documentCount()`, `lastUpdateInstant()`, `getTotalSearchCount()`, `getZeroResultSearchCount()`, `getLastQueryElapsedMillis()`.
   - Owns the `Directory` reference + analyzer config.
   - Provides accessors that `LuceneIndexer` and `LuceneSearcher` use to obtain `IndexWriter` / `IndexReader`.

4. **`LuceneSearchProvider`** facade (~250 LOC):
   - `initialize(Engine, Properties)` builds the three helpers.
   - `findPages` / `pageRemoved` / `reindexPage` / `clearIndex` etc. one-line-delegate.
   - Keeps every public `PROP_*` constant + the `MAX_SEARCH_HITS` constant + `SEARCHABLE_FILE_SUFFIXES`.

The `IndexWriter` and `IndexReader` lifecycle is the highest-risk part: today the class manages both via private methods that synchronise on `this`. The decomposition has to preserve the synchronisation semantics — `Indexer` and `Searcher` both call into `IndexLifecycle` for IndexWriter/Reader access. Mitigation: have `IndexLifecycle` expose `synchronized` access methods that the helpers call; the synchronisation centralises on the lifecycle's monitor instead of the facade's.

## Checkpoint plan

Each checkpoint = one commit + the full IT reactor before commit. Concurrent subagents where the work decomposes.

| Ckpt | Duration | Concurrency | Model |
|------|----------|-------------|-------|
| 1 — Scaffold | half day | single | Opus |
| 2 — Migrate consumers | half day | single (only 8 callsites — concurrency overhead not worth it) | Haiku |
| 3 — Decompose LuceneSearchProvider | 2 days | single (high-risk surgery) | Sonnet |
| 4 — Wire Lucene helpers + hybrid services into Services | half day | single | Opus |
| 5 — Verification + close-out | half day | single | Opus |

Ckpt 2 and Ckpt 3 can run in parallel if Ckpt 2's grep-replace doesn't touch any LuceneSearchProvider lines. Audit: SearchManager getManager calls don't appear in LuceneSearchProvider.java itself, so Ckpts 2 + 3 are disjoint and parallelizable.

### Checkpoint 1 — Scaffold (Opus)

- `com.wikantik.search.subsystem.SearchSubsystem` (`Deps` + `Services` records).
- `SearchSubsystemFactory.create(Deps)` reads everything off the engine's existing wiring. The Lucene helper slots stay null until Ckpt 3.
- `SearchSubsystemBridge.fromLegacyEngine(Engine)`.
- `WikiSubsystems` adds `search()` field.
- `WikiEngine.initialize()` builds Search after Knowledge.
- `WikiEngine.setManager()` invalidates the snapshot when SearchManager / SearchProvider is hot-swapped.
- `RestServletBase.getSubsystems()` reads the typed accessor + bridge fallback.
- `SearchSubsystemFactoryTest` with Mockito stubs.

**Behaviour change:** zero.

### Checkpoint 2 — Migrate consumers (Haiku, parallel-eligible with Ckpt 3)

8 callsites:
- wikantik-rest (1): the resource that gets the manager
- wikantik-knowledge (1): the context-retrieval service
- wikantik-main (6): WikiContext, AbstractReferralPlugin, RecentChangesPlugin, FrontmatterMetadataCache(?), DefaultRenderingManager(?), and a couple more — re-grep before dispatch.

REST → `getSubsystems().search().searchManager()`. Non-servlet → `SearchSubsystemBridge.fromLegacyEngine(engine).searchManager()`.

SKIP list: WikiEngine.java, DefaultSearchManager.java, LuceneSearchProvider.java (no SearchManager getManager call there but be defensive).

### Checkpoint 3 — Decompose `LuceneSearchProvider` (Sonnet, single agent)

The high-risk surgery. Approach:
1. Create `LuceneIndexLifecycle` first (it owns the Directory; everything else depends on it).
2. Create `LuceneIndexer` next (write side).
3. Create `LuceneSearcher` last (read side).
4. The facade collapses to delegation.

Behaviour zero — every Lucene API call, every analyzer config, every analyzer chain, every highlighting parameter must move verbatim. The existing `LuceneSearchProviderTest` and `LuceneSearchProviderCITest` stay against the facade.

### Checkpoint 4 — Wire helpers + hybrid services into Services (Opus)

`SearchSubsystem.Services` already has the slots (Ckpt 1). Populate them:
- Lucene helpers: cast `searchProvider` to `LuceneSearchProvider` and pull the helpers off via accessors (`getIndexer()`, `getSearcher()`, `getIndexLifecycle()`).
- Hybrid + embedding services: these are constructed in `WikiEngine.initialize()` via `wireHybridRetrieval` and `wireEntityExtraction`. The factory pulls them off the engine's manager registry or a shared field.

Add `SearchSubsystemFactoryTest` assertions for the new fields.

### Checkpoint 5 — Verification + close-out (Opus)

- `bin/metrics/measure.sh --label phase_7_close`.
- Spec § Phase 7 status + deltas.
- Plan marked complete.
- ArchUnit refrozen if needed.

## Risks

1. **`IndexWriter` / `IndexReader` synchronisation.** The current class synchronises on `this` for index mutations and uses `SearcherManager` for read coordination. The decomposition has to centralise that monitor on `LuceneIndexLifecycle` so concurrent reads + writes still serialise correctly. Mitigation: explicit `synchronized` methods on `LuceneIndexLifecycle` for writer access; readers go through `SearcherManager` unchanged.

2. **Reindex queue + thread.** A background thread drains the reindex queue. Phase 7 moves it onto `LuceneIndexer` along with its `start()`/`shutdown()` lifecycle. Watch for the engine-shutdown event handler that today calls `clearIndex` / closes the writer.

3. **Search → Knowledge cycle.** Search depends on Knowledge for the graph-rerank step; Knowledge depends on Search for `LuceneMlt`. Resolution: keep the `LuceneMlt` field nullable on `KnowledgeSubsystem.Deps` (already is), build Search after Knowledge in the engine, and post-wire the `LuceneMlt` via a setter on `HubOverviewService` once both are constructed. Ckpt 4 implements the post-wire.

4. **Test fixtures.** `LuceneSearchProviderTest` exercises the full surface. After the split, the facade's delegation must be byte-identical so the existing tests pass unchanged. New tests (`LuceneIndexerTest`, `LuceneSearcherTest`, `LuceneIndexLifecycleTest`) split off from the existing test suite where the decomposition naturally separates the assertions.

5. **`MoreLikeThis` MLT seam.** The `LuceneMlt` functional interface in `HubOverviewService` is the cross-subsystem connection point. Phase 7 doesn't change its shape; the post-wire setter just gives `HubOverviewService` a concrete implementation that calls `searchProvider.moreLikeThis(...)` (or, post-decomposition, `luceneSearcher.moreLikeThis(...)`).

## Done when

- `SearchSubsystem.Services` produces the manager + provider + 3 Lucene helpers + 7 hybrid services + 4 embedding services + 1 cache. All non-null after `create()`.
- `LuceneSearchProvider` decomposed: ~250 LOC facade + Indexer + Searcher + IndexLifecycle, each <600 LOC.
- 8 production `engine.getManager(SearchManager.class)` callsites migrated.
- `god_classes_over_800` drops by 1 (LuceneSearchProvider 1251 → ~250 LOC).
- Phase 8 (ApiSubsystem cleanup) plan can begin.
