# Content Chunking — Design

**Date:** 2026-04-16 (revised 2026-04-17)
**Author:** Jake Fear (brainstormed with Claude)
**Status:** Draft — revised per feedback; awaiting review
**Scope:** Introduce a per-page chunking pipeline that splits wiki page bodies into
heading-aware, token-bounded passages and persists them in a new `kg_content_chunks`
table. Runs on every page save via a new `ChunkProjector` `PageFilter`. An
admin-triggered **async combined rebuild** reconstructs both the Lucene index and
the chunks table in one coordinated operation, with a new admin UI that reports
live status and exposes the rebuild trigger. **No embeddings, no retrieval
consumer, no Ollama integration** — those are follow-on specs that build on this
substrate.

**Changes from 2026-04-16 draft:**
- Backfill is now async (production corpus is already >2000 pages and growing).
- Backfill is combined with the existing Lucene reindex — one operation, one
  button, one status surface — because algorithm changes affect both layers.
- Adds a new admin UI (a tab on `AdminContentPage`) showing indexed-document
  count, chunk count, Lucene queue depth, and rebuild status with polling.

## Goal

Establish the smallest, no-regrets piece of substrate required for any future
retrieval work: a stable, versionable, queryable representation of wiki content at
passage granularity. Landing this now lets us:

1. Inspect real chunk boundaries against our >2,000-page production corpus
   before committing to an embedding model or a retrieval architecture.
2. Measure chunk distributions (count, size, heading depth) to size downstream
   work honestly.
3. Swap embedding backends later without re-chunking the entire corpus, because
   chunk identity is independent of any model.
4. Ship a real foundation stone this sprint without taking on GPU, model-choice,
   or retrieval-side risk.

## Non-Goals

- **No embeddings.** `kg_content_chunks` stores text only. A separate future spec
  introduces a `kg_chunk_embeddings(chunk_id, model_version, vector)` table.
- **No retrieval path.** Nothing reads from `kg_content_chunks` in this slice.
  Lucene, MCP `search_pages`, and the existing TF-IDF `kg_content_embeddings` stay
  untouched.
- **No replacement of `kg_content_embeddings`.** The legacy TF-IDF table keeps its
  role as today's similar-pages backend. A later spec deprecates it.
- **No Ollama / external service dependency.** Chunking is pure Java.
- **No ACL enforcement on the chunks table.** Since there is no retrieval consumer
  yet, chunks inherit the ACL of their source page implicitly — future retrieval
  specs are responsible for filtering. Documented as a guard-rail, not a bug.
- **No overlap between chunks.** Sliding-window overlap is a common tuning knob
  left for a follow-up if retrieval recall proves weak.
- **No real tokenizer.** We use a character-based token-count estimate; the
  canonical tokenizer will arrive with the embedding model choice.
- **No cross-restart rebuild persistence.** Rebuild progress lives in memory.
  If Tomcat restarts mid-rebuild, the in-flight state is lost and the operator
  re-triggers from the UI. No job queue table, no resume.
- **No per-page rebuild endpoint.** Save-time chunking already handles the
  single-page case; the admin rebuild surface is intentionally bulk-only.
- **No graph re-projection in the combined rebuild.** `POST
  /admin/knowledge/project-all` stays as its own endpoint — graph projection
  has different semantics (node identity, edge diffing) and different failure
  modes. Combining it into the content rebuild is a future question.
- **No partial rebuild (Lucene-only or chunks-only).** One "Rebuild Indexes"
  button. If an operator needs finer control later, we add it then.
- **No non-destructive / soft-touch rebuild.** The combined rebuild always
  clears Lucene and `kg_content_chunks` before repopulating. Operators who
  explicitly want "re-enqueue everything without wiping and without a
  degraded search window" continue to use the deprecated
  `POST /admin/content/reindex` endpoint, which does idempotent per-page
  upserts. The two endpoints coexist until a later spec removes the old
  one.

## Background — where this plugs in

- `wikantik-main/src/main/java/com/wikantik/knowledge/GraphProjector.java:41`
  is a `PageFilter` that runs on every page save and projects frontmatter +
  links into the knowledge graph. We model `ChunkProjector` on the same shape:
  a second `PageFilter` that runs on save, independently of `GraphProjector`,
  so chunking failures do not prevent graph projection and vice versa.
- `bin/db/migrations/V004__knowledge_graph.sql` established the KG tables. The
  next available migration number is **V008**.
- The existing `kg_content_embeddings` (TF-IDF, 512-dim) lives in
  `LuceneSearchProvider.java:524-550` and is written by admin/batch paths, not
  on page save. It stays where it is; we do not entangle with it.
- `bin/db/migrations/README.md` requires idempotent DDL and forbids editing
  applied migrations. V008 follows that convention.
