# Agent MCP Surface Redesign

**Date:** 2026-04-24
**Status:** design approved, pending implementation plan
**Primary goal:** realign Wikantik's MCP surface with its actual role — a
source of RAG/GraphRAG context for coding agents — and stop paying tool-
surface tax on use cases that don't happen.

## Why

The MCP surface was built incrementally over the last six months while the
retrieval stack was evolving. Today it is out of step with how the wiki is
actually used from an agent:

- The primary workflow is **"agent consumes context while I'm building"** —
  retrieve passages relevant to the problem at hand, optionally pull a full
  page when deeper context is needed.
- A narrow secondary workflow is **"agent imports a batch of new articles"**
  — occasional, not the default loop.
- Wiki maintenance (audits, broken-link reports, SEO pings, structured data
  previews, page history walks) is real work but happens from the admin UI
  or on-demand scripts, not as the default shape of an agent session.

Meanwhile the retrieval backing has moved forward substantially:

- Hybrid BM25 + dense retrieval with `qwen3-embedding-0.6b`, SUM_TOP_3 chunk
  aggregation, dense-heavy RRF fusion. Recall@20 = 0.975 at peak.
- Heading-aware chunking with atomic list blocks; heading path prepended at
  embed time.
- LLM-based entity extraction (`ClaudeEntityExtractor`,
  `AsyncEntityExtractionListener`, `BootstrapEntityExtractionIndexer`)
  populates `chunk_entity_mentions` (V011), producing a mention-based KG
  that is authoritative over the older link/metadata-derived graph.
- KG-aware rerank that promotes pages whose entities co-mention the query's
  entities.

The MCP surface does not reflect any of this. Concretely:

- `wikantik-mcp` has ~20 tools. Its `search_pages` tool still calls
  `SearchManager.findPages()` directly — **pure lexical Lucene, no hybrid
  rerank, no KG signal**. That is a capability gap, not a choice.
- `wikantik-tools` (OpenAPI tool-server at `/tools`) has 2 tools and does
  apply hybrid rerank via `HybridSearchService`, but returns page-level
  results only — no chunks, no relatedPages.
- `wikantik-knowledge` has an `/knowledge-mcp` endpoint with 6 graph-native
  tools (`discover_schema`, `query_nodes`, `get_node`, `traverse`,
  `search_knowledge`, `find_similar`) backed by `DefaultKnowledgeGraphService`
  whose edges still come from `GraphProjector` (frontmatter `related:`
  fields + link graph), not from the mention-based graph the extractor
  produces.

Three surfaces, three different retrieval paths, no chunk-level exposure
anywhere, and the graph tools read from the wrong backing. This design
collapses that into a coherent picture aligned with how the wiki is used.

## Scope

In scope:

- Redesign the MCP tool surface across `wikantik-mcp`, `wikantik-knowledge`,
  and `wikantik-tools` (OpenAPI).
- Extract a shared `ContextRetrievalService` that is the single home for
  retrieval logic.
- Rebacking of the 6 existing KG tools so they read from the mention-based
  graph.
- Module rename `wikantik-mcp` → `wikantik-admin-mcp` and endpoint rename
  `/mcp` → `/wikantik-admin-mcp`.
- Retirement of the export/preview/import three-step content workflow in
  favor of direct `write_pages` + `update_page` with optimistic locking.

Out of scope:

- Changes to the retrieval stack itself (hybrid fusion, chunker,
  embedding model). These remain as they are.
- Changes to the KG extractor pipeline or the underlying `chunk_entity_mentions`
  schema.
- Changes to authentication / API-key admin (the planned 9b work is
  separate).

## Non-goals

- No attempt to unify MCP and OpenAPI into a single transport. They stay
  as separate wire protocols, both driven by the shared service.
- No full re-write of the 6 existing KG tools' API shape — only the backing
  moves.
- No new write capabilities beyond page creation / update / rename.

## High-level design

Three MCP-style surfaces, one shared retrieval service, one service
boundary for KG reads. All calls that look up wiki context for an agent
funnel through `ContextRetrievalService` in `wikantik-knowledge`.

