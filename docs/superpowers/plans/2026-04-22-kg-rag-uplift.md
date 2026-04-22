# Plan: Knowledge Graph Uplift for RAG (unified embeddings, extractor pipeline, graph-aware rerank)

## Context

**Why now.** Search is production-ready: chunked and embedded on page save (`ChunkProjector` → `AsyncEmbeddingIndexListener` → `content_chunk_embeddings`), hybrid BM25+dense retrieval wired through `HybridSearchService.rerankWith` at `wikantik-rest/src/main/java/com/wikantik/rest/SearchResource.java:196`, with circuit breakers and graceful BM25 fallback. The KG has a clean schema (V004) and solid CRUD/traversal, but it is fed only by frontmatter YAML and agent-filed proposals, and it sits in two orphan embedding stacks (ComplEx + TF-IDF) that are decoupled from edge mutations and unrelated to search's Ollama stack. Net effect: the KG cannot keep pace with content, and RAG retrieval is entity-blind — a chunk about *Napoleon* and a chunk about *Waterloo* look semantically unrelated to the retriever even though the graph knows they're linked.

**What this plan delivers.** Three concurrent workstreams that together turn the KG from a curated catalog into a live substrate for RAG, consistent with the search pipeline:
- **(A) Unify embeddings** on `content_chunk_embeddings` (drop TF-IDF, retire ComplEx) so KG and search share one vector space — the prerequisite for any cross-system signal.
- **(B) Pluggable content-extractor pipeline** (`EntityExtractor` interface with Claude + Ollama implementations) that runs on save, mirroring `ChunkProjector`'s cadence, and emits *proposals* into the existing `kg_proposals` workflow — the KG ends up populated on the same cadence as search with human-on-the-loop safety.
- **(C) Graph-aware reranking** via a `GraphProximityScorer` hook inside `SearchResource.applyHybridRerank` — chunks whose mentioned entities are close (in the KG) to the query's entities get boosted. This is the first real *GraphRAG* lift and the foundation for the continuous-improvement loop: better content → better chunks → better extractions → richer graph → better RAG retrieval → surfacing gaps that motivate better content.

**Deliberate non-goals this round** (keep scope honest):
- Microsoft-style GraphRAG (community detection, LLM community summaries, global/local query routing). Reachable from this foundation; not built now.
- Link-prediction UX replacement for `predict_edges`. Deprecated with ComplEx; Claude-based suggestion via the extractor covers the use case.

## Benefits to users (detailed)

1. **RAG answers grounded in entity relationships, not just text similarity.** With graph-aware reranking, a question like *"what factors led to the 1815 campaign outcome?"* pulls chunks mentioning *Napoleon*, *Waterloo*, *Wellington*, *Blücher* even when their wording doesn't lexically/densely overlap with the query — because the graph connects them. This is the single biggest RAG quality lift from a graph, and it works at the same latency envelope as today's hybrid search (one extra in-memory graph lookup per candidate).

2. **Entity-expanded context blocks for downstream RAG.** Each RAG hit can carry a sidecar `related_entities` block (names, types, 1-hop relationships) so the answering model has structured context alongside the chunk text. Users get fewer "I don't know, but here's some text" answers and more answers that correctly disambiguate people, places, projects, and versions.

3. **Graph that keeps up with content.** Today the KG rots because updating a page doesn't update the graph. After (B), save a page → entities/relations in that page become proposals within seconds, and the admin proposal UI is the authoring surface for curation. Editors stop having to hand-author YAML relationships to get KG coverage.

4. **One place to look, one set of metrics.** Unifying on `content_chunk_embeddings` means one index, one model, one set of failure modes. A bad Ollama day already gracefully degrades search to BM25; after (A) the KG degrades the same way instead of failing silently with stale TF-IDF vectors. Consistency = operability.

5. **Continuous-improvement loop, visible to the user.** Proposals that repeatedly fail (hit `kg_rejections`) identify content ambiguity; clusters of orphan entities surface topic gaps; dense regions of the graph reveal over-covered topics. These signals are only meaningful if the graph tracks content in near-real-time, which (B) delivers. You get a product-quality dashboard almost for free.

6. **Lower cost of future work.** With ComplEx deprecated and one embedding store, every later feature (community detection, global-query mode, reranker tuning) is built against one stack rather than three. It's the foundation that makes full GraphRAG economically feasible later.