- The markdown rendering pipeline already uses Flexmark. `NodeTextAssembler`
  (`wikantik-main/src/main/java/com/wikantik/search/NodeTextAssembler.java`)
  provides the existing strip-markdown path for search indexing. We do **not**
  reuse it directly — chunking needs structure (headings, code fences), not
  prose-only text — but we consult it to stay consistent.

### Existing indexing infrastructure we're integrating with

- `LuceneSearchProvider.doFullLuceneReindex()`
  (`wikantik-main/src/main/java/com/wikantik/search/LuceneSearchProvider.java:307`)
  is the startup-time full-reindex path, synchronous.
- `LuceneSearchProvider.reindexPage(Page)` (line 688) enqueues to the
  synchronized `updates` list (line 156).
- `LuceneUpdater extends WikiBackgroundThread` (lines 941–1027) drains that
  queue every 5 s (configurable via `PROP_LUCENE_INDEXDELAY`), logging every
  100 pages. `getReindexQueueDepth()` (line 857) returns current backlog but
  is **not** exposed over REST today.
- `POST /admin/content/reindex` (`AdminContentResource.handleReindex`,
  `wikantik-rest/src/main/java/com/wikantik/rest/AdminContentResource.java`
  line ~218) iterates `PageManager.getAllPages()` and enqueues every page
  onto the `LuceneUpdater` queue. Returns `{started: true, pagesQueued: N}`
  immediately; reports no progress.
- **Prior art for async progress:** `backfill-frontmatter` on
  `AdminKnowledgeResource` (lines 1123–1196). Spawns a daemon thread,
  maintains volatile fields (`backfillRunning`, `backfillTotal`,
  `backfillProcessed`, `backfillErrors`), exposes `GET
  /admin/knowledge/backfill-frontmatter` returning a status JSON. The
  frontend `ContentEmbeddingsTab.jsx` polls it every 2000 ms. This is the
  pattern we mirror for the combined rebuild.

## Preflight dependency — Lucene system-page filter

Audit finding: `LuceneSearchProvider` currently has **no** system-page
filter. `handleReindex` at `AdminContentResource.java:297` calls
`PageManager.getAllPages()` with no filter, and `LuceneUpdater` indexes
whatever it receives. The existing production Lucene index therefore
contains CSS themes, navigation fragments, and other system pages.

This must be fixed **before** the new rebuild lands, so that the new
rebuild is a true superset of the old `/reindex` page set. Without this
fix, either (a) the new rebuild iterates non-system pages and silently
removes system-page entries from Lucene on first run, or (b) it iterates
all pages and indexes system junk forever.

### Scope of the fix (separate small task, not this spec)

- Add a `SystemPageRegistry` check at the entry points to Lucene
  indexing:
    - `LuceneSearchProvider.reindexPage(Page)` — short-circuits if the
      page is a system page.
    - `LuceneSearchProvider.doFullLuceneReindex()` — skips system pages
      while iterating.
- Save-time path: `LuceneSearchProvider` is already wired as a listener
  for page saves; the same filter applies there.
- Test: saving a system page produces no Lucene update; a full reindex
  skips system pages; an existing system-page entry is removed on next
  per-page reindex of that page (the Lucene update is a delete-then-add
  pattern, so a filtered "add" still performs the delete — verify this
  or add an explicit delete path).
- Existing production indexes will still contain system-page entries
  until the operator triggers either `/reindex` (which will delete-only
  those entries on its pass, once the filter is in place) or the new
  rebuild (which clears the directory entirely). No schema change, no
  migration.
- This is a user-observable search behavior change — system pages
  stop appearing in search hits. Given system pages are CSS, nav, and
  template fragments, the risk of anyone relying on this is
  negligible, but it should be called out in the commit message.

This prep task ships as its own small commit before the implementation
plan for this spec kicks off. Once landed, the statement "the new
rebuild is a full superset of `/reindex`" is true.

## Architecture

New code spans three modules: chunking primitives in `wikantik-main`, the
async orchestrator in `wikantik-main/admin`, two REST endpoints in
`wikantik-rest`, and one React tab in `wikantik-frontend`. Plus one new
migration.

```
wikantik-main/src/main/java/com/wikantik/knowledge/chunking/
    Chunk.java                    # immutable record
    ContentChunker.java           # pure function: ParsedPage -> List<Chunk>
    ChunkProjector.java           # PageFilter — runs on save
    ContentChunkRepository.java   # JDBC persistence

wikantik-main/src/main/java/com/wikantik/admin/
    ContentIndexRebuildService.java  # async orchestrator (singleton)
    IndexStatusSnapshot.java         # immutable status record

wikantik-main/src/test/java/com/wikantik/knowledge/chunking/
    ContentChunkerTest.java
    ChunkProjectorTest.java
    ContentChunkRepositoryTest.java

wikantik-main/src/test/java/com/wikantik/admin/
    ContentIndexRebuildServiceTest.java

wikantik-rest/src/main/java/com/wikantik/rest/AdminContentResource.java
    # add GET /admin/content/index-status and POST /admin/content/rebuild-indexes
    # deprecate (but keep) POST /admin/content/reindex

wikantik-frontend/src/components/admin/
    IndexStatusTab.jsx            # new tab on AdminContentPage
    AdminContentPage.jsx          # edit to add the tab and replace raw button

bin/db/migrations/V008__content_chunks.sql
```