```
                       ┌──────────────────────────────────────┐
                       │ wikantik-knowledge                   │
                       │                                      │
                       │ ContextRetrievalService (new)        │
                       │   retrieve(query, filters)           │
                       │   getPage(name, version)             │
                       │   listPages(filters)                 │
                       │   listMetadataValues(field)          │
                       │                                      │
                       │ backed by: HybridSearchService,      │
                       │            NodeMentionSimilarity,    │
                       │            PageManager,              │
                       │            FrontmatterParser,        │
                       │            DefaultKnowledgeGraph-    │
                       │                         Service      │
                       └────────────────┬─────────────────────┘
                                        │
      ┌─────────────────────────────────┼──────────────────────────────────┐
      ▼                                 ▼                                  ▼
┌────────────────────┐   ┌─────────────────────────┐       ┌──────────────────────┐
│ /knowledge-mcp     │   │ /tools (OpenAPI 3.1)    │       │ /wikantik-admin-mcp  │
│ wikantik-knowledge │   │ wikantik-tools          │       │ wikantik-admin-mcp   │
│                    │   │                         │       │ (renamed from        │
│ READ / CONSUME     │   │ MCP-less-client shim    │       │  wikantik-mcp)       │
│                    │   │                         │       │                      │
│ 4 new page tools   │   │ get_page (upgraded)     │       │ WRITES               │
│ 6 existing KG      │   │ search_wiki (upgraded)  │       │  write_pages         │
│   tools, rebacked  │   │                         │       │  update_page         │
│                    │   │ same shape as           │       │  rename_page         │
│                    │   │ retrieve_context        │       │                      │
│                    │   │                         │       │ MAINTENANCE (12)     │
│                    │   │                         │       │  verify_pages        │
│                    │   │                         │       │  get_broken_links    │
│                    │   │                         │       │  get_orphaned_pages  │
│                    │   │                         │       │  get_outbound_links  │
│                    │   │                         │       │  get_backlinks       │
│                    │   │                         │       │  get_page_history    │
│                    │   │                         │       │  diff_page           │
│                    │   │                         │       │  preview_structured_ │
│                    │   │                         │       │     data             │
│                    │   │                         │       │  ping_search_engines │
│                    │   │                         │       │  get_wiki_stats      │
│                    │   │                         │       │  list_proposals      │
│                    │   │                         │       │  propose_knowledge   │
└────────────────────┘   └─────────────────────────┘       └──────────────────────┘
    10 tools                 2 tools                           15 tools
```

### Interaction shape (why these tools exist)

**Primary pattern — agent-initiated RAG.** Agent silently pulls wiki
context when it thinks it's relevant. The dominant call is
`retrieve_context(query)` returning pages-with-chunks-with-relatedPages.
No human direction per call.

**Secondary pattern — user-directed context loading.** User tells the
agent "pull the cluster I'm working on" or "read PageY into context." The
agent uses `list_pages(filters)` or `get_page(name)` deliberately. Small,
bounded, pinned context.

**Tertiary pattern — occasional article import.** User asks the agent to
create or update wiki pages. Agent calls `write_pages` (batch create) or
`update_page` (edit with hash check) on the admin MCP.

**Not a pattern — agent-driven wiki maintenance.** Verify runs,
broken-link audits, structured data previews are possible through the
admin MCP but are not part of the default loop. They exist for ad-hoc
"have Claude help me audit" sessions and because they are already
written.

### The mention-based graph change

The 6 existing KG tools (`discover_schema`, `query_nodes`, `get_node`,
`traverse`, `search_knowledge`, `find_similar`) keep their API shapes.
Their backing moves: edges and node provenance come from the mention
graph populated by the extractor, not from `GraphProjector`'s
link/frontmatter derivation.

Implications:

- `query_nodes` returns nodes with provenance tags reflecting the
  extractor model (`claude-3-5-sonnet`, `qwen3-entity-extraction`, etc.)
  and human-authored overrides, not implicit graph projection.
- `traverse` walks edges that express "these two entities co-occur in
  chunks" (mention-based) rather than "these two pages link to each
  other" (legacy structural).
- `search_knowledge` full-text over node names/properties as before; the
  set of nodes it searches over is authoritative post-extraction.
- `find_similar` already uses `NodeMentionSimilarity` — no change.
- `discover_schema` reports node types the extractor produces plus any
  human-added types. Legacy structural types are absent.

`GraphProjector` becomes dead code once the service reads are migrated;
retirement is a later cycle.

## Detailed design

### `/knowledge-mcp` — 10 tools

