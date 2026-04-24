---
canonical_id: 01KQ0P60Q4PP4BXR1WQKBPV1WS
title: Structural Spine Design
cluster: wikantik-development
type: design
status: proposed
date: '2026-04-24'
author: claude-opus
summary: Detailed design for a machine-queryable structural index (clusters, tags, canonical IDs, typed cross-references) exposed via /api/structure/* and matching MCP tools, with a generated Main.md and stable page identity across renames. Addresses the "structural blindness" problem that forces agents to rediscover wiki shape via full-text search.
tags:
- design
- agent-context
- mcp
- structural-index
- canonical-id
- typed-relations
related:
- AgentGradeContentDesign
- HybridRetrieval
- GoodMcpDesign
- WikantikDevelopment
---

# Structural Spine Design

## Problem

Wikantik exposes 1000+ pages through two MCP servers (`/wikantik-admin-mcp`, `/knowledge-mcp`), 23 REST resources, and full-text search. None of them let an agent ask **"what does this wiki contain?"** without first paying the cost of full-text search. The taxonomy that exists — cluster membership, tag assignments, type declarations, page-to-page relationships — is encoded in human-readable places (YAML frontmatter, `Main.md` prose) but never exposed as structured data that agents can query.

Concrete consequences today:

- An agent asked *"what does this wiki know about retrieval?"* must search BM25 for "retrieval", hope the top hit is a hub page, then parse prose to find sub-articles.
- An agent asked *"show me every hub page"* has no endpoint to call. It must enumerate `/api/pages` and re-parse frontmatter client-side.
- Renaming a page (via `RenamePageTool`) silently invalidates every external reference — no stable identity survives the rename.
- Cross-references are flat Markdown links (`[X](X.md)`). The fact that `CorporateBondIndexFunds.md` is an **example-of** `IndexFundInvestingForEarlyRetirement.md`, and that `OldAuthScheme.md` is **superseded-by** `DatabaseBackedPolicyGrants.md`, exists only in authors' heads.
- `Main.md` is hand-curated prose that encodes the cluster taxonomy. It drifts from reality silently; every edit to frontmatter risks desync.

This design introduces a structural spine — a small set of first-class APIs, a persistent `canonical_id`, and a typed-relation vocabulary — so agents can navigate the wiki by shape, not by keyword.

## Goals

1. Expose the wiki's taxonomy (clusters, tags, types, hubs) as queryable data, not prose.
2. Give every page a `canonical_id` that survives renames.
3. Promote cross-references from untyped Markdown links to typed relations with a small, enforced vocabulary.
4. Replace hand-curated `Main.md` with generation from the structural index.
5. Make the structural index available via both REST (`/api/structure/*`) and MCP (`/knowledge-mcp`), so MCP-native and REST-native agents share one surface.
6. Fail-closed: if the structural index is unavailable, full-text search remains the fallback (same pattern as BM25 fallback in `HybridRetrieval`).

## Non-goals

- Replacing Lucene full-text search (this complements it).
- Replacing the knowledge graph (`KnowledgeGraphService`) — that service models extracted entities across chunks; the structural spine models pages and their authored relationships.
- Authoring a new markup dialect. Typed relations live in YAML frontmatter, not inline Markdown.
- Auto-discovering relations (that is the knowledge-graph projector's job; this spine records author-declared relations only).
- Changing REST response envelopes for existing endpoints.

## Data model

### Frontmatter additions

Every page gains two new frontmatter fields.

```yaml
---
title: Hybrid Retrieval
type: article
cluster: wikantik-development
tags: [retrieval, bm25, embeddings]
canonical_id: 01H8G3Z1K6Q5W7P9X2V4R0T8MN
relations:
  - type: example-of
    target: 01H8G3Z1PRN5Q3X4T9M2V7K0AB   # InformationRetrieval
  - type: prerequisite-for
    target: 01H8G3Z2E7FD8R1Q4V9X2T0NMP   # RetrievalExperimentHarness
  - type: supersedes
    target: 01H8F2Y0R5Q3X4T9M2V7K0AB12   # LegacyLuceneOnlySearch
---
```

- **`canonical_id`** — a 26-character [ULID](https://github.com/ulid/spec). Sort-friendly and URL-safe, fits the existing frontmatter parser, no UUID dashes. Immutable once assigned. Regenerating is a manual admin operation with a paper trail.
- **`relations`** — an ordered list of `{type, target}` objects. `target` is always a `canonical_id`, never a page title, so renames don't break the graph.

Both fields are optional during the bake-in period (Phase 1 below); they become required after the backfill migration lands.

### Relation-type vocabulary

A fixed, enforced set of relation types. Adding a new type requires a migration and a validator update — by design.

| Type | Meaning | Example |
|------|---------|---------|
| `part-of` | Page is a sub-article under a hub | `InternationalIndexFunds` **part-of** `IndexFundInvestingForEarlyRetirement` |
| `example-of` | Page is a concrete instance of a concept | `BondIndexFunds` **example-of** `IntroductionToIndexFundsAndETFs` |
| `prerequisite-for` | Reader should learn this page before the target | `InformationRetrieval` **prerequisite-for** `HybridRetrieval` |
| `supersedes` | Page replaces an older / deprecated page | `DatabaseBackedPolicyGrants` **supersedes** `XmlPolicyFileGrants` |
| `contradicts` | Page presents an intentionally opposing view | `IndexingIsSufficient` **contradicts** `ActiveManagementBeatsPassive` |
| `implements` | Page documents how to implement a concept from the target | `RetrievalExperimentHarness` **implements** `OfflineRetrievalEvaluation` |
| `derived-from` | Page's content is derived (extracted, summarised, synthesised) from the target | `IndexFundsShortGuide` **derived-from** `IndexFundInvestingForEarlyRetirement` |

Relations are **directional**. `A part-of B` does not imply `B has-part A` — the inverse is computed and served on read.

### Structural index projection

An in-memory projection of structural data, maintained by a new `StructuralIndexService`:

```
ClusterIndex  : Map<ClusterName,  ClusterEntry{hubPageId, articleIds, updatedAt}>
TagIndex      : Map<TagName,      TagEntry{pageIds, count}>
TypeIndex     : Map<PageType,     Set<PageId>>
CanonicalIndex: Map<CanonicalId,  PageDescriptor{slug, title, type, cluster, tags, summary, updated}>
SlugIndex     : Map<Slug,         CanonicalId>     // for name→id resolution
RelationGraph : DirectedMultigraph<CanonicalId, TypedEdge{type}>
```

The projection is **derivable** — authoritative state lives in frontmatter on disk + the pages table in Postgres. The index is a cache of derivations. Rebuild on bootstrap is mandatory; incremental maintenance on `PAGE_SAVE` / `PAGE_RENAME` / `PAGE_DELETE` events is the fast path.

### Database schema

One new table, to track canonical-id stability across renames and to hold the relation graph for fast SQL joins when the in-memory projection is being rebuilt or absent.

**Migration `V013__canonical_ids_and_relations.sql`** (idempotent):

```sql
CREATE TABLE IF NOT EXISTS page_canonical_ids (
    canonical_id   CHAR(26)   PRIMARY KEY,
    current_slug   VARCHAR(512) NOT NULL UNIQUE,
    title          VARCHAR(512) NOT NULL,
    type           VARCHAR(32)  NOT NULL,         -- hub | article | reference | runbook (the last is introduced in AgentGradeContentDesign)
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

CREATE TABLE IF NOT EXISTS page_relations (
    source_id      CHAR(26)   NOT NULL REFERENCES page_canonical_ids(canonical_id) ON DELETE CASCADE,
    target_id      CHAR(26)   NOT NULL REFERENCES page_canonical_ids(canonical_id) ON DELETE CASCADE,
    relation_type  VARCHAR(32) NOT NULL
        CHECK (relation_type IN ('part-of','example-of','prerequisite-for','supersedes','contradicts','implements','derived-from')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (source_id, target_id, relation_type)
);

CREATE INDEX IF NOT EXISTS ix_page_relations_target ON page_relations(target_id, relation_type);
CREATE INDEX IF NOT EXISTS ix_page_relations_source_type ON page_relations(source_id, relation_type);
CREATE INDEX IF NOT EXISTS ix_canonical_ids_type ON page_canonical_ids(type);
CREATE INDEX IF NOT EXISTS ix_canonical_ids_cluster ON page_canonical_ids(cluster);

GRANT SELECT, INSERT, UPDATE, DELETE ON page_canonical_ids, page_slug_history, page_relations TO :app_user;
```

The tables are the projection's durable backstop; the in-memory `StructuralIndexService` is the fast path. Frontmatter on disk remains the single source of truth — the DB is derived and can be rebuilt from page sources.

## Service design

### `StructuralIndexService`

Lives in `wikantik-knowledge` (co-located with `ContextRetrievalService` and `KnowledgeGraphService`; shares the same JDBC datasource and event bus). API:

```java
package com.wikantik.knowledge.structure;

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

    /* ------------------------------------------------- Typed relations       */
    List<RelationEdge>          outgoingRelations(String canonicalId, Optional<RelationType> filter);
    List<RelationEdge>          incomingRelations(String canonicalId, Optional<RelationType> filter);
    List<RelationEdge>          traverse(String canonicalId, TraversalSpec spec);  // BFS with type filters + depth cap

    /* ------------------------------------------------- Lifecycle             */
    void                        rebuild();                                    // full scan from frontmatter
    IndexHealth                 health();                                     // up-to-date, lag, last-rebuild
}
```

### Event flow

`StructuralIndexService` subscribes to the existing `WikiEventSubscriptionBridge` (already used by the admin MCP server for resource subscriptions — `McpServerInitializer` line 148 is the wiring pattern to mirror).

```
PAGE_SAVE(slug, previousSlug?, newFrontmatter)
  → parse frontmatter (canonical_id, type, cluster, tags, relations)
  → if canonical_id missing → emit AdminAlert("page without canonical_id")
  → if canonical_id is new → INSERT page_canonical_ids
  → if canonical_id exists and slug differs → INSERT page_slug_history(prev_slug), UPDATE current_slug
  → DELETE page_relations WHERE source=canonical_id; INSERT new relations
  → invalidate in-memory cluster/tag/type indexes for affected entries
  → publish StructuralIndexUpdated event (MCP resource subscribers re-read)

