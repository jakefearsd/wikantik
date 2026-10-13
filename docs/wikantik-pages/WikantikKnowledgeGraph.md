---
cluster:
- wikantik-platform
- wikantik-development
type: article
tags:
- knowledge-graph
- llm-extraction
- ontology
- mcp
- retrieval
- architecture
title: The Wikantik Knowledge Graph
canonical_id: 01KQTCBW5GBFJVWYB8V1CP49P5
summary: How Wikantik's LLM-extracted Knowledge Graph works — nodes, edges, provenance,
  the proposal workflow, ontology layer, and agent MCP surfaces.
status: active
date: '2026-06-20'
---
# The Wikantik Knowledge Graph

The Knowledge Graph (KG) is the semantic layer of Wikantik. It transforms Markdown pages into a rich, queryable network of typed entities and relationships that both humans and AI agents can traverse — distinct from the Page Graph, which tracks real wikilinks.

> **Page Graph vs Knowledge Graph:** The Page Graph records wikilink edges between pages. The Knowledge Graph records LLM-extracted entities and relations. Never conflate them. See [PageGraphVsKnowledgeGraph](PageGraphVsKnowledgeGraph) for the full explainer.

## Core Concepts

### Nodes and Edges

The KG is stored in PostgreSQL (`kg_*` tables — see [KnowledgeGraphCore](KnowledgeGraphCore)). Nodes represent typed entities extracted from page content — each carrying a `source_page`, a `node_type` from the 9-class vocabulary (person, organization, place, event, product, technology, concept, project, version), a provenance value and a review tier. Edges represent typed relations between entities; the relationship type must be one of a closed vocabulary of 21 predicates (`related_to`, `part_of`, `is_a`, `implements`, `located_in`, …) enforced by a database CHECK constraint (`RelationshipTypeVocabulary`). The KG is not projected from page frontmatter or wikilinks — those feed the Page Graph and the ontology's page and concept graphs.

A **write-time SHACL gate** (`OntologyShaclValidator`) enforces shape constraints on KG edges at the two write chokepoints: the curation path (`DefaultKgCurationOps`, shared by the admin REST surface and the admin-MCP `curate_edges` tool) refuses a non-conformant edge citing the violated shape; the machine-materialization path (`KgMaterializationService`) skips and logs it. The gate is currently narrow (the two shaped predicates are `implements` and `located_in`) and null-safe.

### The 9-Class Entity Vocabulary

Both the chunk-level and page-level extractors emit only the canonical `EntityTypeVocabulary.ENTITY_CLASSES`: **person, organization, place, event, product, technology, concept, project, version**. Prompts are generated from this vocabulary; parsers lowercase and allowlist to it (default: `concept`). An `EntityTypeVocabularyDriftTest` keeps the vocabulary in sync with `NodeTypeMapping`.

### KG Inclusion Policy

The KG operates under a **cluster-primary default-exclude policy**: a page contributes entities only if its cluster has an `include` row in `kg_cluster_policy` (managed at `/admin/kg-policy` or with `bin/kg-policy.sh`) or the page itself sets `kg_include: true` in frontmatter (`kg_include: false` always wins). See [KgInclusionPolicy](KgInclusionPolicy) for the full policy reference.

### Provenance Model

Trust is tracked on every node and edge by two markers: a **provenance** value (`human-authored`, `human-curated`, `ai-inferred`, `ai-reviewed`) and a review **tier** — `machine` (materialized after the LLM judge approved it) or `human` (a curator authored, confirmed or approved it). Reads default to `min_tier=machine` (`wikantik.kg.read.default_min_tier`); `human` gives the strict view. Human curators review proposals in the admin panel at `/admin/knowledge-graph`. See [ProvenanceModel](ProvenanceModel).

## The Proposal Workflow

The KG uses a **human-in-the-loop AI enrichment** cycle:

