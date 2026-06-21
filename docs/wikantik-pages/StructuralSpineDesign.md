---
type: design
status: active
cluster: wikantik-development
date: '2026-04-24'
title: Structural Spine Design
tags:
- design
- agent-context
- mcp
- structural-index
- canonical-id
- typed-relations
summary: Detailed design for a machine-queryable structural index (clusters, tags,
  canonical IDs, typed cross-references) exposed via /api/structure/* and matching
  MCP tools, with a generated Main.md and stable page identity across renames. Addresses
  the "structural blindness" problem that forces agents to rediscover wiki shape via
  full-text search.
author: claude-opus
related:
- AgentGradeContentDesign
- HybridRetrieval
- GoodMcpDesign
- WikantikDevelopment
canonical_id: 01KQ0P60Q4PP4BXR1WQKBPV1WS
---

> **Note (2026-05-02).** The Structural Spine is now a sub-area of the
> **Page Graph** subsystem. The typed-relation grammar (`relations:`
> frontmatter, `related_to`, `part_of`, etc.) was removed in this
> update — see `docs/superpowers/specs/2026-05-02-page-graph-vs-knowledge-graph-design.md`.
> The spine retains its `canonical_id` assignment and validation, the
> `cluster:` hub-membership mechanism, save-time enforcement, and the
> `Main.md` projection. Page Graph edges are now strictly real wiki
> links.

# Structural Spine Design

> 🌐 **Product overview:** [The structural spine on wikantik.com](https://www.wikantik.com/platform/structural-spine.html) — a plain-language walkthrough for readers and AI agents.


## Problem

Wikantik exposes 1000+ pages through two MCP servers (`/wikantik-admin-mcp`, `/knowledge-mcp`), 23 REST resources, and full-text search. None of them let an agent ask **"what does this wiki contain?"** without first paying the cost of full-text search. The taxonomy that exists — cluster membership, tag assignments, type declarations, page-to-page relationships — is encoded in human-readable places (YAML frontmatter, `Main.md` prose) but never exposed as structured data that agents can query.

Concrete consequences today:

- An agent asked *"what does this wiki know about retrieval?"* must search BM25 for "retrieval", hope the top hit is a hub page, then parse prose to find sub-articles.
- An agent asked *"show me every hub page"* has no endpoint to call. It must enumerate `/api/pages` and re-parse frontmatter client-side.
- Renaming a page (via `RenamePageTool`) silently invalidates every external reference — no stable identity survives the rename.
- `Main.md` is hand-curated prose that encodes the cluster taxonomy. It drifts from reality silently; every edit to frontmatter risks desync.

This design introduces a structural spine — a small set of first-class APIs, a persistent `canonical_id`, and cluster-hub membership — so agents can navigate the wiki by shape, not by keyword.

## Goals

1. Expose the wiki's taxonomy (clusters, tags, types, hubs) as queryable data, not prose.
2. Give every page a `canonical_id` that survives renames.
3. Replace hand-curated `Main.md` with generation from the structural index.
4. Make the structural index available via both REST (`/api/structure/*`) and MCP (`/knowledge-mcp`), so MCP-native and REST-native agents share one surface.
5. Fail-closed: if the structural index is unavailable, full-text search remains the fallback (same pattern as BM25 fallback in `HybridRetrieval`).

## Non-goals

- Replacing Lucene full-text search (this complements it).
- Replacing the Knowledge Graph (`KnowledgeGraphService`) — that service models LLM-extracted entities across chunks; the structural spine models pages, their cluster membership, and their rename-stable identity.
- Authoring a new markup dialect. `canonical_id` and `cluster:` live in YAML frontmatter, not inline Markdown.
- Typed cross-references between pages (removed 2026-05-02 — see note at top).
- Changing REST response envelopes for existing endpoints.

## Data model

### Frontmatter additions

Every page gains one new frontmatter field.

```yaml
---
title: Hybrid Retrieval
type: article
cluster: wikantik-development
tags: [retrieval, bm25, embeddings]
canonical_id: 01H8G3Z1K6Q5W7P9X2V4R0T8MN
---
```

- **`canonical_id`** — a 26-character [ULID](https://github.com/ulid/spec). Sort-friendly and URL-safe, fits the existing frontmatter parser, no UUID dashes. Immutable once assigned. Regenerating is a manual admin operation with a paper trail.

The `canonical_id` field is optional during the bake-in period (Phase 1 below); it becomes required after the backfill migration lands.

> **Removed 2026-05-02.** The `relations:` frontmatter field and its typed-relation vocabulary (`part-of`, `example-of`, `prerequisite-for`, `supersedes`, `contradicts`, `implements`, `derived-from`) were removed. Page Graph edges are now strictly real wikilinks. If curated typed edges between concepts are needed in the future, they belong in the Knowledge Graph as admin-approved edges, not in page frontmatter.

### Structural index projection

An in-memory projection of structural data, maintained by a new `StructuralIndexService`:

```
ClusterIndex  : Map<ClusterName,  ClusterEntry{hubPageId, articleIds, updatedAt}>
TagIndex      : Map<TagName,      TagEntry{pageIds, count}>
TypeIndex     : Map<PageType,     Set<PageId>>
CanonicalIndex: Map<CanonicalId,  PageDescriptor{slug, title, type, cluster, tags, summary, updated}>
SlugIndex     : Map<Slug,         CanonicalId>     // for name→id resolution
```

The projection is **derivable** — authoritative state lives in frontmatter on disk + the pages table in Postgres. The index is a cache of derivations. Rebuild on bootstrap is mandatory; incremental maintenance on `PAGE_SAVE` / `PAGE_RENAME` / `PAGE_DELETE` events is the fast path.

### Database schema

Two tables track canonical-id stability across renames, used as a durable backstop when the in-memory projection is being rebuilt or absent.

**Migration `V013__canonical_ids_and_relations.sql`** (idempotent):

```sql
CREATE TABLE IF NOT EXISTS page_canonical_ids (
    canonical_id   CHAR(26)   PRIMARY KEY,
    current_slug   VARCHAR(512) NOT NULL UNIQUE,
    title          VARCHAR(512) NOT NULL,
    type           VARCHAR(32)  NOT NULL,         -- hub | article | reference | runbook
    cluster        VARCHAR(128),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS page_slug_history (
    canonical_id   CHAR(26)   NOT NULL REFERENCES page_canonical_ids(canonical_id) ON DELETE CASCADE,
    previous_slug  VARCHAR(512) NOT NULL,
    renamed_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (canonical_id, previous_slug)
);

CREATE INDEX IF NOT EXISTS ix_canonical_ids_type ON page_canonical_ids(type);
CREATE INDEX IF NOT EXISTS ix_canonical_ids_cluster ON page_canonical_ids(cluster);

GRANT SELECT, INSERT, UPDATE, DELETE ON page_canonical_ids, page_slug_history TO :app_user;
```

> **Removed 2026-05-02.** The `page_relations` table (and its indexes) was dropped when typed relations were removed. Page Graph edges are now derived at query time from the live `ReferenceManager` data.

Frontmatter on disk remains the single source of truth — the DB is derived and can be rebuilt from page sources.

## Service design

### `StructuralIndexService`

Lives in `wikantik-main` (co-located with `ContextRetrievalService` and `KnowledgeGraphService`; shares the same JDBC datasource and event bus). API:

```java
package com.wikantik.api.pagegraph;

public interface StructuralIndexService {

    /* ------------------------------------------------- Clusters / tags / types */
    List<ClusterSummary>        listClusters();
    ClusterDetails              getCluster(String name);
    List<TagSummary>            listTags(int minPages);
    List<PageDescriptor>        listPagesByType(PageType type);
    List<PageDescriptor>        listPagesByFilter(StructuralFilter filter);
    Sitemap                     sitemap();

    /* ------------------------------------------------- Canonical identity    */
    Optional<PageDescriptor>    getByCanonicalId(String canonicalId);
    Optional<String>            resolveSlugAtTimestamp(String canonicalId, Instant at);
    Optional<String>            resolveCanonicalIdFromSlug(String slug);     // current-slug only

    /* ------------------------------------------------- Lifecycle             */
    void                        rebuild();                                    // full scan from frontmatter
    IndexHealth                 health();                                     // up-to-date, lag, last-rebuild
}
```

> **Removed 2026-05-02.** The `outgoingRelations`, `incomingRelations`, and `traverse(TraversalSpec)` methods were dropped with the typed-relation grammar. Page Graph traversal (wikilinks) uses `ReferenceManager` directly.

### Event flow

`StructuralIndexService` subscribes to the existing `WikiEventSubscriptionBridge` (already used by the admin MCP server for resource subscriptions — `McpServerInitializer` line 148 is the wiring pattern to mirror).

```
PAGE_SAVE(slug, previousSlug?, newFrontmatter)
  → parse frontmatter (canonical_id, type, cluster, tags)
  → if canonical_id missing → auto-assign ULID and inject into frontmatter
  → if canonical_id is new → INSERT page_canonical_ids
  → if canonical_id exists and slug differs → INSERT page_slug_history(prev_slug), UPDATE current_slug
  → invalidate in-memory cluster/tag/type indexes for affected entries
  → publish StructuralIndexUpdated event (MCP resource subscribers re-read)