PAGE_RENAME(oldSlug, newSlug)
  → PAGE_SAVE already handles slug transition via canonical_id stability; rename is a degenerate case

PAGE_DELETE(slug)
  → DELETE page_canonical_ids WHERE current_slug=slug (CASCADE clears relations and history)
  → invalidate in-memory indexes
  → warn if any incoming relations existed (broken-link candidate)
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

### `GET /api/pages/{canonical_id}/relations?direction=out&type=example-of&depth=2`

Returns the directed relation graph rooted at the page, bounded by depth and optional type filter. Inverse queries use `direction=in`.

## MCP surface (added to `/knowledge-mcp`)

`wikantik-knowledge/src/main/java/com/wikantik/knowledge/mcp/` gains five tools. All tool descriptions follow the lean-but-concrete convention already established in the knowledge MCP: one-sentence purpose, explicit input shape, explicit output shape, and at least one example payload in the JSON schema (per the tool-description upgrade specified in `AgentGradeContentDesign.md`).

| Tool | Description |
|------|-------------|
| `list_clusters` | Return all clusters with hub page, article count, and last-updated timestamp. No inputs. |
| `list_tags` | Return tag dictionary. Input: `{min_pages?: int}`. Output: `[{tag, count, top_pages: [canonical_id]}]`. |
| `list_pages_by_filter` | Filtered page listing. Inputs: `type?`, `cluster?`, `tag?`, `updated_since?`, `limit?`, `cursor?`. Output: `{pages: [PageSummary], next_cursor?}`. |
| `get_page_by_id` | Resolve a canonical_id to the current page, including latest slug + frontmatter + rendered body. |
| `traverse_relations` | Walk the typed-relation graph. Inputs: `{from: canonical_id, direction, type_filter?, depth_cap?}`. Output: `[{source, target, type, path}]`. |