1. **Extraction:** Chunk-level extraction runs inside the wiki (off the save thread after a page's chunks change, and as a batch from the admin panel's Extraction tab) and offline through `wikantik-extract-cli` (`bin/kg-extract.sh`). The backend is Ollama by default (`wikantik.knowledge.extractor.backend`); the in-server default model is `gemma4-graph:12b` (`wikantik.knowledge.extractor.ollama.model`; the CLI's `--ollama-model` defaults to `gemma4-assist:latest`). Every request is sent with `think: false` (`OllamaChatRequest`) to suppress reasoning traces that break structured JSON. The page-level and chunk-level extractors both emit entities from the 9-class vocabulary.
2. **Proposal:** Extracted entities and edges are filed in the `kg_proposals` table (below `wikantik.knowledge.extractor.confidence_threshold`, default 0.6, they are dropped). Agents can also file proposals with the admin-MCP `propose_knowledge` tool. A triple already rejected (`kg_rejections`) is not re-filed.
3. **Machine judge:** An asynchronous judge LLM (`wikantik.kg.judge.enabled`, default true; a pass every `wikantik.kg.judge.cron.interval_min` = 5 minutes, up to `batch_size` = 50 proposals) screens pending proposals. Approved ones are materialized into `kg_nodes` / `kg_edges` at tier `machine`; a rejected edge is recorded in `kg_rejections`; an abstention waits for a human.
4. **Review:** Administrators approve, reject or judge proposals in the admin panel's Proposals tab, or through the admin-MCP `list_proposals`, `inspect_proposals` and `review_proposals` tools.
5. **Integration:** A human approval promotes the materialized rows to tier `human` (creating them if the judge had not), and a `new-edge` approval is also written back into the source page's frontmatter. A human rejection retracts anything the judge materialized and records the triple in `kg_rejections`. See [KnowledgeProposals](KnowledgeProposals).

## The RDF/OWL Ontology Layer

Wikantik layers a queryable `wikantik:` RDF/OWL ontology over the KG (module `wikantik-ontology`, backed by Apache Jena + TDB2):

- **T-Box:** `wikantik.ttl` — 9 entity + 5 content classes, 21 KG predicates with domain/range, public mappings to schema.org/SKOS/Dublin Core/PROV-O, a SKOS concept scheme.
- **A-Box:** Postgres→RDF projectors (Entity/Edge/Page/Concept); RDFS `subClassOf` inference via `OntologyModelManager`.
- **Event-incremental sync:** `OntologyEventListener` re-projects a page's graph on save/rename and removes it on true delete; `OntologyEntitySync` re-projects entity graphs on `KgChangeEvent` (coalesced, async — `wikantik.ontology.incremental.enabled`, `wikantik.ontology.incremental.coalesce.ms`). A nightly `OntologyRebuildScheduler` (`wikantik.ontology.rebuild.interval.hours`, default 24, 0 disables) rebuilds the whole TDB2 store from PostgreSQL as a reconciliation backstop; the store (`wikantik.ontology.tdb2.dir`, default `<workDir>/ontology-tdb2`) is a rebuildable cache, and `POST /admin/ontology/rebuild` forces a rebuild.
- **Public read surface:** `/sparql` (read-only SELECT/ASK/CONSTRUCT), `/id/{type}/{id}` (JSON-LD/Turtle dereferencing), `/export/{ontology.ttl,graph.nt}` (dumps). All public with permissive CORS. Restricted pages and their entities are excluded from the materialized dataset — restricted content cannot be queried via SPARQL.

SEO metadata (`<script type="application/ld+json">`) is re-sourced from `NodeTypeMapping.schemaOrgType` so the page JSON-LD `@type` stays in agreement with the ontology's inferred schema.org types (`SemanticHeadOntologyAgreementTest` enforces this).

## Traversal and Agent Surfaces

The KG is exposed through two MCP servers:

- **`/knowledge-mcp`** (21 read-only tools when fully wired; among them): `query_nodes`, `traverse`, `find_similar`, `search_knowledge`, `discover_schema`, `get_node`, `get_ontology`, `sparql_query`, `list_stale_citations`, and `assemble_bundle` (RAG context bundle). Several tools register conditionally on what a deployment has wired, so a leaner install legitimately advertises fewer. Auth: bearer token / API key.
- **`/wikantik-admin-mcp`** (29 tools): admin-bypass mirrors of `query_nodes` and `search_knowledge` so curators see freshly-created entities; `list_orphaned_kg_nodes` for degree-0 entity discovery; proposal intake and review (`propose_knowledge`, `list_proposals`, `inspect_proposals`, `review_proposals`) and KG curation writes (`curate_nodes`, `curate_edges`); plus `rename_cluster` for bulk cluster-path curation, which is not a KG operation.

Human-facing surfaces: the admin panel at `/admin/knowledge-graph` (eight tabs: Proposals, Extraction, Node Explorer, Edge Explorer, Content Embeddings, Hub Proposals, Hub Discovery, LLM Activity), the inclusion-policy dashboard at `/admin/kg-policy`, and the reader route `/knowledge-graph`, a Cytoscape viewer over `GET /api/knowledge/graph?min_tier=` (see [KnowledgeAdminUi](KnowledgeAdminUi)).

### Citation edges and the context bundle

The RAG-as-a-Service context bundle (`GET /api/bundle?q=` / `assemble_bundle` MCP tool) is retrieval over page chunks with version-pinned citations; it does not use the Knowledge Graph to rerank (that stage was removed in 2026-07 after measuring zero lift — see [KnowledgeGraphRerank](KnowledgeGraphRerank) and [HybridRetrieval](HybridRetrieval)). **Citation edges** are a third, derived edge type, distinct from both the Page Graph and the KG: authors write inline `cite://` markup in a page body, it is parsed at save into the `citations` table (version- and span-hash pinned), and a span-level staleness grade drives the self-healing loop — `list_stale_citations` surfaces citations whose target sections have drifted. The same data is served by `GET /admin/drift/citations`.

## Which pages contribute entities (KG inclusion)

**By default a page contributes NO entities to the Knowledge Graph** — inclusion
is **default-exclude**. A page is included only if its **cluster** has an
`include` row in `kg_cluster_policy`, OR its frontmatter sets `kg_include: true`.
A page overrides its cluster either way:

- `kg_include: true` — force the page **in**, regardless of cluster.
- `kg_include: false` — force the page **out**; this **beats** `cluster: include`.

Cluster policy lookup is **segment-aware**: a sub-cluster inherits its parent's
policy by walking ancestors (`a/b/c` → `a/b` → `a`), most specific winning. It
used to be exact-match, which silently excluded every sub-cluster from the KG.
Where a page holds several cluster memberships, resolution is **fail-closed** —
an explicit EXCLUDE on *any* membership wins outright, so adding a second
membership can never quietly pull a page into the graph.

System pages (Sandbox, Main, navigation) are always excluded. Full operator
guide: `KgInclusionPolicy`.

## See Also

- [PageGraphVsKnowledgeGraph](PageGraphVsKnowledgeGraph) — canonical explainer for the two graph subsystems
- [WikantikPlatformHub](WikantikPlatformHub) — platform cluster hub
- [WikantikArchitecture](WikantikArchitecture) — full module breakdown