#### New: `retrieve_context`

Primary RAG entry point. Returns pages with their top contributing
chunks and a small `relatedPages` hint per page derived from mention
co-occurrence.

Input:

```json
{
  "query": "string (required) — natural language",
  "maxPages": "int (default 5, max 20)",
  "chunksPerPage": "int (default 3, max 5)",
  "filters": {
    "cluster": "string — optional",
    "tags": ["string"],
    "type": "string — e.g. 'article'",
    "modifiedAfter": "ISO-8601"
  }
}
```

Output:

```json
{
  "query": "string (echoed)",
  "pages": [
    {
      "name": "RetrievalExperimentHarness",
      "url": "https://.../RetrievalExperimentHarness",
      "score": 0.89,
      "summary": "from frontmatter",
      "cluster": "search",
      "tags": ["search", "retrieval", "embeddings"],
      "contributingChunks": [
        {
          "headingPath": "Retrieval Experiment Harness > 7. Model selection > Decision rationale",
          "text": "qwen3 leads on recall at both cutoffs...",
          "chunkScore": 0.86,
          "matchedTerms": ["recall", "qwen3"]
        }
      ],
      "relatedPages": [
        {"name": "HybridRetrieval", "reason": "shared entities: qwen3, bm25"},
        {"name": "EmbeddingsInGenAI", "reason": "shared entities: dense embedding, cosine similarity"}
      ]
    }
  ],
  "totalMatched": 17
}
```

Behavior:

- Calls `HybridSearchService` to get ranked chunks (BM25 + dense fusion,
  KG-aware rerank already inside).
- Aggregates chunk scores to pages via SUM_TOP_3 (existing aggregation).
- For each returned page, picks the top `chunksPerPage` contributing
  chunks and attaches them.
- For each returned page, resolves up to 3–5 `relatedPages` via
  `NodeMentionSimilarity` (mention-centroid neighbors of the page's
  extracted entities). `reason` lists the specific shared entities that
  caused the hit.
- Applies `filters` as a pre-filter over the candidate page set before
  ranking. View ACLs applied as today.

#### New: `get_page`

Pinned-context escape hatch. Replaces today's `read_page`.

Input:

```json
{ "pageName": "string (required)", "version": "int (optional, default latest)" }
```

Output:

```json
{
  "exists": true,
  "pageName": "...",
  "content": "markdown body, no frontmatter",
  "metadata": {},
  "version": 12,
  "contentHash": "sha256 hex",
  "author": "...",
  "lastModified": "..."
}
```

Identical semantics to today's `read_page`. Name change is the only
delta; the rest of the contract (content vs metadata split, content
hash, system-page flag) carries over.

#### New: `list_pages`

Filter-driven browse. Absorbs `query_metadata`, `recent_changes`, and
the listing side of `list_pages`. All filters optional, AND semantics.

Input:

```json
{
  "cluster": "string",
  "tags": ["string"],
  "type": "string",
  "author": "string",
  "modifiedAfter": "ISO-8601",
  "modifiedBefore": "ISO-8601",
  "limit": "int (default 50, max 200)",
  "offset": "int (default 0)"
}
```

Output:

```json
{
  "pages": [
    {
      "name": "...",
      "cluster": "...",
      "tags": [...],
      "summary": "...",
      "lastModified": "..."
    }
  ],
  "totalMatched": 123,
  "limit": 50,
  "offset": 0
}
```

No chunks, no relatedPages — this is browsing, not RAG. Agent gets page
names to follow up with `get_page` or `retrieve_context`.

#### New: `list_metadata_values`

Discovery helper for "what clusters exist?" Identical to today's tool.

Input:

```json
{ "field": "cluster" }
```

Output:

```json
{ "field": "cluster", "values": [ {"value": "search", "count": 14}, ... ] }
```

#### Kept (rebacked): `discover_schema`, `query_nodes`, `get_node`, `traverse`, `search_knowledge`, `find_similar`

Shapes unchanged. See "The mention-based graph change" above for the
backing migration.

### `/wikantik-admin-mcp` — 15 tools

#### New: `write_pages`

Batch-create new pages. Best-effort per-page, idempotent where possible.

Input:

```json
{
  "pages": [
    { "pageName": "string", "content": "markdown body", "metadata": {} }
  ]
}
```

Output:

