---
hubs:
- WikantikPlatformHub
- WikantikDevelopment
date: '2026-06-20'
status: active
summary: How Wikantik's LLM-extracted Knowledge Graph works — nodes, edges, provenance,
  the proposal workflow, ontology layer, and agent MCP surfaces.
tags:
- knowledge-graph
- llm-extraction
- ontology
- mcp
- retrieval
- architecture
type: article
cluster: wikantik-platform
canonical_id: 01KQTCBW5GBFJVWYB8V1CP49P5
title: The Wikantik Knowledge Graph
---
# The Wikantik Knowledge Graph

The Knowledge Graph (KG) is the semantic layer of Wikantik. It transforms Markdown pages into a rich, queryable network of typed entities and relationships that both humans and AI agents can traverse — distinct from the Page Graph, which tracks real wikilinks.

> **Page Graph vs Knowledge Graph:** The Page Graph records wikilink edges between pages. The Knowledge Graph records LLM-extracted entities and relations. Never conflate them. See [PageGraphVsKnowledgeGraph](PageGraphVsKnowledgeGraph) for the full explainer.

## Core Concepts

### Nodes and Edges

The KG is stored in PostgreSQL (`kg_*` tables). Nodes represent typed entities extracted from page content — each carrying a `source_page`, a `node_type` from the 9-class vocabulary (person, organization, place, event, product, technology, concept, project, version), and provenance metadata. Edges represent typed relations between entities (e.g. `implements`, `located_in`).

A **write-time SHACL gate** (`OntologyShaclValidator`) enforces shape constraints on KG edges at the two write chokepoints: the admin-MCP curation path refuses a non-conformant edge citing the violated shape; the machine-extraction path skips and logs it. The gate is currently narrow (the two shaped predicates are `implements` and `located_in`) and null-safe.

### The 9-Class Entity Vocabulary

Both the chunk-level and page-level extractors emit only the canonical `EntityTypeVocabulary.ENTITY_CLASSES`: **person, organization, place, event, product, technology, concept, project, version**. Prompts are generated from this vocabulary; parsers lowercase and allowlist to it (default: `concept`). An `EntityTypeVocabularyDriftTest` keeps the vocabulary in sync with `NodeTypeMapping`.

### KG Inclusion Policy

The KG operates under a **cluster-primary default-exclude policy**: only pages in clusters explicitly marked `kg_include: true` (via frontmatter override) or enabled via `bin/kg-policy.sh` are extracted into the KG. See [KgInclusionPolicy](KgInclusionPolicy) for the full policy reference.

### Provenance Model

Trust is tracked via provenance: `human-authored` (curator-added), `ai-inferred` (proposals not yet reviewed), or `ai-reviewed` (approved and integrated). Human curators review proposals in the admin panel at `/admin/knowledge-graph/*`.

## The Proposal Workflow

The KG uses a **human-in-the-loop AI enrichment** cycle:

1. **Extraction:** The `wikantik-extract-cli` module runs offline batch extraction (LLM via Ollama — model `gemma4-graph:12b`, with `think: false` to suppress reasoning traces that break structured JSON). The page-level and chunk-level extractors both emit entities from the 9-class vocabulary.
2. **Proposal:** Extracted entities and edges are submitted to the `proposals` table.
3. **Review:** Administrators approve, modify, or reject proposals via the admin panel.
4. **Integration:** Approved proposals become `human-authored` KG edges, and the curator can surface them via the admin MCP.

## The RDF/OWL Ontology Layer

Wikantik layers a queryable `wikantik:` RDF/OWL ontology over the KG (module `wikantik-ontology`, backed by Apache Jena + TDB2):

- **T-Box:** `wikantik.ttl` — 9 entity + 5 content classes, 21 KG predicates with domain/range, public mappings to schema.org/SKOS/Dublin Core/PROV-O, a SKOS concept scheme.
- **A-Box:** Postgres→RDF projectors (Entity/Edge/Page/Concept); RDFS `subClassOf` inference via `OntologyModelManager`.
- **Event-incremental sync:** `OntologyEventListener` re-projects a page's graph on save/rename and removes it on true delete. A nightly `OntologyRebuildScheduler` reconciles entity graphs.
- **Public read surface:** `/sparql` (read-only SELECT/ASK/CONSTRUCT), `/id/{type}/{id}` (JSON-LD/Turtle dereferencing), `/export/{ontology.ttl,graph.nt}` (dumps). All public with permissive CORS. Restricted pages and their entities are excluded from the materialized dataset — restricted content cannot be queried via SPARQL.

SEO metadata (`<script type="application/ld+json">`) is re-sourced from `NodeTypeMapping.schemaOrgType` so the page JSON-LD `@type` stays in agreement with the ontology's inferred schema.org types (`SemanticHeadOntologyAgreementTest` enforces this).

## Traversal and Agent Surfaces

The KG is exposed through two MCP servers:

- **`/knowledge-mcp`** (19–20 read-only tools): `query_nodes`, `traverse`, `find_similar`, `search_knowledge`, `discover_schema`, `get_node`, `get_ontology`, `sparql_query`, `list_stale_citations`, and `assemble_bundle` (RAG context bundle). Auth: bearer token / API key.
- **`/wikantik-admin-mcp`** (26 tools): admin-bypass mirrors of `query_nodes` and `search_knowledge` so curators see freshly-created entities; `list_orphaned_kg_nodes` for degree-0 entity discovery; proposal review and KG curation writes.

### RAG-as-a-Service and Stale Citation Self-Healing

The KG participates in the RAG-as-a-Service context bundle (`GET /api/bundle?q=` / `assemble_bundle` MCP tool): retrieved sections carry version-pinned citation edges into the `citations` table, and a span-level staleness score drives the self-healing loop — `list_stale_citations` surfaces citations whose grounding sections have drifted. The drift burn-down dashboard is at `/admin/drift/citations`.

## See Also

- [PageGraphVsKnowledgeGraph](PageGraphVsKnowledgeGraph) — canonical explainer for the two graph subsystems
- [WikantikPlatformHub](WikantikPlatformHub) — platform cluster hub
- [WikantikArchitecture](WikantikArchitecture) — full module breakdown