`ContentChunker` is stateless and does no I/O. `ChunkProjector` owns the save-time
orchestration (chunk → diff against existing rows → persist). `ContentChunkRepository`
is the only class that touches JDBC. This separation lets every component be
unit-tested without a database except the repository.

### Data flow on page save

```
PageSave
  ├─> GraphProjector.postSave(...)      [existing, unchanged]
  └─> ChunkProjector.postSave(...)      [new]
          ├─ parse page (FrontmatterParser, shared)
          ├─ ContentChunker.chunk(parsedPage) -> List<Chunk>
          ├─ ContentChunkRepository.findByPage(pageName) -> List<StoredChunk>
          ├─ diff by (chunk_index, content_hash)
          └─ apply diff: delete-removed + upsert-changed + insert-new
```

The diff step is important for forward compatibility: a chunk whose `content_hash`
did not change keeps its row `id`, which in turn preserves any future embedding
row pointing at it. Without the diff, every page save would invalidate all of
that page's embeddings — a catastrophic regression once embeddings exist.

### Transaction boundary

`ChunkProjector.postSave` runs in its own transaction, separate from the page
write and from `GraphProjector`. A chunking failure is logged at `WARN` with the
page name and exception, and does **not** propagate. Per `CLAUDE.md`: no empty
catches, always log with context.

Rationale: chunking is a derived artifact. Failing a page save because we could
not re-chunk the page is a far worse user experience than having chunks briefly
out of sync with the page body. Out-of-sync state is recoverable via backfill.

## Chunker design

### Token budget

- **Target:** 300 tokens per chunk.
- **Max:** 512 tokens (hard ceiling).
- **Min:** 80 tokens. Chunks below this are merged forward into the next chunk
  where possible.
- **Estimator:** `token_count_estimate = ceil(char_count / 4.0)`. This is the
  standard GPT-family heuristic and is close enough for planning; the real
  tokenizer arrives with the embedding model. Both `char_count` (exact) and
  `token_count_estimate` are stored so we can recompute later without re-chunking.

Chosen because: 300 is the well-documented RAG sweet spot across multiple
evaluations; bge-m3's 8192 max sequence length gives huge headroom; our
downstream generator (Gemma-class local model) is far more sensitive to
retrieval *precision* than to chunk size, favouring smaller chunks.

### Splitting algorithm

1. **Frontmatter is excluded.** The body string passed to the chunker is the
   page content with YAML/TOML frontmatter already stripped by
   `FrontmatterParser`.
2. **Parse body as Markdown AST via Flexmark** (reusing the existing parser
   configuration so rendering and chunking stay consistent on syntax edge cases).
3. **Walk the AST, tracking a heading stack.** Each ATX/Setext heading push/pops
   the stack by level. The stack at the point of chunk emission becomes the
   chunk's `heading_path`.
4. **Accumulate block-level nodes** (paragraphs, lists, blockquotes) into the
   current chunk until the next block would push the chunk over the `max`
   threshold, then emit.
5. **Atomic blocks:**
    - Fenced code blocks are **never split.** A code block larger than `max`
      becomes its own chunk (documented exception; logged at `DEBUG` if the
      chunk exceeds `max * 2`).
    - Tables are treated the same way — atomic, own chunk if needed.
6. **Oversize paragraphs:** if a single paragraph exceeds `max`, split on
   sentence boundaries (regex on `. `, `! `, `? ` followed by capitalized
   letter or newline). If a single sentence still exceeds `max`, split on
   character boundary at the nearest whitespace and log at `WARN`.
7. **Heading-only boundaries:** when a heading is encountered, flush the current
   chunk if it is at or above `min`. If below `min`, continue accumulating
   across the heading boundary — the next heading still appears in
   `heading_path` when the chunk eventually flushes.
8. **Plugin markup** (`[{Plugin}]` / `[{Plugin}]()`) is preserved as literal
   text in the chunk. Chunks are raw content, not rendered output.
9. **Empty pages** (zero body length after frontmatter strip) produce zero
   chunks. The page row exists; chunk rows do not. Backfill and save-time
   handle zero-chunk pages identically.

### Chunk identity

```
content_hash = SHA-256( heading_path || '\n' || text )[0..16]  // hex, 16 chars
```