```json
{
  "results": [
    { "pageName": "...", "created": true, "contentHash": "..." },
    { "pageName": "...", "created": false, "error": "already exists" }
  ],
  "summary": { "total": 5, "created": 4, "failed": 1 }
}
```

Behavior:

- Fails individual pages that already exist; agent uses `update_page`
  for those.
- Author set from MCP client name via existing `AuthorConfigurable`
  plumbing.
- Frontmatter is serialized + prepended to body server-side.
- Returns per-item status so the agent can retry only the failures.

#### New: `update_page`

Edit existing page with optimistic locking.

Input:

```json
{
  "pageName": "string",
  "content": "markdown body",
  "metadata": { /* optional — merged with existing if omitted */ },
  "expectedContentHash": "string from last get_page / retrieve_context"
}
```

Output (success):

```json
{ "pageName": "...", "updated": true, "newContentHash": "...", "newVersion": 13 }
```

Output (hash mismatch):

```json
{ "pageName": "...", "updated": false, "error": "hash mismatch", "currentHash": "..." }
```

Agent on mismatch re-fetches and re-reasons. No force-overwrite mode.

#### Kept (moved): `rename_page` and the 12 maintenance tools

Unchanged behavior. Move as-is, update registration.

### `/tools` (OpenAPI tool-server) — 2 tools, upgraded

Both tools switch to calling `ContextRetrievalService`.

- `search_wiki` — response shape becomes `{query, results: [pages-with-
  contributingChunks-with-relatedPages], total}`. Same envelope as
  `retrieve_context` in MCP. OpenWebUI and other MCP-less clients get
  the same context quality as MCP clients.
- `get_page` — shape unchanged.
- OpenAPI document updated to reflect the new response schema.

### Service layer: `ContextRetrievalService`

```java
package com.wikantik.api.knowledge;

public interface ContextRetrievalService {

    RetrievalResult retrieve( ContextQuery query );

    RetrievedPage getPage( String pageName, int version );

    PageList listPages( PageListFilter filter );

    List< MetadataValue > listMetadataValues( String field );
}
```

Records (in `wikantik-api`):

- `ContextQuery(query, maxPages, chunksPerPage, filter)`
- `RetrievalResult(query, pages, totalMatched)`
- `RetrievedPage(name, url, score, summary, cluster, tags,
  contributingChunks, relatedPages)`
- `RetrievedChunk(headingPath, text, chunkScore, matchedTerms)`
- `RelatedPage(name, reason)` — `reason` is a comma-joined list of
  shared entity names, human-readable
- `PageListFilter(cluster, tags, type, author, modifiedAfter,
  modifiedBefore, limit, offset)`
- `PageList(pages, totalMatched, limit, offset)`
- `MetadataValue(value, count)`

Implementation (`DefaultContextRetrievalService` in `wikantik-knowledge`)
composes:

- `HybridSearchService` for chunk-level ranking.
- `PageAggregator` / SUM_TOP_3 for chunk → page aggregation.
- `NodeMentionSimilarity` for relatedPages derivation.
- `PageManager` + `FrontmatterParser` for page fetch / metadata parse.
- `DefaultKnowledgeGraphService` for entity name lookups (reason
  strings).

### Module layout

