# Knowledge Graph deletion (step 2 of removal) — 2026-11-09

Step 1 (`2026-11-08-kg-retirement-pass.md`, released 2.5.12) switched the Knowledge Graph off and fenced
it. This step deletes it. The burn-down list is the frozen store of
`wikantik-war/.../KnowledgeGraphFenceArchTest` (440 entries) plus the KG classes themselves
(~154 production files / 25k lines, ~106 test files / 19k lines).

**Keep (not the KG):** `kg_content_chunks` + `content_chunk_embeddings` and all chunking; bundle,
briefing, agent projection, eval, query log, frontmatter defaults; `DefaultContextRetrievalService`;
the `wikantik-knowledge` module and `/knowledge-mcp` endpoint names; the ontology's page/concept graphs
and content classes; the Page Graph; drift; citations.

## Decisions (Jake, 2026-11-09)

1. **Archive, then drop** the 15 KG tables: `pg_dump` of just those tables to the backup NAS before the
   deploy, then migration `V070` drops them (`kg_content_chunks`/`content_chunk_embeddings` stay).
2. **Remove the entity vocabulary** from the ontology: the 9 entity classes, the 21 KG predicates, the
   SHACL edge shapes and `/id/entity`. Content classes + SKOS concept scheme stay.
3. **Delete `RelationshipsPlugin` and strip `[{Relationships}]`** from repo pages and live prod pages.
4. **Mark the Wikantik-KG doc pages retired** in this pass (repo + prod): retired notice + `status: archived`.

Defaults announced: module/endpoint/table names kept; only the entangled pieces move now
(`KgJdbcSupport` → a non-KG JDBC base, `OllamaHttpCaller` out of `knowledge.extraction`, LLM-activity
recorders for extractor/judge deleted); the wholesale package move of chunking/bundle/briefing out of
`com.wikantik.knowledge` is a separate mechanical follow-up; every KG config key goes, including
`wikantik.knowledge.enabled`.

## Waves

- **Wave 1 (parallel):**
  - **D1 consumers** — wikantik-rest KG resources + handlers, admin-mcp + mcp-core + knowledge-mcp KG
    tools, extract-cli KG CLIs, `bin/kg-*` scripts, web.xml registrations, their tests and ITs.
  - **D2 ontology** — wikantik-ontology entity projection, T-Box entity vocabulary, SHACL edge gate,
    `/id/entity`, `NodeTypeMapping`'s entity half, `EntityTypeVocabulary`; main's ontology runtime.
  - **C1 content** — strip `[{Relationships}]`; mark KG doc pages retired (repo + prod via MCP).
- **Wave 2:** **D3 core** — wikantik-main KG packages, subsystem + persistence wiring, KG policy,
  plugin, config keys, `KnowledgeGraphFlag`; the entangled moves.
- **Wave 3:** **D4 api + schema** — wikantik-api KG types, `KgChangeEvent`, migration `V070`, IT seed,
  fence test retired, docs (CLAUDE.md, admin docs, config reference).
- **Verify:** full `--all` gate, local smoke, review; archive dump; release + deploy.
