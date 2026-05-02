---
title: Page Graph vs Knowledge Graph — clean separation
date: 2026-05-02
status: draft
---

# Page Graph vs Knowledge Graph — clean separation

## Problem

The codebase now has two distinct graph-like subsystems, and the existing
naming actively confuses them:

- The **page-link graph** — page-to-page edges from wikilinks plus the
  Structural Spine's typed `relations:` frontmatter — is what powers the
  reader-facing visualization at `/graph`. The sidebar labels it
  **"Knowledge Graph"**.
- The **Knowledge Graph** — LLM-extracted entity nodes, hubs, co-mention
  edges, embeddings — lives in `wikantik-knowledge`, the
  `com.wikantik.knowledge.*` packages, and the `kg_*` tables. It is
  reached through admin pages and MCP tools.

So the user-facing thing called "Knowledge Graph" is actually the
page-link graph, and the real Knowledge Graph is mostly hidden under
admin tooling. New contributors, agents reading MCP descriptions, and
the operator triaging an issue all have to disambiguate every time the
word "graph" appears.

This spec defines a single canonical naming and the top-to-bottom
rename needed to enforce it.

## Canonical definitions

> **Page Graph.** Edges are page-to-page links. Sources: wikilinks
> parsed from page bodies; typed `relations:` from page frontmatter
> (`related_to`, `part_of`, etc.); canonical IDs and the typed-relation
> grammar provided by the Structural Spine. Purpose: navigation,
> authoring aids, broken-link / orphan triage, the visual `/page-graph`
> view. Audience: human readers and authors.
>
> **Knowledge Graph.** Nodes are LLM-extracted entities; edges are
> co-mention or typed-relation predicates between them. Purpose:
> semantic retrieval, hub discovery, agent-facing question answering.
> Audience: agents and admins.

The **Structural Spine** (canonical IDs, typed-relation grammar,
save-time enforcement, generated `Main.md`) is folded **inside** the
Page Graph umbrella. It is the schema layer that the Page Graph is
built on, and remains a sub-area within `com.wikantik.pagegraph`.

After this rename, the word "graph" used by itself is a code smell —
grep or code review should always find `pagegraph`, `page-graph`,
`knowledgegraph`, `knowledge-graph`, or `kg`.

## Scope of changes

### 1. Java packages (in `wikantik-main`)

Move:

- `com.wikantik.references` → `com.wikantik.pagegraph.references`
- `com.wikantik.knowledge.structure` → `com.wikantik.pagegraph.spine`

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
moves needed there. If page-graph-related interfaces surface during
the refactor (e.g. lifted out of `wikantik-main` to break a cycle),
they land in a new `com.wikantik.api.pagegraph` package rather than
under `api/knowledge/`.

### 2. REST endpoints (in `wikantik-rest`)

Reader-side:

- `/graph` → `/page-graph`
- Add a permanent (301) redirect `/graph` → `/page-graph` so existing
  external links and bookmarks continue to work.

Admin-side:

- `/admin/knowledge` → `/admin/knowledge-graph` (existing tabs:
  extraction, hubs, KG policy, etc.).
- New URL prefix `/admin/page-graph` for Page-Graph-specific operator
  surfaces. Move `AdminStructuralConflictsResource` under
  `/admin/page-graph/conflicts`. Class names unchanged.

Resource classes already correctly named for the Page Graph and not
renamed: `PageRelationsResource`, `OutboundLinksResource`.

### 3. Frontend (in `wikantik-frontend/src/components`)

- Rename folder `components/graph/` → `components/pagegraph/`.
- Component files inside the folder largely keep their names — they are
  generic graph rendering primitives reused across both surfaces in the
  long run (`GraphCanvas`, `GraphLegend`, `GraphZoomSlider`,
  `FilterPanel`, `GraphErrorBoundary`, etc.). The route component is
  renamed `GraphView.jsx` → `PageGraphView.jsx`.
- Sidebar label: "Knowledge Graph" → "Page Graph". The link target
  changes from `/graph` to `/page-graph`.