7. **Privacy-flexible extractor.** The pluggable `EntityExtractor` means operators can stay entirely local (Ollama) for sensitive corpora or run Claude for extraction quality. Config-flip, not fork.

## Target architecture

```
Page save
  │
  ├─► ChunkProjector ──► kg_content_chunks
  │       │
  │       ├─► AsyncEmbeddingIndexListener ──► content_chunk_embeddings (Ollama)  [unchanged]
  │       │
  │       └─► AsyncEntityExtractionListener  (NEW, mirrors above)
  │              │
  │              └─► EntityExtractor (Claude | Ollama)  (NEW)
  │                     │
  │                     └─► kg_proposals  +  chunk_entity_mentions (NEW)
  │
  └─► GraphProjector ──► kg_nodes/kg_edges  [unchanged: frontmatter links_to/etc]
         │
         └─► (deleted) EmbeddingService.retrain() / ComplEx               [DEPRECATED]

Query path
  GET /api/search
      ├─► BM25 (Lucene)                             [unchanged]
      ├─► QueryEmbedder → Ollama vector             [unchanged]
      ├─► HybridSearchService.rerankWith(...)       [unchanged call site]
      │       └─► HybridFuser (RRF + weighted)      [unchanged]
      └─► GraphProximityScorer (NEW)                [added as additional feature]
              └─► uses chunk_entity_mentions + in-memory graph neighbors
```

The KG's node-similarity queries (`find_similar`, `HubDiscoveryService` TF-IDF users) re-point to **`content_chunk_embeddings` via a new `NodeMentionSimilarity` helper**: a node's vector is the centroid of its mention-chunk vectors. No new embedding table; one source of truth.

## Work streams

### (A) Freshness & embedding consistency

**Goal.** One embedding store across KG and search. ComplEx removed. TF-IDF removed. No more retrain cron for the KG — freshness rides on search's existing async indexer.

**Schema (V011).** `bin/db/migrations/V011__chunk_entity_mentions.sql` — new table `chunk_entity_mentions(chunk_id UUID FK → kg_content_chunks, node_id UUID FK → kg_nodes, confidence REAL, extractor TEXT, extracted_at TIMESTAMPTZ, PRIMARY KEY(chunk_id, node_id))`. Indexed both directions (node→chunks, chunk→nodes). This is the bridge between search embeddings and KG — used in both (B) and (C). The V008 header comment already anticipates this table, so the naming fits.

**Code — classes to add.**
- `com.wikantik.knowledge.embedding.NodeMentionSimilarity` (`wikantik-main`) — given a node, fetches its mention-chunks and computes a pgvector-aggregated similarity query against `content_chunk_embeddings`. Replaces all callers of `ContentEmbeddingRepository` (TF-IDF).

**Code — edits.**
- `wikantik-main/.../knowledge/HubDiscoveryService.java` — swap TF-IDF reads for `NodeMentionSimilarity`; the HDBSCAN input vectors now come from Ollama.
- `wikantik-main/.../knowledge/DefaultKnowledgeGraphService.java:176` and `:184` — no wiring needed (the dirty-marking idea disappears with ComplEx).
- `wikantik-knowledge/.../KnowledgeMcpInitializer.java:44` — `find_similar` and the similarity path in `predict_edges` re-point to `NodeMentionSimilarity`. Keep `find_similar`; drop `predict_edges` (ComplEx-only).
- Delete: `com.wikantik.knowledge.EmbeddingService`, `ComplExModel`, `TfidfModel`, `EmbeddingRepository`, `ContentEmbeddingRepository`, `PROP_RETRAIN_MINUTES` scheduling, and the `predict_edges` MCP tool. Update `MEMORY.md` if any reference.
- Leave `kg_embeddings` and `kg_content_embeddings` tables in place this migration; a later cleanup migration drops them after one redeploy. This is cheaper than gating with a flag.

**Tests to write first (TDD).**
- `NodeMentionSimilarityTest` — given mock `chunk_entity_mentions` + `content_chunk_embeddings`, verify top-K retrieval; zero-mention node returns empty; mention with missing embedding is skipped not errored.
- `HubDiscoveryServiceTest` — existing tests continue to pass with TF-IDF impl swapped; add one test that proves the new path reads `content_chunk_embeddings`.
- Deletion audit: run `rg` to confirm no remaining callers of removed classes, no dead DI wiring in any `*Initializer`/`*Bootstrap`.

