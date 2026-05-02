---
title: Page Graph vs Knowledge Graph — clean separation
date: 2026-05-02
status: draft
---

# Page Graph vs Knowledge Graph — clean separation

## Problem

The codebase has two distinct graph-like subsystems that the current
naming and frontmatter grammar actively confuse:

- The **page-link graph** — page-to-page edges from wikilinks plus
  typed `relations:` entries in page frontmatter — powers the
  reader-facing visualization at `/graph`. The sidebar labels it
  **"Knowledge Graph"**.
- The **Knowledge Graph** — LLM-extracted entity nodes, hubs,
  co-mention edges, embeddings — lives in `wikantik-knowledge`, the
  `com.wikantik.knowledge.*` packages, and the `kg_*` tables. It is
  reached through admin pages and MCP tools.

Two things are wrong:

1. **The user-facing thing called "Knowledge Graph" is actually the
   page-link graph**, while the real Knowledge Graph hides under admin
   tooling. Every contributor, agent, and operator pays a
   disambiguation tax on the word "graph".
2. **The frontmatter `relations:` mechanism is a third concept** that
   doesn't fit either graph cleanly: it is hand-curated (so not the
   Knowledge Graph, which is extracted), but it is also not a real
   page link (so its place in the Page Graph is dubious — it expresses
   typed semantic predicates between page concepts, which is
   KG-shaped). Empirically it is unloved: 3 of 951 content pages use
   it, and nothing load-bearing depends on it.

This spec defines a single canonical naming, removes the `relations:`
mechanism so the Page Graph is strictly defined by real wiki links and
hub (cluster) membership, and performs the top-to-bottom rename needed
to enforce the separation.

## Canonical definitions

> **Page Graph.** A graph whose edges are real page-to-page wikilinks.
> Sources: wikilinks parsed from page bodies, period. Companion
> structure (not edges of the Page Graph itself, but co-resident in
> the same subsystem): **canonical IDs** (rename-stable identifiers
> assigned in frontmatter) and **hub membership** (the `cluster:`
> frontmatter field assigning each page to a hub). Purpose:
> navigation, authoring aids, broken-link / orphan triage, the visual
> `/page-graph` view. Audience: human readers and authors.
>
> **Knowledge Graph.** Nodes are LLM-extracted entities; edges are
> co-mention or typed-relation predicates between them. Purpose:
> semantic retrieval, hub discovery, agent-facing question answering.
> Audience: agents and admins.

The **Structural Spine** survives in a narrower form: it is the
schema layer that gives every page a stable identity (`canonical_id`)
and a hub assignment (`cluster:`), validated at save time. It no
longer carries a typed-relation grammar. It remains a sub-area within
the Page Graph subsystem (`com.wikantik.pagegraph.spine`).

After this work, the word "graph" used by itself is a code smell —
grep or code review should always find `pagegraph`, `page-graph`,
`knowledgegraph`, `knowledge-graph`, or `kg`.

## Scope of changes

### 1. Remove the frontmatter `relations:` mechanism

This is the central simplification — done first because the rest of
the rename is cleaner without `relations:` in scope.