```
wikantik-api/
  + api/knowledge/ContextRetrievalService.java
  + api/knowledge/ContextQuery.java
  + api/knowledge/RetrievalResult.java
  + api/knowledge/RetrievedPage.java
  + api/knowledge/RetrievedChunk.java
  + api/knowledge/RelatedPage.java
  + api/knowledge/PageListFilter.java
  + api/knowledge/PageList.java
  + api/knowledge/MetadataValue.java

wikantik-knowledge/
  + knowledge/DefaultContextRetrievalService.java
  + knowledge/mcp/RetrieveContextTool.java
  + knowledge/mcp/GetPageTool.java                (lifted from wikantik-mcp ReadPageTool)
  + knowledge/mcp/ListPagesTool.java              (absorbs query_metadata + recent_changes)
  + knowledge/mcp/ListMetadataValuesTool.java     (lifted from wikantik-mcp)
  ~ knowledge/mcp/KnowledgeMcpInitializer.java    (registers 4 new tools alongside 6 existing)
  ~ knowledge/DefaultKnowledgeGraphService.java   (rebacking: reads from mention graph)
  - knowledge/GraphProjector.java                 (retire in final cycle)

wikantik-admin-mcp/                               (renamed directory + pom artifactId)
  ~ McpServerInitializer.java                     (endpoint /mcp → /wikantik-admin-mcp)
  + tools/WritePagesTool.java
  + tools/UpdatePageTool.java
  - tools/ReadPageTool.java                       (moved)
  - tools/SearchPagesTool.java                    (absorbed into retrieve_context)
  - tools/QueryMetadataTool.java                  (absorbed into list_pages)
  - tools/RecentChangesTool.java                  (absorbed into list_pages)
  - tools/ListPagesTool.java                      (absorbed)
  - tools/ListMetadataValuesTool.java             (moved)
  - tools/ExportContentTool.java                  (workflow retired)
  - tools/PreviewImportTool.java                  (workflow retired)
  - tools/ImportContentTool.java                  (replaced by write_pages/update_page)
  (all other tools kept: verify_pages, get_broken_links, get_orphaned_pages,
   get_outbound_links, get_backlinks, get_page_history, diff_page,
   preview_structured_data, ping_search_engines, get_wiki_stats,
   list_proposals, propose_knowledge, rename_page)

wikantik-tools/
  ~ tools/SearchWikiTool.java                     (calls ContextRetrievalService)
  ~ tools/GetPageTool.java                        (calls ContextRetrievalService)
  ~ tools/OpenApiDocument.java                    (new response schema)
```

## Rollout order

Each cycle is a self-contained spec → plan → implementation unit.

1. **Cycle 1 — `ContextRetrievalService` extraction. ✓** Interface + records
   in `wikantik-api`. `DefaultContextRetrievalService` in
   `wikantik-knowledge`. Refactor `SearchResource` and `SearchWikiTool`
   to call the service (no wire-level changes yet). Unit + integration
   tests lock the contract.

2. **Cycle 2 — new `/knowledge-mcp` tools. ✓** `retrieve_context`,
   `get_page`, `list_pages`, `list_metadata_values` registered in
   `KnowledgeMcpInitializer` alongside the existing 6 KG tools. Both
   the old page tools in `wikantik-mcp` and the new ones in
   `/knowledge-mcp` work during the transition so the MCP client can be
   switched over without downtime.

3. **Cycle 3 — KG tools rebacked onto mention graph. ✓**
   `DefaultKnowledgeGraphService.queryNodes` / `traverse` /
   `searchKnowledge` switch to reading the mention-derived edges. New
   tests verify the returned node set matches the extractor's output
   for a frozen fixture corpus. `find_similar` and `discover_schema`
   audited for the same alignment.

4. **Cycle 4 — admin-mcp rename + writes. ✓** Module directory rename
   `wikantik-mcp` → `wikantik-admin-mcp`, pom artifactId updated,
   endpoint `/mcp` → `/wikantik-admin-mcp`. New `write_pages` and
   `update_page` tools. Delete `ReadPageTool`, `SearchPagesTool`,
   `QueryMetadataTool`, `RecentChangesTool`, `ListPagesTool`,
   `ListMetadataValuesTool`, `ExportContentTool`, `PreviewImportTool`,
   `ImportContentTool`. MCP client configuration updated to the new
   URL.

5. **Cycle 5 — tool-server upgrade. ✓** `SearchWikiTool` returns the new
   chunks-with-relatedPages envelope. OpenAPI document regenerated.
   Tests updated.

6. **Cycle 6 — `GraphProjector` retirement. ✓** Confirm no callers, delete
   `GraphProjector.java` and the `ChunkProjector` path it fed. Any
   migration / rebuild scripts that relied on it are replaced or
   removed.

Cycles 1–2 deliver the core agent-facing improvement. Cycles 3–4 are
hygiene and consistency. Cycle 5 closes the gap for OpenWebUI. Cycle 6
is dead-code cleanup.

## Testing approach

- **`ContextRetrievalService` contract tests.** Fake `HybridSearchService`,
  fake `NodeMentionSimilarity`, assert response shape + `relatedPages`
  derivation against known mention fixtures.
- **Tool-level tests in `wikantik-knowledge/mcp`.** One test class per
  tool following the pattern of existing KG tool tests.