16 hex chars = 64 bits of entropy. Collision probability across a corpus of
~50k chunks is ~7e-11, well below the threshold where we would care. The hash
is used only for change detection, never as a primary key.

## Schema

New migration `bin/db/migrations/V008__content_chunks.sql`:

```sql
CREATE TABLE IF NOT EXISTS kg_content_chunks (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    page_name            TEXT      NOT NULL,
    chunk_index          INT       NOT NULL,
    heading_path         TEXT[]    NOT NULL DEFAULT '{}',
    text                 TEXT      NOT NULL,
    char_count           INT       NOT NULL,
    token_count_estimate INT       NOT NULL,
    content_hash         TEXT      NOT NULL,
    created              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT kg_content_chunks_page_index_uniq UNIQUE (page_name, chunk_index)
);

CREATE INDEX IF NOT EXISTS idx_kg_content_chunks_page_name
    ON kg_content_chunks (page_name);
CREATE INDEX IF NOT EXISTS idx_kg_content_chunks_content_hash
    ON kg_content_chunks (content_hash);

GRANT SELECT, INSERT, UPDATE, DELETE ON kg_content_chunks TO :app_user;
```

Design choices worth naming:

- **No `embedding` column.** Embeddings live in their own table (future spec)
  joined on `chunk_id`. Keeping them separate lets us add, drop, or run
  multiple models without DDL on this table.
- **`heading_path TEXT[]`.** Postgres arrays are indexable and cheap. Stored
  in outer-to-inner order so `heading_path[1]` is the top-level heading.
- **UUID primary key.** Matches `kg_nodes` / `kg_edges` convention. Stable
  across re-chunking via the diff mechanism (unchanged chunks keep their id).
- **`UNIQUE (page_name, chunk_index)` without `model_version`.** Chunks are
  model-agnostic text. The embedding table will carry `model_version`.
- **No foreign key to `kg_nodes`.** Chunks exist for every page; not every
  page has a KG node (system pages, orphan content). Coupling the tables would
  create spurious referential-integrity work.
- **Forward-looking (not in this migration):**
  ```sql
  -- Deferred to future spec, shown here for orientation only:
  -- CREATE TABLE kg_chunk_embeddings (
  --     chunk_id      UUID REFERENCES kg_content_chunks(id) ON DELETE CASCADE,
  --     model_version TEXT NOT NULL,
  --     embedding     VECTOR(1024) NOT NULL,
  --     created       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  --     PRIMARY KEY (chunk_id, model_version)
  -- );
  ```
  The comment block goes in this spec, not in the migration SQL. The migration
  file creates only the chunks table.

## Save-time integration

`ChunkProjector` is registered the same way `GraphProjector` is — via the
wiki's `FilterManager` bootstrap wiring in `WikiEngine`. Look for the
`filterManager.addPageFilter( new GraphProjector(...) )` call and add a
parallel registration.

Pseudocode for `ChunkProjector.postSave`:

```java
try {
    ParsedPage parsed = FrontmatterParser.parse(context.getPage().getContent());
    List<Chunk> produced = chunker.chunk(parsed);
    List<StoredChunk> existing = repository.findByPage(pageName);
    ChunkDiff diff = ChunkDiff.compute(existing, produced);
    repository.apply(diff);
    LOG.info("Chunked page {} into {} chunks (delta: +{} ~{} -{})",
             pageName, produced.size(), diff.added, diff.changed, diff.removed);
} catch (Exception e) {
    LOG.warn("Chunking failed for page {}: {}", pageName, e.getMessage(), e);
}
```

`ChunkDiff.compute`:
- Match existing and produced chunks by `chunk_index`.
- If both exist and `content_hash` matches → no-op.
- If both exist and hash differs → UPDATE (keep `id`, bump `modified`).
- If produced has a new index → INSERT.
- If existing has an index not in produced → DELETE.

This is a bounded-size operation (chunks per page max out in the hundreds even
for our longest content), so no batching is needed.

## Combined rebuild orchestration

A single async orchestrator — `ContentIndexRebuildService` — owns the
bulk-rebuild workflow for both the Lucene index and `kg_content_chunks`.
Operators see one button, one progress display, one completion signal.

### Why combined

Algorithm changes that motivate a rebuild almost always touch both layers:
tuning field boosts changes Lucene ranking, changing the chunker changes
what gets embedded later, and tweaking frontmatter extraction changes
both. Forcing operators to trigger two separate rebuilds — and to keep
them in sync — is the failure mode we're designing out.

### State machine

```
IDLE ──trigger──▶ STARTING ──▶ RUNNING ──▶ DRAINING_LUCENE ──▶ IDLE
                                  │                                ▲
                                  └──── error/cancel ──────────────┘
```