**Frontmatter grammar.** The `relations:` block is removed. No
replacement. If the same authoring intent (e.g. "this page supersedes
that one") needs to come back later, it belongs on the Knowledge
Graph as a curated edge, not in page frontmatter.

**Pages currently using it (3).** Strip the `relations:` block from:

- `docs/wikantik-pages/AutoScalingStrategies.md`
- `docs/wikantik-pages/OpenSourceContribution.md`
- `docs/wikantik-pages/OntologyDesignPatterns.md`

The existing entries reference relation `type:` values (`Pods`,
`coverage_threshold`, `security_scan`, `architectural_drift`) that
aren't in the validator's allow-list anyway — they would have been
rejected on next save. They are not load-bearing.

**Database.** New migration `V<NNN>__drop_page_relations.sql` (the
next free number at implementation time — V023 today) drops the
`page_relations` table. The table contains only data derived from
frontmatter, with frontmatter as the source of truth — safe to drop,
no backup needed. (Per project convention, this migration is
DDL-only; no data backfill.)

**Java code to delete:**

- `com.wikantik.knowledge.structure.PageRelationsDao` (and tests)
- `com.wikantik.knowledge.structure.FrontmatterRelationValidator`
  (and tests)
- The `relations:` validation branch inside
  `StructuralSpinePageFilter` (the canonical-id auto-assignment
  branch stays). Update the filter's tests accordingly.
- The `relations` field on `StructuralProjection` and the projection
  logic that populates it.
- `com.wikantik.api.structure.{Relation, RelationType, RelationEdge,
  TraversalSpec}` — these are wholly about the typed-relation
  grammar and have no other consumer once the above is removed.
- `com.wikantik.api.structure.StructuralIndexService` — keep the
  type, but trim the relation-traversal methods. Canonical-id
  lookup, cluster lookup, and `Main.md` projection methods stay.

**REST endpoint to delete:** `PageRelationsResource` (and its tests).
The `/api/pages/*/relations` paths are removed. No redirect — these
are programmatic endpoints with very low real-world usage.

**MCP tool to delete:** `TraverseRelationsTool` on `/knowledge-mcp`
(and its tests). The tool's previous job — "walk typed edges between
pages" — is replaced by the existing Page Graph tools on
`/wikantik-admin-mcp` (`get_outbound_links`, `get_backlinks`) for
real link traversal, and by KG retrieval tools on `/knowledge-mcp`
for semantic traversal.

**Frontend.** The graph data renderer collapses its typed-edge palette
(`KNOWN_PALETTE` keyed by `links_to`/`related_to`/`part_of`) to a
single edge style — only `links_to` will be produced now.
`mergeBidirectionalEdges` and `mergeParallelEdges` keep working
unchanged. Filter UI loses its by-type filter for relation kinds.

**Docs.** `docs/wikantik-pages/StructuralSpineDesign.md` is rewritten
to describe the narrower Structural Spine (canonical IDs + cluster
membership + save-time enforcement), with a "removed in this work"
note explaining why typed `relations:` are gone.

**Stays (explicitly).**

- `canonical_id` frontmatter field. The save-time auto-assignment
  and validation in `StructuralSpinePageFilter` stays. This is the
  rename-stable identity system used by `Main.pins.yaml` and the
  `/for-agent` projection.
- `cluster:` frontmatter field. This is hub membership; 948 of 951
  pages use it.
- `Main.md` generation (`GenerateMainPageCli`) — already runs off
  `Main.pins.yaml` + `cluster:`, never touched `relations:`.
- `OutboundLinksResource`, `ReferenceManager` — these operate on
  real wikilinks and are exactly what we want to keep.
- All Knowledge Graph code (`com.wikantik.knowledge.*`,
  `wikantik-knowledge` module, `kg_*` tables, MCP tools on
  `/knowledge-mcp` other than `TraverseRelationsTool`).

### 2. Java packages (in `wikantik-main`)

Move:

- `com.wikantik.references` → `com.wikantik.pagegraph.references`
- `com.wikantik.knowledge.structure` (what survives section 1) →
  `com.wikantik.pagegraph.spine`. Surviving classes:
  `StructuralSpinePageFilter` (without the relations branch),
  `DefaultStructuralIndexService` (without relation methods),
  `StructuralProjection` (without the relations field), and the
  canonical-id support classes.

Stay put (these are the real Knowledge Graph):

- `com.wikantik.knowledge.embedding`
- `com.wikantik.knowledge.extraction`
- `com.wikantik.knowledge.chunking`
- `com.wikantik.knowledge.eval`
- `com.wikantik.knowledge.mcp`
- `com.wikantik.knowledge.{HubOverviewService, HubProposalService,
  DefaultKnowledgeGraphService, JdbcKnowledgeRepository, MentionIndex,
  KnowledgeGraphServiceFactory}`

Net: `com.wikantik.pagegraph.*` consolidates page-link code;
`com.wikantik.knowledge.*` is now exclusively the KG.

No new Maven module — the split is package-only.

API-side (`wikantik-api/src/main/java/com/wikantik/api/`): the
`com.wikantik.api.knowledge` package today contains only Knowledge
Graph types (entity nodes, edges, proposals, retrieval results) — no
moves needed there. The trimmed `com.wikantik.api.structure` package
(after deleting the relation grammar types in section 1) is renamed
to `com.wikantik.api.pagegraph` to match the new umbrella name.

### 3. REST endpoints (in `wikantik-rest`)

Reader-side:

- `/graph` → `/page-graph`
- Add a permanent (301) redirect `/graph` → `/page-graph` so existing
  external links and bookmarks continue to work.

Admin-side:

- `/admin/knowledge` → `/admin/knowledge-graph` (existing tabs:
  extraction, hubs, KG policy, etc.).
- New URL prefix `/admin/page-graph` for Page-Graph-specific operator
  surfaces. Move `AdminStructuralConflictsResource` under
  `/admin/page-graph/conflicts`. Class name unchanged.

Resource classes already correctly named for the Page Graph and not
renamed: `OutboundLinksResource`. (`PageRelationsResource` is
deleted, per section 1.)

### 4. Frontend (in `wikantik-frontend/src/components`)

- Rename folder `components/graph/` → `components/pagegraph/`.
- Component files inside the folder largely keep their names — they are
  generic graph rendering primitives (`GraphCanvas`, `GraphLegend`,
  `GraphZoomSlider`, `FilterPanel`, `GraphErrorBoundary`, etc.). The
  route component is renamed `GraphView.jsx` → `PageGraphView.jsx`.
- Edge palette in `graph-data.js` collapses to a single style (per
  section 1). Filter UI for relation types is removed.
- Sidebar label: "Knowledge Graph" → "Page Graph". The link target
  changes from `/graph` to `/page-graph`.
- Admin nav: rename the "Knowledge" entry to "Knowledge Graph". The
  underlying components (`AdminKnowledgePage`, `GraphExplorer`,
  `EdgeExplorer`, `ExtractionTab`, `AdminKgPolicyPage`,
  `ChunkInspectorTab`, `ContentEmbeddingsTab`, `ExistingHubsPanel`,
  `HubDiscoveryCard`) are already KG-correct.

A reader-facing Knowledge Graph view is **out of scope** for this
project. The sidebar gains the Page Graph link but no parallel
Knowledge Graph link until that view is designed separately.

### 5. MCP tool surface

**Admin MCP (`/wikantik-admin-mcp`).** Tool *names* are accurate as-is
(`get_backlinks`, `get_outbound_links`, `get_broken_links`,
`get_orphaned_pages`, `get_page_history`, etc. — they describe the
action, not the umbrella concept). Update tool **descriptions** to say
"Page Graph" wherever they currently say "graph", "wiki graph", or
similar.

**Knowledge MCP (`/knowledge-mcp`).** `TraverseRelationsTool` is
deleted (per section 1). Remaining tool names already read as
Knowledge / KG concepts. Audit descriptions to consistently say
"Knowledge Graph" rather than "knowledge graph" or just "graph".

The point of the description audit: an agent presented with both MCP
servers can pick the right tool by reading the descriptions.

### 6. Documentation

- `CLAUDE.md`: insert a short "Page Graph vs Knowledge Graph" section
  near the top of the architecture overview with the two canonical
  definitions verbatim from this spec. Sweep the rest of the doc for
  ambiguous "graph" references and qualify each. Update the
  Structural Spine summary to reflect the narrower scope (canonical
  IDs + clusters; no `relations:`).
- `README.md`: same one-paragraph explainer near the top; sweep for
  ambiguous references.
- `docs/wikantik-pages/StructuralSpineDesign.md`: rewrite to describe
  the narrower spine. Add a "Changes 2026-05-02" note explaining the
  removal of typed `relations:` and pointing to this spec.
- `docs/wikantik-pages/HybridRetrieval.md` and
  `docs/wikantik-pages/AgentGradeContentDesign.md`: audit pass — every
  use of "graph" must disambiguate.
- New page `docs/wikantik-pages/PageGraphVsKnowledgeGraph.md` — the
  single authoritative explainer for end users and contributors.
  Linked from the sidebar help, the `/page-graph` view, and the
  `/admin/knowledge-graph` view.

### 7. Database and CLI

- New migration `Vxxx__drop_page_relations.sql` (per section 1).
  This is the only DB change.
- Other tables remain cleanly partitioned (`page_canonical_ids`,
  `page_slug_history` on the Page Graph side; `kg_nodes`, `kg_edges`,
  `kg_proposals`, `kg_node_embeddings`, `kg_cluster_policy`, etc. on
  the Knowledge Graph side). No further renames.
- No CLI rename. `bin/kg-*.sh` is already correct. No Page Graph CLI
  scripts exist; none are introduced by this change.

## Backwards compatibility

- HTTP `301 /graph` → `/page-graph` so reader bookmarks continue to
  work.
- HTTP `301 /admin/knowledge` → `/admin/knowledge-graph`.
- `/api/pages/*/relations` is removed with no redirect — programmatic
  endpoint with no known consumers, and there is no equivalent to
  redirect to.
- `TraverseRelationsTool` is removed from `/knowledge-mcp`; agents
  bound to it must rebind to `get_outbound_links` /
  `get_backlinks` (real-link traversal) or to KG retrieval tools
  (semantic traversal).
- Java package renames are source-incompatible, but this is a single
  monorepo with no external consumers, so a single coordinated commit
  is sufficient. No deprecation shims.
- DB schema changes by one dropped table; the `page_relations` table
  contains no source-of-truth data.

## Non-goals

- No new reader-facing Knowledge Graph view (separate project).
- No new `wikantik-page-graph` Maven module (package-only refactor;
  cyclic-dependency risk with rendering/parsing not worth the
  symmetry).
- No replacement mechanism for the deleted `relations:` grammar. If
  curated typed edges between concepts come back later, they belong
  on the Knowledge Graph.
- No backwards-compatibility shims beyond the two HTTP redirects.
- No changes to authentication, authorization, retrieval ranking, or
  any other non-naming, non-relations behaviour.

## Acceptance

- `grep -rE "^relations:" docs/wikantik-pages/` returns no matches.
- `grep -rE "PageRelations|FrontmatterRelationValidator|TraverseRelationsTool|RelationType|RelationEdge|TraversalSpec" --include="*.java"`
  returns no matches.
- `psql … -c "\dt page_relations"` reports the table does not exist.
- `grep -rn "knowledge\.structure\|knowledge\.references" --include="*.java"`
  returns no matches.
- Sidebar in `wikantik-frontend/src/components/Sidebar.jsx` shows
  "Page Graph" linking to `/page-graph` (not "Knowledge Graph"
  linking to `/graph`).
- `curl -sI http://localhost:8080/graph` returns `301` to
  `/page-graph`.
- `curl -sI http://localhost:8080/admin/knowledge` returns `301` to
  `/admin/knowledge-graph`.
- `curl -sI http://localhost:8080/api/pages/Foo/relations` returns
  `404`.
- The new `PageGraphVsKnowledgeGraph.md` page exists and is reachable
  from the sidebar help.
- All existing tests pass after rename. Integration tests covering
  the reader graph view, admin KG pages, and the MCP surface are
  updated to match.
- `mvn clean install -T 1C -DskipITs` is green.
- `mvn clean install -Pintegration-tests -fae` is green.