- **End-to-end IT.** Start the wiki with the 23k-chunk corpus, call
  `/knowledge-mcp` `retrieve_context` with a seed query known to have
  KG neighbors (e.g. "hybrid retrieval"). Assert:
  - top-1 page is expected;
  - `contributingChunks` non-empty with heading paths;
  - `relatedPages` non-empty with resolvable reasons.
- **Shape parity test.** Assert `SearchWikiTool` OpenAPI response equals
  `retrieve_context` MCP response for the same query (same service, same
  shape).
- **Regression test for KG backing.** With a fixed fixture of
  `chunk_entity_mentions` rows, assert `query_nodes`, `traverse`, and
  `find_similar` return identical sets before and after rebacking for
  queries that don't depend on the removed link-derived edges.
- **Dogfood gate.** Before cycle 6 merges, run a real Claude Code session
  against the new `/knowledge-mcp` for an actual development task.
  Verify the agent finds context that the old surface missed (e.g. via
  the harness's `hard` query category). Iterate if it doesn't.

## Backwards compatibility

- User is the sole developer on main. No external MCP consumers apart
  from the local Claude Code config and the OpenWebUI tool-server.
- `/mcp` → `/wikantik-admin-mcp` URL change: update Claude Code's MCP
  configuration in the same commit. Call this out in release notes.
- OpenAPI tool-server response shape change in cycle 5: note in release
  notes; OpenWebUI registrations are reconfigured once.
- The 6 existing KG tools keep their wire contracts. Only their backing
  moves. Clients see behavioral drift (different node / edge set) but no
  API breakage.
- Tool names removed from the admin-mcp (`read_page`, `search_pages`,
  `query_metadata`, `recent_changes`, `list_pages`, `list_metadata_values`,
  `export_content`, `preview_import`, `import_content`) have their
  capabilities available either at `/knowledge-mcp` (reads) or through
  the new write tools (writes). No capability is lost.

## Risks and open questions

- **`relatedPages` quality.** `NodeMentionSimilarity` is already in
  production for rerank, but using it to produce a human-readable
  `reason` ("shared entities: X, Y") requires selecting which shared
  entities to expose. Too many → noisy; too few → misleading. Default:
  top 3 shared entities by (confidence × mention count). Will tune from
  dogfooding.
- **Mention graph completeness.** The extractor has not been run over
  the full 23k-chunk corpus yet at full quality. Until it is, the
  rebacked KG tools in cycle 3 may return an incomplete node set. The
  phased rollout handles this — cycle 3 can land after a full extractor
  pass.
- **SUM_TOP_3 loss when `chunksPerPage < 3`.** If the agent asks for
  `chunksPerPage=1`, the returned chunk is the top-contributor but the
  page score still reflects SUM_TOP_3. That's correct (don't lie about
  the score) but means the single shown chunk may not fully explain the
  score. Spell this out in tool description.
- **View ACLs on related pages.** `relatedPages` is derived from the KG,
  but the linked page may not be visible to the caller. Filter
  `relatedPages` by the caller's permissions (same as `retrieve_context`
  results).
- **Deprecation of `GraphProjector` may uncover implicit consumers.**
  Mitigation: search all modules for `GraphProjector` references before
  cycle 6, convert any remaining ones, then delete.
- **Cycle 3 rebacking strategy: read-filter vs. data purge.** Two options
  for moving the 6 KG tools onto the mention graph. Option A: filter
  reads by provenance so `query_nodes` / `traverse` / etc. ignore
  `GraphProjector`-written rows (simpler, reversible, but
  `GraphProjector` keeps writing into tables that are then read-ignored).
  Option B: stop `GraphProjector` writing entirely and purge its rows
  (cleaner data model, irreversible). Option B is the end state but can
  be staged — start with A in cycle 3, land B as part of cycle 6
  alongside `GraphProjector` retirement. The plan will pick between
  them.

## Deferred decisions

- Whether to add a `retrieve_by_entities` or `neighborhood_of(entity)`
  tool in a future cycle as a third consumption path. Option C from
  brainstorming. Hold until a concrete agent workflow asks for it.
- Whether to expose a `force: true` override on `update_page` for cases
  where the agent intentionally wants to stomp. Skip until a workflow
  actually needs it.
- Unified API-key admin story (project memory: 9b) — how the two MCP
  endpoints + tool-server endpoint share / segment scopes. Handled in
  its own spec.
