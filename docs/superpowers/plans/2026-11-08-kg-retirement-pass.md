# Knowledge Graph retirement pass (step 1 of removal) — 2026-11-08

Goal: the Knowledge Graph (LLM-extracted entities, `kg_nodes`/`kg_edges`/`kg_proposals`,
`chunk_entity_mentions`, KG policy, hub discovery) is **switched off by default and unreachable
from every UI**, with the code left in place and fenced for a later deletion release. Ships as a
patch release.

**Not the KG** (must keep working): `kg_content_chunks` + `content_chunk_embeddings` (dense
retrieval), chunking, bundle, briefing, agent projection, query log, eval, frontmatter defaults,
the Page Graph, the ontology's page/concept graphs, drift, Content Intelligence.

## Decisions (Jake, 2026-11-08)

1. **Off switch = flag default off.** `wikantik.knowledge.enabled` defaults to `false`; "off" must
   mean zero KG table reads. Setting it `true` revives the backend until deletion.
2. **Hub Discovery / Hub Proposals / Existing Hubs retire with the KG** (they cluster entity
   mentions).
3. **Content Embeddings + LLM Activity move to a new admin "Embeddings" page**; their endpoints
   leave `/admin/knowledge-graph/*` (which 503s when the KG is off).
4. **Isolation = one gate + an ArchUnit freeze fence now**; moving non-KG code out of
   `com.wikantik.knowledge` happens in the deletion release.

Defaults announced and not objected to: KG MCP tools unregistered (both servers) and agent docs
updated; ontology stops projecting KG entities (`/id/entity/*` 404s; next rebuild purges them);
`kg_include` leaves the frontmatter editor schema (stored values stay, inert); agent-facing
`relatedPages` is re-sourced from page-embedding centroids; admin-overview KG cards removed;
`[{Relationships}]` renders empty.

## Tasks

- **T1 Backend gate.** `KnowledgeGraphFlag` (wikantik-api, default false) as the single reader;
  ini default + description; ontology wiring builds no KG repositories and projects no entities
  when off; admin overview loses the KG panels (and its unguarded `kgService()` derefs);
  `/api/capabilities` drops `knowledgeGraph`; `kg_include` leaves `FrontmatterSchema`.
- **T2 REST relocation.** `/admin/embeddings/*` (status of the real chunk-embedding index,
  pages-without-frontmatter, frontmatter backfill); drop those + hub routes from the KG resource;
  SPA `/knowledge-graph` route + `/admin/knowledge` legacy redirect removed; REST ITs for off
  surfaces retired.
- **T3 Agent surfaces.** KG tools absent from the pinned MCP surface + instructions; knowledge-mcp
  name/instructions de-KG'd; `relatedPages` from `PageEmbeddingSimilarity` with ACL filtering at
  every consumer; stale "KG rerank"/"knowledge-graph cluster" wording; KG MCP ITs retired;
  `McpAgents.md` + wiki-content skill.
- **T4 Frontend.** Delete KG admin pages, kg-policy pages, `/knowledge-graph` viewer, page panel,
  hub tabs, KG API client functions, overview cards, capability flags; new `/admin/embeddings`
  page hosting Content Embeddings + LLM Activity; banner/label/link text; selenide ITs.
- **T5 Fence.** ArchUnit freeze rule: no new dependency from outside the KG onto KG classes.
- **T6 Verify.** Full gate; local smoke test with JDWP breakpoints on KG entry points plus
  `pg_stat_user_tables` read counters for the KG tables before/after; docs; release.