- **IDLE**: no rebuild in flight. Status endpoint returns live stats only.
- **STARTING**: transient, while we clear Lucene's directory and the
  `kg_content_chunks` table in one transaction. Brief.
- **RUNNING**: orchestrator daemon thread iterates **all pages** from
  `PageManager.getAllPages()` — matching the old `/reindex` endpoint's
  page set exactly. Per page:
    - If the page is not a system page (`SystemPageRegistry`), run the
      chunker synchronously and persist its rows.
    - Unconditionally call `LuceneSearchProvider.reindexPage(page)` to
      enqueue on the `LuceneUpdater` queue. The prep-step system-page
      filter inside `LuceneSearchProvider` decides whether to actually
      index — system pages become no-ops at that layer, not here. This
      keeps the orchestrator simple and ensures the page-set decision
      lives in one place (Lucene) rather than being duplicated.
- **DRAINING_LUCENE**: orchestrator has finished iterating pages. Chunks
  are 100% complete; we wait until `LuceneUpdater.getReindexQueueDepth()`
  drops to zero before transitioning to IDLE. During draining, the UI
  reports "Chunking complete; Lucene indexing in progress (N remaining)."
- On error: individual page failures are recorded in `errors[]` but do not
  stop the run (same policy as `backfill-frontmatter`). A fatal error
  (e.g. table-clear fails) transitions directly back to IDLE with the
  error captured.

### Concurrency and restart

- Only one rebuild in flight at a time. A trigger request while `RUNNING`
  or `DRAINING_LUCENE` returns **409 Conflict** with the current snapshot.
- The orchestrator is a singleton registered in `WikiEngine`.
- Tomcat restart during a rebuild clears the in-memory state. Operator
  re-triggers. Partial chunks written before the crash stay in the table
  and are overwritten when the new run reaches those pages. Lucene is
  idempotent on per-page reindex.

### Failure isolation

- Per-page exception → caught, logged at `WARN`, added to `errors[]`,
  loop continues.
- Chunker throws → Lucene enqueue still attempted (don't let a chunker
  bug break search indexing).
- Lucene enqueue throws → next page still processed.
- Both throw for a single page → two error entries.

### Properties and configuration

```
wikantik.rebuild.enabled = true         # kill-switch; 503 if false
wikantik.rebuild.lucene_drain_poll_ms = 2000
```

Unchanged: the existing `PROP_LUCENE_INDEXDELAY` governs the background
updater's cadence. The drain phase polls `getReindexQueueDepth()` at
`lucene_drain_poll_ms` and exits when depth is zero for two consecutive
polls (guards against a brief mid-drain emptiness).

## Admin REST surface

All under `AdminContentResource`, protected by existing `AdminAuthFilter`
(requires `AllPermission`). No per-endpoint additional authorization.

### `GET /admin/content/index-status`

Returns a unified snapshot for the UI. Always cheap (no table scans over
500 KB text); aggregate counts come from `COUNT(*)` + a couple of
pre-computed summaries.

```json
{
  "pages": {
    "total": 2143,
    "system": 47,
    "indexable": 2096
  },
  "lucene": {
    "documents_indexed": 2096,
    "queue_depth": 0,
    "last_update": "2026-04-17T14:02:11Z"
  },
  "chunks": {
    "pages_with_chunks": 2096,
    "pages_missing_chunks": 0,
    "total_chunks": 18432,
    "avg_tokens": 287,
    "min_tokens": 42,
    "max_tokens": 512
  },
  "rebuild": {
    "state": "IDLE",
    "started_at": null,
    "pages_total": 0,
    "pages_iterated": 0,
    "pages_chunked": 0,
    "system_pages_skipped": 0,
    "lucene_queued": 0,
    "chunks_written": 0,
    "errors": []
  }
}
```