### (B) Pluggable extractor pipeline

**Goal.** Every page save produces entity/relation proposals into `kg_proposals` and mention rows into `chunk_entity_mentions`, through an extractor that can be swapped between Claude and Ollama by config.

**Code — new interface and two implementations.**
- `com.wikantik.api.knowledge.EntityExtractor` (`wikantik-api`):
  ```
  interface EntityExtractor {
    ExtractionResult extract(Chunk chunk, ExtractionContext ctx);
  }
  record ExtractionResult(List<ProposedNode> nodes,
                          List<ProposedEdge> edges,
                          List<Mention> mentions,
                          String extractorCode,
                          Duration latency);
  ```
- `com.wikantik.knowledge.extraction.ClaudeEntityExtractor` — uses Anthropic SDK with **prompt caching** on the system prompt + schema + existing-node dictionary (cache TTL is 5 min, matches search's save cadence well). Model default: `claude-haiku-4-5` for cost, with a config to flip to `claude-sonnet-4-6` for higher-quality corpora. Structured-output JSON mode. Batches small chunks; hard timeout per chunk; retries on 5xx only.
- `com.wikantik.knowledge.extraction.OllamaEntityExtractor` — reuses the existing Ollama client infrastructure; structured JSON via function-call-style prompt; same interface contract. Best-effort parse; poisoned chunks are logged + skipped (matches how `AsyncEmbeddingIndexListener` handles bad vectors).

**Selection.** `wikantik.knowledge.extractor=claude|ollama|disabled` in `wikantik-custom.properties`. Default: `disabled` (opt-in) so a fresh deploy doesn't surprise-emit proposals.

**Wiring — save-time hook.**
- `com.wikantik.knowledge.extraction.AsyncEntityExtractionListener` (`wikantik-main`) — `WikiEventListener` that subscribes to the same page-saved events `AsyncEmbeddingIndexListener` uses. Walks the chunks from `ChunkProjector`'s output, calls the selected `EntityExtractor`, writes proposals + mentions. Fully async; search path is not blocked. Prometheus metrics (`wikantik_kg_extractor_requests_total`, `_latency_seconds`, `_triples_emitted_total`, `_failures_total`).
- Proposals resolve to existing nodes by exact name match first; ambiguous names go to proposals with `candidate_ids` array for admin disambiguation.
- `kg_rejections` is consulted before writing a proposal — rejected edges aren't re-proposed.

**Safety.**
- No direct writes to `kg_nodes`/`kg_edges` from the extractor. Ever. All paths route through `kg_proposals` → admin approval → existing `DefaultKnowledgeGraphService.upsertNode/upsertEdge`. This keeps human-on-the-loop intact and preserves provenance (`ai-proposed` vs `ai-reviewed`).
- Confidence threshold (config-driven, default 0.6): below threshold → dropped not proposed, to avoid proposal spam.
- Per-page rate limit so a re-save storm can't DoS the extractor.

**Admin UX (minimal, existing surfaces).**
- `ListProposalsTool` (wikantik-mcp) already shows proposals; extend with an `extractor` filter facet.
- `KnowledgeGraphResource` already serves the admin page snapshot; add `mentions_count` per node (cheap COUNT against `chunk_entity_mentions`).

**Tests to write first.**
- `EntityExtractorContractTest` — table-driven contract test run against both implementations with mocked backends: schema compliance, mention coverage, deduplication, rejection-check behavior.
- `AsyncEntityExtractionListenerIT` — integration test spinning up a small corpus (3 pages, mocked Claude), asserting proposal + mention rows exist after save.
- `ClaudeEntityExtractorTest` — prompt-caching header verification, timeout behavior, malformed JSON handling, 429 retry.

### (C) Graph-aware reranking

**Goal.** Use the graph to re-order hybrid results. Chunks whose mention-entities are graph-close to the query's entities get boosted; the rest are untouched.

**Entry point — no API breakage.** Extend `SearchResource.applyHybridRerank` at `wikantik-rest/.../SearchResource.java:177` to call a new step after `HybridSearchService.rerankWith` returns. Falls back silently if the graph has no mentions for the query/candidates — same defensive posture as the existing hybrid path.

**Code — new classes.**
- `com.wikantik.search.hybrid.GraphProximityScorer` (`wikantik-main`):
  ```
  float[] score(Set<UUID> queryEntities, List<ChunkCandidate> candidates, int maxHops)
  ```
  - Resolves query entities via: exact name match in `kg_nodes` → OR if no match, a cheap `kg_nodes` BM25/trigram lookup on top-K name candidates.
  - Candidate entities from `chunk_entity_mentions` (bulk fetch in one query).
  - Proximity score: 1/(1 + min-hops) over a BFS frontier capped at `maxHops=2`, with a weight per relationship-type optional for future tuning.
  - Fuses into the existing HybridFuser result as an additional feature with tunable weight `wikantik.search.graph.boost=0.2` (default; 0 disables).
- `com.wikantik.search.hybrid.InMemoryGraphNeighborIndex` — loads `kg_edges` once and refreshes on edge-mutation events. Memory-bounded cache; degrades to DB lookups above cardinality cap (same pattern as `InMemoryChunkVectorIndex`).

**Latency budget.** Target +15ms p95 on the search path. Query-entity resolution is the cost pole; cache resolved entities per query (Caffeine, 5-min TTL — same TTL as `QueryEmbedder` cache, for consistency).

**Tests to write first.**
- `GraphProximityScorerTest` — fixed graph, known query entities, asserted score ordering; zero-overlap case returns zeros, not errors.
- `SearchResourceHybridGraphIT` — full hybrid path with graph boost enabled, asserting re-order over a BM25-only baseline.
- Regression: with `wikantik.search.graph.boost=0` the output is bit-identical to today's hybrid rerank.

## Phasing

Three phases. Each is independently shippable and reversible (config-flag off).

**Phase 1 — Schema + unification (≈ 1 week, low-risk).**
- V011 migration for `chunk_entity_mentions`.
- `NodeMentionSimilarity` class + swap in `HubDiscoveryService` and `find_similar`.
- Delete ComplEx/TF-IDF code paths and `predict_edges` MCP tool.
- No user-visible change yet; the goal is a clean unified baseline.
- Exit criteria: full `mvn clean install -Pintegration-tests -fae` passes; no callers of removed classes; `/api/graph` still renders identically.

**Phase 2 — Extractor pipeline (≈ 2 weeks, opt-in).**
- `EntityExtractor` interface + Claude + Ollama implementations.
- `AsyncEntityExtractionListener` wired to page-saved events.
- Extractor defaults to `disabled`; ops enables after reviewing a small corpus of proposals.
- Exit criteria: enabling extractor on a seeded 20-page corpus produces plausible proposals that pass admin review at ≥70% rate; error rate (malformed JSON, timeouts) <5%; `kg_rejections` correctly suppresses re-proposals.

**Phase 3 — Graph-aware reranking (≈ 1 week once mentions are populated).**
- `GraphProximityScorer` + `InMemoryGraphNeighborIndex`.
- Hook into `SearchResource.applyHybridRerank`.
- Config-flag default: `boost=0.2`; can be flipped to 0 to disable without redeploy.
- Exit criteria: on a hand-curated eval set of ≥30 queries with known-relevant pages, graph-aware reranking wins or ties in ≥70% of queries vs hybrid-without-graph; p95 search latency delta ≤20ms.

## Critical files to modify / add

| Path | Action |
|---|---|
| `bin/db/migrations/V011__chunk_entity_mentions.sql` | **new** |
| `wikantik-api/src/main/java/com/wikantik/api/knowledge/EntityExtractor.java` | **new** |
| `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/ClaudeEntityExtractor.java` | **new** |
| `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/OllamaEntityExtractor.java` | **new** |
| `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/AsyncEntityExtractionListener.java` | **new** |
| `wikantik-main/src/main/java/com/wikantik/knowledge/embedding/NodeMentionSimilarity.java` | **new** |
| `wikantik-main/src/main/java/com/wikantik/search/hybrid/GraphProximityScorer.java` | **new** |
| `wikantik-main/src/main/java/com/wikantik/search/hybrid/InMemoryGraphNeighborIndex.java` | **new** |
| `wikantik-main/src/main/java/com/wikantik/knowledge/HubDiscoveryService.java` | edit: swap TF-IDF reads for `NodeMentionSimilarity` |
| `wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/KnowledgeMcpInitializer.java` | edit: drop `predict_edges`, re-point `find_similar` |
| `wikantik-rest/src/main/java/com/wikantik/rest/SearchResource.java` (`:177`) | edit: call `GraphProximityScorer` after hybrid rerank |
| `wikantik-main/src/main/java/com/wikantik/knowledge/EmbeddingService.java` | **delete** (+ `ComplExModel`, `TfidfModel`, `EmbeddingRepository`, `ContentEmbeddingRepository`) |
| `wikantik-mcp/.../mcp/tools/` `predict_edges` tool | **delete** |
| `wikantik-war/src/main/config/tomcat/wikantik-custom.properties.template` | add `wikantik.knowledge.extractor=disabled`, `wikantik.search.graph.boost=0.2`, extractor model/timeout config |

## Existing functions/utilities to reuse (not reinvent)

- **Async save-time pattern**: mirror `com.wikantik.search.embedding.AsyncEmbeddingIndexListener` exactly — same event, same queue-and-drain shape, same Prometheus conventions.
- **Circuit breaker + timeout**: reuse `com.wikantik.search.embedding.QueryEmbedder`'s pattern for the Claude client wrapper; don't write a new one.
- **Chunking**: `com.wikantik.knowledge.chunking.ChunkProjector` output is authoritative — extractor consumes the same chunks that become embeddings, so mentions and embeddings are aligned by construction.
- **Hybrid fusion**: `com.wikantik.search.hybrid.HybridFuser` already does weighted rank fusion; graph boost piggybacks on it rather than building parallel scoring.
- **Metadata cache**: `FrontmatterMetadataCache` already exists with `(pageName, lastModified)` keying — query-entity resolution cache follows the same pattern.
- **Prompt caching**: use the `claude-api` skill's guidance when writing `ClaudeEntityExtractor`; cache the system prompt + schema + existing-node dictionary blocks.

## Verification

**Build & unit tests.**
```bash
mvn compile -pl wikantik-main,wikantik-api,wikantik-rest,wikantik-knowledge,wikantik-mcp -q
mvn test -pl wikantik-main -Dtest='NodeMentionSimilarityTest,GraphProximityScorerTest,EntityExtractorContractTest,AsyncEntityExtractionListenerIT'
mvn clean install -T 1C -DskipITs   # full unit run
```

**Integration tests.**
```bash
mvn clean install -Pintegration-tests -fae   # no -T flag (per CLAUDE.md)
```

**Local end-to-end.**
```bash
# 1. Apply V011 and redeploy.
bin/deploy-local.sh
tomcat/tomcat-11/bin/startup.sh

# 2. Seed a test page with known entities; save it; wait for async listeners.
#    Verify chunk_entity_mentions rows appear for the saved page:
PGPASSWORD=... psql -h localhost -U jspwiki -d wikantik \
  -c "SELECT cm.node_id, n.name, cm.confidence, cm.extractor
        FROM chunk_entity_mentions cm JOIN kg_nodes n ON n.id=cm.node_id
       WHERE cm.extracted_at > NOW() - INTERVAL '5 minutes' ORDER BY cm.confidence DESC LIMIT 20;"

# 3. Verify proposals were filed:
#    Admin UI /admin/knowledge/proposals OR:
source <(grep -v '^#' test.properties | sed 's/^test.user.//' | sed 's/=/="/;s/$/"/')
curl -u "${login}:${password}" http://localhost:8080/api/graph/proposals?extractor=claude | jq '.[] | {subject, predicate, object, confidence}'

# 4. Search with and without graph boost and eyeball reranking:
curl "http://localhost:8080/api/search?q=<known-entity-query>&limit=10" | jq '.results | map({pageName, score})'
#    Flip wikantik.search.graph.boost=0 in wikantik-custom.properties, restart, repeat — diff the top 10.
```

**MCP smoke.**
- `discover_schema` shows `chunk_entity_mentions`-derived stats per node type.
- `find_similar` against a well-connected node returns neighbors ranked by Ollama-backed mention similarity (no more TF-IDF scores).
- `predict_edges` tool is gone (error or absent from tool list — expected).

**Rollback.** Every workstream is config-gated:
- Phase 1: the removal of ComplEx/TF-IDF is the non-reversible step; guarded by Phase 1's exit criteria (full IT suite green). The V011 migration is additive and safe to roll forward.
- Phase 2: `wikantik.knowledge.extractor=disabled` flips extraction off instantly; no data corruption path because all writes go to proposals.
- Phase 3: `wikantik.search.graph.boost=0` produces bit-identical hybrid output to today.
