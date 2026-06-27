---
title: Page Graph vs Knowledge Graph
type: article
cluster: wikantik-development
audience:
- humans
- agents
summary: 'Canonical explainer of two distinct subsystems: the Page Graph (wikilink
  edges) vs. the Knowledge Graph (LLM-extracted entities) and why not to conflate
  them.'
canonical_id: 01KTGSV428AK6A68N69X235ZHT
tags:
- page-graph
- knowledge-graph
- wikantik-development
- architecture
---

<!-- canonical_id is omitted intentionally — StructuralSpinePageFilter
     auto-assigns a ULID on first save and writes it back into the
     frontmatter. -->

# Page Graph vs Knowledge Graph

> 🌐 **Product overview:** [Page graph on wikantik.com](https://www.wikantik.com/platform/page-graph.html) — a plain-language walkthrough for readers and AI agents.


Wikantik distinguishes two graph subsystems. Confusing them is the
single most common source of bugs and miscommunication when working
on retrieval, navigation, or admin tooling. Use the right name.

## Page Graph

**A graph whose edges are real page-to-page wikilinks.**

- **Sources.** Wikilinks parsed from page bodies (`[OtherPage](OtherPage)`,
  `[OtherPage]`, etc.). Period.
- **Companion structure** (not edges of the Page Graph itself, but
  co-resident in the same subsystem): the `canonical_id` field in
  frontmatter (the rename-stable identifier) and the `cluster:` field
  (hub membership).
- **Purpose.** Navigation, authoring aids (broken-link triage, orphan
  pages), the visual `/page-graph` view.
- **Audience.** Human readers and authors.
- **Code.** `com.wikantik.pagegraph.*` (in `wikantik-main`),
  `com.wikantik.api.pagegraph` (in `wikantik-api`).
- **UI.** `/page-graph` (reader); `/admin/page-graph/*` (operator).

## Knowledge Graph

**A graph whose nodes are LLM-extracted entities and whose edges are
co-mention or typed-relation predicates between them.**

- **Sources.** The entity-extraction pipeline (`bin/kg-extract.sh`)
  reads page text and proposes nodes and edges; admins approve them.
- **Purpose.** Semantic retrieval, hub discovery, agent-facing
  question answering.
- **Audience.** Agents and admins.
- **Code.** `com.wikantik.knowledge.*` (in `wikantik-main`),
  `wikantik-knowledge` module, `kg_*` tables.
- **UI.** `/admin/knowledge-graph/*` (operator); `/knowledge-mcp` MCP
  tool surface (agents).

## How to tell them apart

| Question | Page Graph | Knowledge Graph |
|---|---|---|
| What is an edge? | A wikilink one author wrote in one page | An LLM-extracted predicate between two entities |
| What is a node? | A page | An entity (concept, person, organisation, etc.) |
| Who curates it? | Authors (by writing links) | Extraction pipeline (with admin review) |
| Where does it live in code? | `pagegraph.*` | `knowledge.*`, `kg_*` |

## What was removed (2026-05-02)

The frontmatter `relations:` mechanism — a third concept that let
authors hand-curate typed edges between pages without writing real
wikilinks — was removed. Three of 951 pages used it; nothing
load-bearing depended on it. After removal, the Page Graph is
strictly the wikilinks graph. If curated typed edges between concepts
need to come back later, they belong on the Knowledge Graph as
admin-approved edges, not in page frontmatter.

## Page Graph consumers

Code that reads wikilink or cluster data from the Page Graph subsystem:

- **`AgentHintsDeriver`** (in `wikantik-main`, package `com.wikantik.knowledge.agent`) — uses `ReferenceManager.findReferrers(slug)` to compute intra-cluster inbound link centrality for `prefer_pages` ranking on the `/for-agent` projection. See [docs/superpowers/specs/2026-05-10-derived-agent-hints-design.md](../superpowers/specs/2026-05-10-derived-agent-hints-design.md).

## See also

- [StructuralSpineDesign](StructuralSpineDesign) — the canonical-id
  and cluster machinery that lives inside the Page Graph subsystem.
- [HybridRetrieval](HybridRetrieval) — the hybrid retrieval stack; also documents the shelved KG graph rerank (boost=0 by default).
- `docs/superpowers/specs/2026-05-02-page-graph-vs-knowledge-graph-design.md`
  — the spec that drove this separation.