- Admin nav: rename the "Knowledge" entry to "Knowledge Graph". The
  underlying components (`AdminKnowledgePage`, `GraphExplorer`,
  `EdgeExplorer`, `ExtractionTab`, `AdminKgPolicyPage`,
  `ChunkInspectorTab`, `ContentEmbeddingsTab`,
  `ExistingHubsPanel`, `HubDiscoveryCard`) are already KG-correct.

A reader-facing Knowledge Graph view is **out of scope** for this
project. The sidebar gains the Page Graph link but no parallel
Knowledge Graph link until that view is designed separately.

### 4. MCP tool surface

**Admin MCP (`/wikantik-admin-mcp`).** Tool *names* are accurate as-is
(`get_backlinks`, `get_outbound_links`, `get_broken_links`,
`get_orphaned_pages`, `get_page_history`, etc. — they describe the
action, not the umbrella concept). Update tool **descriptions** to say
"Page Graph" wherever they currently say "graph", "wiki graph", or
similar.

**Knowledge MCP (`/knowledge-mcp`).** Tool names already read as
Knowledge / KG concepts. Audit descriptions to consistently say
"Knowledge Graph" rather than "knowledge graph" or just "graph".

The point of the description audit: an agent presented with both MCP
servers can pick the right tool by reading the descriptions.

### 5. Documentation

- `CLAUDE.md`: insert a short "Page Graph vs Knowledge Graph" section
  near the top of the architecture overview with the two canonical
  definitions verbatim from this spec. Sweep the rest of the doc for
  ambiguous "graph" references and qualify each.
- `README.md`: same one-paragraph explainer near the top; sweep for
  ambiguous references.
- `docs/wikantik-pages/StructuralSpineDesign.md`: add a header note
  "(Part of the Page Graph subsystem.)"
- `docs/wikantik-pages/HybridRetrieval.md` and
  `docs/wikantik-pages/AgentGradeContentDesign.md`: audit pass — every
  use of "graph" must disambiguate.
- New page `docs/wikantik-pages/PageGraphVsKnowledgeGraph.md` — the
  single authoritative explainer for end users and contributors.
  Linked from the sidebar help, the `/page-graph` view, and the
  `/admin/knowledge-graph` view.

### 6. Database and CLI

No DB table renames. `page_*` and `kg_*` are already cleanly
partitioned (`page_canonical_ids`, `page_relations`, `page_slug_history`
on the Page Graph side; `kg_nodes`, `kg_edges`, `kg_proposals`,
`kg_node_embeddings`, `kg_cluster_policy`, etc. on the Knowledge Graph
side). No new migration needed for naming.

No CLI rename. `bin/kg-*.sh` is already correct. No Page Graph CLI
scripts exist; none are introduced by this change.

## Backwards compatibility

- HTTP `301 /graph` → `/page-graph` so reader bookmarks continue to
  work.
- HTTP `301 /admin/knowledge` → `/admin/knowledge-graph`.
- Java package renames are source-incompatible, but this is a single
  monorepo with no external consumers, so a single coordinated commit
  is sufficient. No deprecation shims.
- MCP tool *names* are unchanged; only descriptions change. Existing
  agent integrations continue to work without rebinding.
- DB schema unchanged.

## Non-goals

- No new reader-facing Knowledge Graph view (separate project).
- No new `wikantik-page-graph` Maven module (chose package-only
  refactor; cyclic-dependency risk with rendering/parsing not worth
  the symmetry).
- No backwards-compatibility shims beyond the two HTTP redirects.
- No changes to authentication, authorization, or any non-naming
  behaviour. This is a rename project.

## Acceptance

- `grep -r "Knowledge Graph" wikantik-frontend/src/components/Sidebar.jsx`
  returns no hits in the page-link route.
- `grep -r "knowledge\.structure\|knowledge\.references" --include="*.java"`
  returns no hits.
- `curl -sI http://localhost:8080/graph` returns `301` to
  `/page-graph`.
- `curl -sI http://localhost:8080/admin/knowledge` returns `301` to
  `/admin/knowledge-graph`.
- The new `PageGraphVsKnowledgeGraph.md` page exists and is reachable
  from the sidebar help.
- All existing tests pass after rename. Integration tests covering the
  reader graph view and admin KG pages are updated to use the new
  routes.
- `mvn clean install -T 1C -DskipITs` is green.
- `mvn clean install -Pintegration-tests -fae` is green.