PAGE_RENAME(oldSlug, newSlug)
  → PAGE_SAVE already handles slug transition via canonical_id stability; rename is a degenerate case

PAGE_DELETE(slug)
  → DELETE page_canonical_ids WHERE current_slug=slug (CASCADE clears history)
  → invalidate in-memory indexes
```

### Rebuild strategy

On every WAR startup, `StructuralIndexService.rebuild()` runs in a background executor:

1. Stream all pages via `PageManager.getAllPages()`.
2. Parse frontmatter (`FrontmatterParser`).
3. Upsert `page_canonical_ids`. If a page lacks `canonical_id`, synthesise one and surface the page in an `/admin/unclaimed-canonical-ids` view — do **not** auto-rewrite the source file (rewrites must go through a migration tool, see Phase 1 below).
4. Reconcile `page_relations` against frontmatter.
5. Log duration. Fail-closed: structural endpoints return 503 `{ "status": "rebuilding" }` until bootstrap completes, and MCP tools return a clear `rebuilding` error with an ETA hint.

Bootstrap SLA target: full rebuild of 2000 pages in < 5 s on the production box. Measured end-to-end, emitted as `wikantik_structural_index_rebuild_duration_seconds` (Prometheus).

## REST API

All endpoints live under `/api/structure/*` and use the existing REST envelope convention (`{data: …}` or `{error: …}`, ACL-checked via `RestServletBase`).

### `GET /api/structure/clusters`

```json
{
  "data": {
    "clusters": [
      {
        "name": "wikantik-development",
        "hub_page": {
          "canonical_id": "01H8G3Z1K6Q5W7P9X2V4R0T8MN",
          "slug": "WikantikDevelopment",
          "title": "Wikantik Development"
        },
        "article_count": 34,
        "article_ids": ["01H8G3Z…", "01H8G3Z…", …],
        "updated_at": "2026-04-24T13:45:11Z"
      },
      …
    ],
    "generated_at": "2026-04-24T13:50:00Z"
  }
}
```

### `GET /api/structure/clusters/{name}`

Full cluster detail: hub page summary, ordered article list with summaries, tag distribution within the cluster.

### `GET /api/structure/tags?min_pages=3`

Tag dictionary with counts and top-N representative pages per tag.

### `GET /api/structure/pages?type=hub&cluster=X&tag=Y&updated_since=ISO&limit=100&cursor=…`

Filtered page listing. Server-side paginated; cursor is opaque (encoded `(updated_at, canonical_id)`).

### `GET /api/structure/sitemap?shape=compact`

Compact machine-readable sitemap:

```json
{
  "data": {
    "pages": [
      {
        "id":      "01H8G3Z1K6Q5W7P9X2V4R0T8MN",
        "slug":    "HybridRetrieval",
        "title":   "Hybrid Retrieval",
        "type":    "article",
        "cluster": "wikantik-development",
        "tags":    ["retrieval","bm25","embeddings"],
        "summary": "Operator reference for Wikantik's BM25 + dense hybrid retrieval …",
        "updated": "2026-04-22T18:02:10Z",
        "url":     "https://wiki.jakefear.com/wiki/HybridRetrieval"
      },
      …
    ],
    "count":         2034,
    "generated_at": "2026-04-24T13:50:00Z"
  }
}
```

This is the ideal "agent prelude" payload — small enough to prime a cold agent with a map of the whole wiki (≈ 200 bytes × 2000 pages ≈ 400 KB uncompressed; well under one tool call's budget with gzip).

### `GET /api/pages/by-id/{canonical_id}`

Resolves a canonical ID to the current page. Same response shape as `GET /api/pages/{slug}`.

> **Removed 2026-05-02.** `GET /api/pages/{canonical_id}/relations` was dropped with typed relations. For Page Graph traversal (wikilinks), use `GET /api/pages/{slug}/backlinks` or the `get_backlinks` / `get_outbound_links` MCP tools on `/wikantik-admin-mcp`.

## MCP surface (added to `/knowledge-mcp`)

`wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/` gains five tools. All tool descriptions follow the lean-but-concrete convention already established in the knowledge MCP: one-sentence purpose, explicit input shape, explicit output shape, and at least one example payload in the JSON schema (per the tool-description upgrade specified in `AgentGradeContentDesign.md`).

| Tool | Description |
|------|-------------|
| `list_clusters` | Return all clusters with hub page, article count, and last-updated timestamp. No inputs. |
| `list_tags` | Return tag dictionary. Input: `{min_pages?: int}`. Output: `[{tag, count, top_pages: [canonical_id]}]`. |
| `list_pages_by_filter` | Filtered page listing. Inputs: `type?`, `cluster?`, `tag?`, `updated_since?`, `limit?`, `cursor?`. Output: `{pages: [PageSummary], next_cursor?}`. |
| `get_page_by_id` | Resolve a canonical_id to the current page, including latest slug + frontmatter + rendered body. |

All four tools land in `wikantik-knowledge` (the read-only server) — structural queries are retrieval-adjacent, not admin operations. The `McpToolRegistry` in `wikantik-knowledge/…/mcp/KnowledgeMcpInitializer.java` gets four `registerTool(...)` calls; follow the existing `SearchKnowledgeTool` wiring pattern.

> **Removed 2026-05-02.** `traverse_relations` was dropped with typed relations. For wikilink traversal use `get_backlinks` / `get_outbound_links` on `/wikantik-admin-mcp`.

### MCP resources

Three new resource templates:

- `wiki://structure/clusters` — snapshot of the cluster list (cacheable, `Last-Modified` based on latest `PAGE_SAVE`)
- `wiki://structure/tags` — snapshot of the tag dictionary
- `wiki://structure/sitemap` — compact sitemap (as above)

These mirror the existing `wiki://pages` / `wiki://recent-changes` resources, giving subscription-aware MCP clients automatic updates through the existing `WikiEventSubscriptionBridge`.

## `Main.md` generation

Today `Main.md` is hand-curated prose. Post-structural-spine, it is generated.

- **Template:** `wikantik-wikipages/src/main/templates/Main.md.mustache` (or equivalent). Static boilerplate + a `{{#clusters}} … {{/clusters}}` loop.
- **Generator:** `bin/generate-main-page.sh` calls `GET /api/structure/clusters` against a freshly-built WAR (or, offline, reads frontmatter directly via a CLI in `wikantik-extract-cli`) and renders the template.
- **Build-time integration:** the WAR build runs the generator during `wikantik-wikipages-builder`'s `package` phase, so `Main.md` always ships in sync with the frontmatter it summarises.
- **Editorial override:** curators can still pin specific pages ("featured" section) via a small `docs/wikantik-pages/Main.pins.yaml` file — the template reads both the structural output and the pins.

No hand edits to `Main.md` after generation lands. `apache-rat` / pre-commit refuses commits that modify `Main.md` directly.

## Migration path

### Phase 1 — Canonical IDs (one sprint)

1. Add `canonical_id` to `FrontmatterParser` schema (optional initially).
2. CLI tool in `wikantik-extract-cli`: `wikantik-extract-cli assign-canonical-ids --dry-run | --write`. Walks `docs/wikantik-pages/`, assigns a fresh ULID to each page missing one, rewrites frontmatter in-place (single commit, one file). Idempotent.
3. Migration `V013` creates the three tables.
4. `StructuralIndexService` runs in **observe-only** mode: builds the projection, exposes it at `/api/structure/*`, but does not yet require canonical_ids on every save.
5. Validator warns on `PAGE_SAVE` for pages without `canonical_id`; surfaces in `/admin/unclaimed-canonical-ids`.

Exit criterion: 100 % of pages in `docs/wikantik-pages/` have `canonical_id`.

> **Removed 2026-05-02.** Phase 2 (typed relations) was cancelled. The `relations:` frontmatter grammar and `ProposeRelationsTool` were not implemented and will not be. See the note at the top of this document.

### Phase 2 (renumbered) — `Main.md` generation (half sprint)

1. Template + generator + pre-commit guard.
2. Delete the old `Main.md`; commit the generated one.
3. Add a regression integration test: regenerate on every build, fail the build if the checked-in `Main.md` diverges.

### Phase 3 (renumbered) — Enforcement (quarter sprint)

1. Make `canonical_id` **required** in the frontmatter validator (reject `PAGE_SAVE` without it; auto-assign and inject if absent).
2. Flip `StructuralIndexService` from observe-only to authoritative for structural queries.

## Failure modes and fail-closed behaviour

| Failure | Detection | Response |
|---------|-----------|----------|
| Projection out-of-date | `health()` reports `lag_seconds > 60` | `/api/structure/*` returns `X-Index-Staleness: <n>`; MCP tools include `stale_by_seconds` in every response. No 5xx. |
| Projection empty (not yet rebuilt) | `health().status = rebuilding` | Endpoints return HTTP 503 with `Retry-After`. MCP tools return structured `rebuilding` error with ETA. |
| Duplicate canonical_id detected | On save, unique constraint fires | Reject save with actionable error: *"canonical_id X already used by page Y. Pick a different ID or delete Y."* |
| Two-way dependency conflict | Background check | Surface both pages in `/admin/page-graph/conflicts`. |
| DB outage | `StructuralIndexService` falls back to in-memory projection | Reads continue to serve; writes that require DB persistence return 503 `{"fallback": "memory", "writes_deferred": true}` and replay when DB returns. |

The overarching principle matches `HybridRetrieval`'s fail-closed rule: **structural queries degrade, they do not lie.** Callers get stale data with an explicit staleness marker, or a clear unavailable error — never silently wrong data.

## Observability

New Prometheus metrics (all gauges + counters exposed via the existing `MeterRegistryHolder` in `wikantik-api/src/main/java/com/wikantik/api/observability/`):

| Metric | Type | Purpose |
|--------|------|---------|
| `wikantik_structural_index_pages_total` | gauge | Count of pages tracked in the projection |
| `wikantik_structural_index_lag_seconds` | gauge | Seconds since last event processed |
| `wikantik_structural_index_rebuild_duration_seconds` | histogram | Distribution of full-rebuild times |
| `wikantik_structural_api_requests_total{endpoint,status}` | counter | Per-endpoint request volume |

Existing admin dashboards (`/admin/observability`) gain a "Structural Index" panel showing the three gauges + the rebuild histogram sparkline. A health check at `/api/health/structural-index` returns `{status, lag_seconds, pages, relations}`.

## Testing strategy

### Unit tests

- `StructuralIndexServiceTest` — event-driven updates: create page, rename, delete, verify projection + DB state.
- `CanonicalIdAssignmentTest` — auto-assign on save, immutability on rename, duplicate detection.

### Integration tests

In `wikantik-it-tests`:

- **REST:** create 30 pages via the REST write path, assert each structural endpoint returns the expected shape, assert filters work.
- **MCP:** invoke each new MCP tool against a seeded Cargo-Tomcat, assert schemas and example payloads match the tool's self-described JSON schema.
- **Rename survives:** create page A with `canonical_id=X`, rename to B, assert `GET /api/pages/by-id/X` still resolves.
- **Rebuild SLA:** seed 2000 pages, measure `rebuild()` duration, assert < 5 s (budget set at the start of the sprint; regression-gated).

### Dogfooding

Run the harness against the dogfoodable cluster first:

- Generate `Main.md` from structural data.
- `diff` against the committed hand-curated version — expect convergence after a week of authoring work.
- Any remaining divergence represents either editorial pins (add to `Main.pins.yaml`) or actual taxonomy fixes.

## Effort and sequencing

| Phase | Effort | Blocks |
|-------|--------|--------|
| 1 — canonical IDs | ~1 sprint (1 dev) | Phase 2 |
| 2 — `Main.md` generation | ~0.5 sprint | — |
| 3 — enforcement | ~0.25 sprint | Phase 1 complete |

Total: ~1.75 dev-sprints. The structural queries (Phase 1's observe-only mode) are useful on day one — agents gain `list_clusters`, `list_tags`, and `sitemap` immediately, even before canonical_ids are fully backfilled.

## Open questions

1. **ULID vs UUIDv7.** Proposal picks ULID for its URL-safety and sort-friendliness, but UUIDv7 has wider tooling support. Decision: ULID, but store as `CHAR(26)` so migration to UUIDv7 is a column-type change, not a schema rethink.
2. **Cluster membership: frontmatter `cluster` vs multi-cluster.** Today `cluster: <name>` is a scalar. Multi-cluster membership is not supported — if needed, extend the frontmatter parser to accept `cluster: [name1, name2]` in a follow-up. No relation mechanism needed.

## Structural Index consumers

Code that queries `StructuralIndexService` for cluster or verification data:

- **`AgentHintsDeriver`** (in `wikantik-main`) — uses `StructuralIndexService.getCluster(...)` for hub designation and `verificationOf(...)` for the AUTHORITATIVE confidence bonus. Powers `prefer_pages` on the `/for-agent` projection.
- **`AgentGradeAuditResource`** (in `wikantik-rest`) — scans the index via `listPagesByFilter` to surface pages with weak agent-grade signals (no cluster, no intra-cluster inbound links, generic hub summary, missing or stale verification). Mounted at `GET /admin/agent-grade-audit`.

## Related designs

- [AgentGradeContentDesign](AgentGradeContentDesign) — the companion design that consumes this structural spine for agent-shaped page projections and retrieval-quality CI.
- [HybridRetrieval](HybridRetrieval) — existing retrieval infrastructure; the structural spine does not replace retrieval, it enriches the result surface.
- [GoodMcpDesign](GoodMcpDesign) — the design principles all new MCP tools in this plan must follow.