**Field notes for the `rebuild` block during RUNNING:**
- `pages_total` = count of `PageManager.getAllPages()` at start (matches
  old `/reindex`'s `pagesQueued`).
- `pages_iterated` = how many pages the orchestrator has visited.
- `pages_chunked` = how many of those were non-system and ran through
  the chunker.
- `system_pages_skipped` = `pages_iterated - pages_chunked` during and
  after the run, so operators can see the full scope (parity with old
  `/reindex`).
- `lucene_queued` = how many `reindexPage` calls the orchestrator has
  made (Lucene's own filter decides what actually gets indexed).
- `chunks_written` = cumulative count of chunk rows inserted/updated
  this run.

Polled by the UI at 2000 ms cadence.

### `POST /admin/content/rebuild-indexes`

Triggers the combined rebuild. Request body is empty (no options in v1).

- 202 `{ state: "STARTING", started_at: "..." }` on successful trigger.
- 409 + current snapshot if a rebuild is already in flight.
- 503 if `wikantik.rebuild.enabled` is false.

### `POST /admin/content/reindex` (existing, deprecated)

Kept for backwards compatibility with operator scripts that may call it.
Flagged as deprecated in the Swagger/OpenAPI description and in the
response headers (`Deprecation: true`). Removed in a later spec once we
confirm no callers.

Semantic differences from the new `rebuild-indexes`, post-prep-step:

| Aspect                | `/reindex` (deprecated)     | `/rebuild-indexes` (new)     |
|-----------------------|------------------------------|------------------------------|
| Page set              | non-system (post-prep-step)  | non-system                    |
| Clears Lucene first   | no                           | yes                           |
| Produces chunks       | no                           | yes                           |
| Progress reporting    | no                           | yes (polled status endpoint)  |
| Search degraded window| no                           | yes (minutes, for 2k pages)   |
| Concurrency guard     | no                           | yes (409 on double-trigger)   |

The two endpoints coexist indefinitely until an explicit removal spec.

## Admin UI

A new tab on the existing `AdminContentPage.jsx` named **Index Status**.
It becomes the primary surface for both stats and the rebuild action; the
existing standalone reindex button on the main page is removed.

### Layout (top to bottom)

1. **Stat cards row** — four cards: Pages Indexable, Lucene Documents,
   Total Chunks, Lucene Queue Depth. Each shows the number plus a muted
   subtitle (e.g. "of 2143 total", "avg 287 tokens/chunk").
2. **Rebuild section** — a "Rebuild Indexes" button that is:
    - **Enabled** when `rebuild.state === "IDLE"`.
    - **Disabled with spinner** when `state in {STARTING, RUNNING, DRAINING_LUCENE}`.
    - Clicking opens a confirmation dialog: "This will clear the Lucene
       index and chunk table and rebuild from all 2,096 pages. Search
       will be degraded until it completes. Continue?"
    - Below the button: current state label, primary progress bar
       (`pages_iterated / pages_total`), a subtitle showing
       `pages_chunked` and `system_pages_skipped` so operators see the
       full scope, and — during DRAINING — a secondary bar showing the
       Lucene queue drain.
3. **Errors panel** — collapsible, shows the last 20 entries from
   `rebuild.errors`. Each entry: page name, error message, timestamp.
   Scrollable if more.
4. **Last successful rebuild** — date/time and duration, pulled from the
   orchestrator's history (single-slot, in-memory).

### Polling behaviour

- Tab mounts → fetch once, then poll every 2000 ms.
- Tab unmounts → cancel poll.
- When `state === "IDLE"` and no rebuild has run this session, drop poll
  cadence to 10000 ms (stats don't change that fast).
- When `state` transitions from non-IDLE to IDLE, show a green toast
  "Rebuild complete: N pages, M chunks, K errors" and revert to slow poll.

### API client

New methods on `wikantik-frontend/src/api/client.js`:
- `api.admin.getIndexStatus()`
- `api.admin.rebuildIndexes()`

### No separate rebuild page

We do not introduce `/admin/index` as a standalone route. The status tab
lives inside `AdminContentPage` so that content-focused operators have
everything in one place.

## Observability

Metrics exposed via `wikantik-observability` (Prometheus):

**Save-time chunker:**
- `wikantik_chunker_chunks_produced` (counter)
- `wikantik_chunker_duration_seconds` (histogram)
- `wikantik_chunker_failures_total` (counter, labels: `reason`)
- `wikantik_chunker_chunk_size_tokens` (histogram, sampled at emission time)

**Rebuild orchestrator:**
- `wikantik_rebuild_state` (gauge, 0=IDLE, 1=STARTING, 2=RUNNING,
  3=DRAINING_LUCENE)
- `wikantik_rebuild_runs_total` (counter, labels: `outcome` =
  `completed` | `failed`)
- `wikantik_rebuild_duration_seconds` (histogram, observed on completion)
- `wikantik_rebuild_pages_iterated` (gauge, current run)
- `wikantik_rebuild_pages_chunked` (gauge, current run)
- `wikantik_rebuild_system_pages_skipped` (gauge, current run)
- `wikantik_rebuild_errors` (gauge, count in the current run's errors[])

Logs at `INFO` on successful save-time chunking, rebuild state transitions,
and run completion; `WARN` on per-page failures; `ERROR` on fatal rebuild
errors that transition directly back to IDLE. All logs include page name or
run ID as appropriate.

## Configuration

Added to `wikantik.properties` defaults, overridable in
`wikantik-custom.properties`:

```
wikantik.chunker.target_tokens = 300
wikantik.chunker.max_tokens    = 512
wikantik.chunker.min_tokens    = 80
wikantik.chunker.enabled       = true
```

Plus the rebuild properties already defined in the Rebuild section:

```
wikantik.rebuild.enabled              = true
wikantik.rebuild.lucene_drain_poll_ms = 2000
```

Flag semantics — the two `enabled` flags are orthogonal but cooperate:
- `wikantik.chunker.enabled=false` disables the save-time `ChunkProjector`
  **and** causes the rebuild orchestrator to skip the chunking step
  per-page (Lucene reindex still runs). Effectively "freeze the chunks
  table."
- `wikantik.rebuild.enabled=false` disables the rebuild trigger endpoint
  (503) independently of chunker state. Save-time chunking is unaffected.

## Testing plan

Per `CLAUDE.md` project preference: TDD, tests-before-fix. Three test classes:

### `ContentChunkerTest` (unit, no database)

- Empty body → zero chunks.
- Single paragraph shorter than `min` → one chunk (the `min` threshold is a
  merge-forward policy, not a drop policy).
- Three H2 sections, each short → three chunks, each with `heading_path`
  = `["<h1>", "<section-title>"]`.
- One oversized paragraph (~800 tokens) → split on sentence boundaries, each
  resulting chunk below `max`.
- Fenced code block larger than `max` → single chunk containing the whole
  block (policy exception documented).
- Mixed content: heading, short paragraph, code fence, heading, long paragraph
  → chunk count and boundaries exactly match a hand-computed fixture.
- Plugin markup `[{Plugin}]` preserved verbatim.
- `content_hash` is deterministic for identical input.
- `content_hash` changes when `heading_path` changes even if `text` is
  identical (guards against false cache hits across sections).

### `ChunkProjectorTest` (integration, in-memory wiki)

Reuses `InMemoryPageSaveHelper` (seen in
`wikantik-main/src/test/java/com/wikantik/knowledge/test/`) to drive full
save-time flow.

- Save new page → chunks written, count matches chunker output.
- Re-save unchanged page → zero writes (diff is all no-op).
- Edit one paragraph → only affected chunks are updated; unaffected chunks
  keep their `id`.
- Delete a section → removed chunks DELETED; others unchanged.
- Chunker throws → page save still succeeds; `WARN` log emitted; no chunk
  row written for the failing page.
- `wikantik.chunker.enabled = false` → no rows written on save.

### `ContentChunkRepositoryTest` (JDBC, H2)

- `findByPage`, `apply(diff)` round-trip correctness against real SQL.
- `UNIQUE (page_name, chunk_index)` enforced.
- Migration applies and re-applies cleanly (no-op on second run).

### `ContentIndexRebuildServiceTest` (integration, in-memory wiki)

- Initial state is `IDLE` with zero progress fields.
- Trigger: state transitions `IDLE → STARTING → RUNNING`; started_at set.
- Trigger while RUNNING: returns 409 equivalent (service-level signal);
  in-flight state not disturbed.
- System-page handling: a fixture with 3 regular + 2 system pages runs
  to completion with `pages_iterated=5`, `pages_chunked=3`,
  `system_pages_skipped=2`, `lucene_queued=5`. No chunk rows for system
  pages. Covers the superset guarantee over the old `/reindex` page set.
- Page with chunker exception: recorded in `errors[]`, loop continues,
  subsequent page still processed.
- Page with Lucene enqueue exception (simulated): chunker result still
  persisted, error recorded, loop continues.
- Run completes all pages → state transitions `RUNNING → DRAINING_LUCENE`.
- Queue depth drops to zero → state transitions `DRAINING_LUCENE → IDLE`
  after two consecutive zero polls (not one).
- `wikantik.rebuild.enabled=false` → trigger refused.
- Tomcat restart simulation (recreate service) → state starts fresh at
  IDLE; partially-written chunks for pages processed before restart
  remain in the table.

### API/UI contract test

Thin test that exercises `GET /admin/content/index-status` against a
test DB to pin the JSON shape the frontend depends on. Adding or
renaming a field requires updating this test, which forces
frontend/backend alignment.

## Risks and decisions to revisit

1. **Char-based token estimate will under- or over-count vs. the real tokenizer.**
   Acceptable for a pre-embedding slice. Once `bge-m3` (or whatever we pick)
   lands, we add a second column `token_count_<model>` and reconcile. Chunks
   themselves do not need to be re-split unless estimates were catastrophically
   wrong — we will check this with a one-shot analysis script at that time.
2. **Oversized code fences produce oversized chunks.** If real retrieval
   evaluation shows this hurts recall, the fix is to split code on blank-line
   boundaries. Not worth pre-optimizing.
3. **Diff by `chunk_index` + `content_hash` is not tolerant of content motion.**
   If a user reorders two sections, all downstream chunks shift index, and
   every subsequent chunk becomes an UPDATE. Their embeddings (when added)
   survive because update is in-place, but the `modified` timestamps churn.
   An index-oblivious diff (match purely by hash) is more robust but changes
   the "stable identity" contract. Revisit if churn becomes a real problem.
4. **No feedback loop with `GraphProjector`.** If a page is excluded from the
   graph (system page), it is currently NOT excluded from chunking — we chunk
   every non-system page independently. This is deliberate: future retrieval
   may want to index help content and UI templates. If it turns out we do
   not, add an `exclude-from-chunking` frontmatter flag (trivial).
5. **Tests use H2; production uses PostgreSQL with pgvector.** V008 does not
   depend on pgvector (no VECTOR columns), so H2 can run it directly. The
   future embeddings migration will need an H2-compatible variant or a
   Postgres-only integration test.
6. **Rebuild clears Lucene + chunks before repopulating.** During RUNNING
   and DRAINING_LUCENE, search quality is degraded — partial index,
   partial chunks. For a 2,000-page corpus this window is on the order of
   minutes. The rebuild is an *infrequent* operation (algorithm changes,
   not routine), so accepting the degraded window is the right tradeoff.
   A blue/green rebuild (write into a shadow index, swap atomically) is
   possible for Lucene but adds significant complexity; deferred unless a
   real operator need shows up.
7. **Chunks table is cleared in one transaction.** For a 2,000-page corpus
   with ~18k chunks, a single `DELETE FROM kg_content_chunks` is fine. At
   100x that size it becomes a long-running transaction; we'd switch to
   `TRUNCATE` or batched deletes. Not a v1 concern.
8. **No cancellation.** Once triggered, a rebuild runs to completion (or
   to a fatal error). Adding cancel is easy (a `volatile boolean cancel`
   the loop checks each iteration) but not in scope; revisit if operators
   ask.
9. **Polling is per-operator.** Two admins on the status tab at the same
   time double the poll traffic. Still trivial for a small team; if it
   grows, move to SSE.
10. **Stats query cost.** `COUNT(*)` + aggregates over `kg_content_chunks`
    is fine at 18k rows, fine at 200k. If the chunk count climbs into
    millions, we precompute and cache the stats. Not a v1 concern, called
    out so it doesn't become a surprise.

## Forward compatibility — what this unlocks

Once V008 is applied and the table is populated:

- **Follow-on A:** embedding worker. Create `kg_chunk_embeddings`, add a
  background job that scans for chunks lacking embeddings at the current
  `model_version`, batches them, calls Ollama `/api/embed` with `bge-m3`,
  writes vectors. Reuses the same orchestrator pattern; likely adds a
  third phase to the rebuild state machine (`EMBEDDING`) that can run
  after `DRAINING_LUCENE`, or runs as its own independent worker that
  the status endpoint surfaces alongside. Design decision deferred to
  that spec.
- **Follow-on B:** retrieval core (`KnowledgeRetrievalService`). Reads
  `kg_content_chunks` + embeddings, does hybrid BM25+vector merge, filters
  via `PermissionFilter`. Separable from this spec and from A.
- **Follow-on C:** OpenWebUI pipeline. Depends on B.
- **Prep step (separate, small):** extract `PermissionFilter` from
  `RestServletBase.checkPagePermission` and `SearchPermissionTest` patterns.
  Does not depend on this spec; can ship in parallel.
- **Prep step (separate, small):** expose
  `LuceneSearchProvider.getReindexQueueDepth()` via the existing health
  metrics. Useful independent of this spec; the index-status endpoint
  will consume it either way.

None of the follow-ons require schema changes to `kg_content_chunks`.

## Resolved decisions (from 2026-04-16 review)

- **`min_tokens = 80` merge-forward, carrying the first section's
  heading_path.** Approved.
- **`ChunkProjector` runs after `GraphProjector`.** Approved.
- **Backfill is async** and combined with Lucene reindex into a single
  rebuild operation driven by `ContentIndexRebuildService`. Production
  corpus is already >2,000 pages and growing rapidly.

## Open questions for review

- **Rebuild removes the standalone `POST /admin/content/reindex` button
  from the UI** (keeping the endpoint deprecated for script
  compatibility). Alternative: keep both buttons during a transition
  period. Draft: remove immediately, one surface for operators.
- **Drain completion heuristic: two consecutive zero-depth polls at 2 s
  apart.** Guards against the queue briefly emptying between in-flight
  pages. Could be strengthened (three polls, longer interval) if we see
  false "complete" signals in practice.
- **Stat card thresholds.** Should the UI flag `pages_missing_chunks > 0`
  as a warning state (yellow card) or show it neutrally? Draft: neutral
  unless value exceeds 5% of total, at which point yellow. Easy to
  redirect.
- **Error panel retention.** Draft keeps the last 20 errors from the
  most recent completed run, cleared when the next run starts. Alternative:
  persist across runs in a small in-memory ring buffer (say, last 100
  across all runs in this JVM). Worth it only if operators ask.