All five tools land in `wikantik-knowledge` (the read-only server) — structural queries are retrieval-adjacent, not admin operations. The `McpToolRegistry` in `wikantik-knowledge/…/mcp/KnowledgeMcpInitializer.java` gets five `registerTool(...)` calls; follow the existing `SearchKnowledgeTool` wiring pattern.

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

### Phase 2 — Typed relations (one sprint)

1. Relation schema validator in `FrontmatterParser` (rejects unknown types, rejects targets that don't resolve to a `canonical_id`).
2. CLI tool: `wikantik-extract-cli infer-relations --from-markdown-links --confidence 0.8`. Reads existing `[X](X.md)` Markdown links and emits relation proposals (most become `part-of`, `example-of`, `prerequisite-for`). Produces a patch file an author reviews — **never** auto-applies.
3. `ProposeRelationsTool` in `wikantik-admin-mcp` — lets an agent propose relation additions as part of the existing knowledge-proposal workflow (same UX as `ProposeKnowledgeTool`).
4. Backfill sweep: author-driven, one cluster at a time, starting with `wikantik-development` (dogfoodable) and `generative-ai` (highest agent-traffic cluster).

Exit criterion: every hub page has outgoing `part-of` relations from its sub-articles; every deprecated page has an explicit `supersedes` pointer; all historical wiki-development clusters fully annotated.

### Phase 3 — `Main.md` generation (half sprint)

1. Template + generator + pre-commit guard.
2. Delete the old `Main.md`; commit the generated one.
3. Add a regression integration test: regenerate on every build, fail the build if the checked-in `Main.md` diverges.

### Phase 4 — Enforcement (quarter sprint)

1. Make `canonical_id` **required** in the frontmatter validator (reject `PAGE_SAVE` without it).
2. Make `relations.target` resolution required (reject saves that point to a missing `canonical_id`).
3. Flip `StructuralIndexService` from observe-only to authoritative for structural queries.

## Failure modes and fail-closed behaviour

| Failure | Detection | Response |
|---------|-----------|----------|
| Projection out-of-date | `health()` reports `lag_seconds > 60` | `/api/structure/*` returns `X-Index-Staleness: <n>`; MCP tools include `stale_by_seconds` in every response. No 5xx. |
| Projection empty (not yet rebuilt) | `health().status = rebuilding` | Endpoints return HTTP 503 with `Retry-After`. MCP tools return structured `rebuilding` error with ETA. |
| Duplicate canonical_id detected | On save, unique constraint fires | Reject save with actionable error: *"canonical_id X already used by page Y. Pick a different ID or delete Y."* |
| Relation target missing | Validator on save | Reject save with error naming the missing ID. Soft mode (Phase 1 only) warns + admits. |
| Two-way disagreement (A says `supersedes B`, B says `supersedes A`) | Cycle detector on save or background check | Surface both pages in `/admin/structural-conflicts`. Do not auto-break cycles. |
| `page_relations` row with missing source/target | CASCADE DELETE protects it in-process; manual DB edits could break it | Nightly consistency scan emits `wikantik_structural_orphan_relations_total`; surfaces orphans in admin UI. |
| DB outage | `StructuralIndexService` falls back to in-memory projection | Reads continue to serve; writes that require DB persistence return 503 `{"fallback": "memory", "writes_deferred": true}` and replay when DB returns. |

The overarching principle matches `HybridRetrieval`'s fail-closed rule: **structural queries degrade, they do not lie.** Callers get stale data with an explicit staleness marker, or a clear unavailable error — never silently wrong data.

## Observability

New Prometheus metrics (all gauges + counters exposed via the existing `MeterRegistryHolder` in `wikantik-api/src/main/java/com/wikantik/api/observability/`):

| Metric | Type | Purpose |
|--------|------|---------|
| `wikantik_structural_index_pages_total` | gauge | Count of pages tracked in the projection |
| `wikantik_structural_index_lag_seconds` | gauge | Seconds since last event processed |
| `wikantik_structural_index_rebuild_duration_seconds` | histogram | Distribution of full-rebuild times |
| `wikantik_structural_relations_total{type}` | gauge | Relation counts per type |
| `wikantik_structural_relations_broken_total` | gauge | Relations whose target resolves to a missing canonical_id |
| `wikantik_structural_api_requests_total{endpoint,status}` | counter | Per-endpoint request volume |

Existing admin dashboards (`/admin/observability`) gain a "Structural Index" panel showing the three gauges + the rebuild histogram sparkline. A health check at `/api/health/structural-index` returns `{status, lag_seconds, pages, relations}`.

## Testing strategy

### Unit tests

- `StructuralIndexServiceTest` — event-driven updates: create page, rename, delete, mutate relations, verify projection + DB state.
- `RelationGraphTraversalTest` — BFS depth caps, type filters, cycle handling.
- `FrontmatterRelationsValidatorTest` — rejects unknown types, rejects invalid canonical IDs, rejects self-loops.

### Integration tests

In `wikantik-it-tests`:

- **REST:** create 30 pages via the REST write path, assert each structural endpoint returns the expected shape, assert filters work.
- **MCP:** invoke each new MCP tool against a seeded Cargo-Tomcat, assert schemas and example payloads match the tool's self-described JSON schema.
- **Rename survives:** create page A with `canonical_id=X`, rename to B, assert `GET /api/pages/by-id/X` still resolves.
- **Broken relations warning:** create A → relation-to B, delete B, assert admin alert fires and metric increments.
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
| 2 — typed relations | ~1 sprint | Phase 3 |
| 3 — `Main.md` generation | ~0.5 sprint | — |
| 4 — enforcement | ~0.25 sprint | Phase 2 complete |

Total: ~2.75 dev-sprints of focused work. The structural queries (Phase 1's observe-only mode) are useful on day one — agents gain `list_clusters`, `list_tags`, and `sitemap` immediately, even before canonical_ids are fully backfilled.

## Open questions

1. **ULID vs UUIDv7.** Proposal picks ULID for its URL-safety and sort-friendliness, but UUIDv7 has wider tooling support. Decision: ULID, but store as `CHAR(26)` so migration to UUIDv7 is a column-type change, not a schema rethink.
2. **Should `relations` live in frontmatter or a sidecar file?** Frontmatter keeps the authoring story single-source. Sidecar would let generated/inferred relations live separately from authored ones. Decision: frontmatter for authored relations; a separate `page_inferred_relations` table (future work, out of scope here) for automatically inferred ones. The two are distinct; do not conflate.
3. **Cluster membership: frontmatter `cluster` vs `part-of` relation.** Today `cluster: <name>` is a scalar. A `part-of` relation to a hub page is more precise and supports multi-cluster membership. Proposal: keep both for one release (cluster is a shorthand for the hub's ID), deprecate `cluster` once `part-of` is universal.
4. **Should `supersedes` auto-redirect?** When `A supersedes B`, should `/wiki/B` 301 to `/wiki/A`? Not in this design — that is a rendering concern. Surface the relationship in the page header and let authors decide page-by-page.

## Related designs

- [AgentGradeContentDesign](AgentGradeContentDesign) — the companion design that consumes this structural spine for agent-shaped page projections and retrieval-quality CI.
- [HybridRetrieval](HybridRetrieval) — existing retrieval infrastructure; the structural spine does not replace retrieval, it enriches the result surface.
- [GoodMcpDesign](GoodMcpDesign) — the design principles all new MCP tools in this plan must follow.
