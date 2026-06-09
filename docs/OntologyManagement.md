# Ontology Management on Wikantik — Why and How

Wikantik is a wiki with an ontology layered over it: the prose pages are also a
machine-readable, queryable knowledge model. This guide explains **why** that
exists and **how** to manage it day to day — as a human curator, as an AI agent,
and as a developer evolving the vocabulary itself.

> Terminology note: throughout, "ontology" means the `wikantik:` RDF/OWL model —
> the classes, properties, and instances projected from the wiki. It is distinct
> from the **Page Graph** (page-to-page wikilink edges). See
> [PageGraphVsKnowledgeGraph.md](wikantik-pages/PageGraphVsKnowledgeGraph.md) — read
> it first if those two terms blur together.

---

## Why an ontology over a wiki?

A plain wiki stores prose. Wikantik additionally maintains a formal model of
*what the pages are about*, because that unlocks things prose alone cannot:

- **Interoperability / linked data.** Pages, clusters, tags, and extracted
  entities are projected into RDF with stable, dereferenceable IRIs and public
  mappings to **schema.org**, **SKOS**, **Dublin Core**, and **PROV-O**. The
  model is queryable over **SPARQL** and exportable as Turtle/N-Triples, so the
  wiki is consumable by any RDF tool — not just its own UI.
- **Agent-grade retrieval.** Agents don't read a wiki the way people do. The
  ontology powers Knowledge-Graph-aware rerank, ontology-aware query expansion,
  structured per-page projections, and the `knowledge-mcp` tool surface — so an
  agent can traverse typed relations and ask "what implements X" instead of
  grepping text.
- **Shared human + AI curation.** Humans and AI agents both create and refine
  the model through surfaces that read the **same** schema and the **same**
  validation rules. The ontology evolves under shared control rather than
  drifting between a human convention and an agent's guesses.
- **Governance & trust.** Every entity/edge carries **provenance**
  (human-authored, human-curated, AI-inferred, AI-reviewed); structurally
  invalid relations are refused at write time by a **SHACL** gate; and
  administrative changes are recorded in a tamper-evident
  [audit log](AuditLog.md).
- **SEO that can't drift.** A page's schema.org `@type` and `sameAs` are
  re-sourced from the ontology, so the public structured data and the internal
  model are two faces of one truth.

---

## The model: three layers

It helps to see the ontology as three layers, because each is *managed
differently* and *by different people*.

| Layer | What it is | Where it lives | Who manages it |
|-------|-----------|----------------|----------------|
| **1. T-Box (vocabulary)** | The class & property *definitions* — 9 entity classes, 5 content classes, 21 relation predicates, their domain/range, the schema.org/SKOS/DC/PROV mappings, and the SHACL shapes | `wikantik-ontology/src/main/resources/ontology/wikantik.ttl` + `shapes.ttl`; `EntityTypeVocabulary`, `RelationshipTypeVocabulary`, `NodeTypeMapping` (code) | **Developers** (edit Turtle/Java) |
| **2. Page concepts (A-Box)** | Each page as a typed concept: its `wk:` content class, IRI, title/summary, and its tag/cluster **SKOS** concepts + provenance | Page **frontmatter**; projected by `PageProjector` + `ConceptProjector` | **Authors & agents** (the structured frontmatter editor) |
| **3. Knowledge Graph (A-Box)** | The *entities* mentioned across the wiki (people, technologies, concepts, …) and the typed **relations** among them | `kg_nodes` / `kg_edges`; projected by `EntityProjector` + `EdgeProjector` | **Extractors + curators** (the Knowledge tab, admin panel, MCP tools) |

**The vocabulary (Layer 1)** is the closed set everything else conforms to:

- **9 entity classes:** `person, organization, place, event, product,
  technology, concept, project, version`.
- **5 content classes:** `Hub, Article, Runbook, DesignDoc, Reference` (mapped
  from a page's `type`).
- **21 relation predicates:** `related_to, part_of, contains, is_a,
  instance_of, generalizes, requires, enables, uses, produces, replaces,
  precedes, extends, implements, alternative_to, contrasts_with,
  compatible_with, mitigates, defines, applies_to, located_in`.

You **author** Layers 2 and 3; you **don't author** Layer 1 from the wiki — you
create *instances that conform to it*. Changing the vocabulary is a code change
(see [Evolving the vocabulary](#evolving-the-vocabulary-layer-1) below).

---

## How to manage page concepts (Layer 2)

This is what most curation looks like, and it happens in the **structured
frontmatter editor** — the form beside the Markdown body when you edit a page.

- **Set the page's type.** `type` (article / hub / reference / runbook / design)
  maps to the page's `wk:` content class and its schema.org `@type`.
- **Cluster & tags become SKOS concepts.** `cluster` and each `tag` are
  projected as `skos:Concept`s the page links to via `dct:subject`; sub-clusters
  (`parent/sub`) become `skos:broader` relations. This is how the topical
  hierarchy is modeled.
- **Provenance.** `verified_at` / `verified_by` / `audience` feed the page's
  trust/confidence and who-it's-for signals.
- **`canonical_id`** is the rename-stable ULID that becomes the page's ontology
  IRI (`/id/page/{canonical_id}`). It's system-managed — read-only in the editor.

The editor renders every field from a **server-authoritative schema**
(`GET /api/frontmatter-schema`) and validates against the same rules the server
enforces on save:

- **Errors block the save (HTTP 422)**, surfaced inline on the offending field.
  Only genuinely-corrupting problems are errors (e.g. malformed YAML).
- **Warnings are advisory (HTTP 200)** and carry a suggested fix. Non-canonical
  `type`/`status`, non-kebab `cluster`, and odd `date` formats warn (with a
  one-click suggestion) but **do not block** — the corpus has legitimate drift,
  and the model is meant to *converge under shared control*, not break existing
  pages on edit. A dry-run endpoint (`POST /api/frontmatter/validate`) powers the
  Raw-YAML break-glass tab.

AI agents writing via the admin MCP `update_page` / `write_pages` tools hit the
**same** validator and receive the **same** suggestion-bearing messages, so they
can self-correct. See
[2026-06-08-structured-page-curation-design.md](superpowers/specs/2026-06-08-structured-page-curation-design.md).

---

## How to manage the Knowledge Graph (Layer 3)

Layer-3 entities and relations come from two sources: **automatic extraction**
and **deliberate curation**.

### Where entities come from
The entity extractors (chunk-level and page-level) read page content and emit
candidate entities (one of the 9 classes) and relations into `kg_nodes` /
`kg_edges`, stamped `AI_INFERRED`. This is the bulk-population path.

### Curating from the page editor (the Knowledge tab)
When you edit a page, the **Knowledge** tab (beside **Frontmatter**) shows the
entities mentioned on *that* page and the relations among them, and lets you:

- confirm an AI-inferred entity (promotes its provenance),
- correct an entity's type (the 9-class vocabulary),
- add or remove an intra-page relation (a predicate from the 21),
- remove a spurious entity.

Writes go through `/api/page-knowledge/*`, gated by your **edit** permission on
the page, and route through the same curation facade as everything else — so the
SHACL gate, provenance stamping, and audit log all apply. A relation that
violates a shape is refused inline (HTTP 422) with the reason.

> **Curation only surfaces on KG-included pages.** Inclusion is cluster-primary
> and **default-exclude**; use `kg_include: true` in frontmatter to opt a page
> in. An entity curated on an excluded page won't appear in the slice. See
> [KgInclusionPolicy.md](KgInclusionPolicy.md).

### Curating at scale (admin + agents)
- **Admin panel:** `/admin/knowledge-graph/*` — list/query nodes & edges,
  upsert, delete, merge duplicates, and review proposals.
- **AI agents:** the admin MCP `curate_nodes` / `curate_edges` tools (and the
  read-only `knowledge-mcp` retrieval tools) operate through the *same*
  `KgCurationOps` chokepoint — there is no path that bypasses the SHACL gate or
  provenance.

### The SHACL write-time gate
Relations are validated against the SHACL **shapes** before they're written. The
gate is deliberately narrow today — `implements` requires a `technology` subject
and a `concept` object; `located_in` requires a `place` object — and a
non-conformant edge is refused (REST/MCP) or skipped-and-logged (the machine
materialization path), citing the violated shape. Predicates without a shape are
unconstrained. This is how the vocabulary's rules are enforced as the A-Box
grows under both human and AI hands.

---

## Consuming the ontology (the read surface)

The materialized model is queryable and dereferenceable, read-only and public
(behind an ACL split so restricted pages/entities never materialize publicly):

- **`GET /sparql`** — read-only `SELECT` / `ASK` / `CONSTRUCT` (updates
  rejected; result cap + timeout).
- **`GET /id/{type}/{id}`** — per-resource dereferencing as JSON-LD or Turtle
  (e.g. `/id/page/{canonical_id}`, `/id/entity/{uuid}`).
- **`GET /export/ontology.ttl`** and **`/export/graph.nt`** — full dumps.
- **`knowledge-mcp`** exposes **`get_ontology`** (the formal T-Box) and
  **`sparql_query`** (read-only SPARQL) to agents, plus ontology-aware query
  expansion in the hybrid retriever (flag `wikantik.search.ontologyExpansion.enabled`,
  default off).

---

## How it stays in sync (the materialization pipeline)

The RDF model is **projected from PostgreSQL into a Jena TDB2 store** with RDFS
`subClassOf` inference:

- **Projectors** turn rows into RDF: `PageProjector` (page concepts),
  `ConceptProjector` (tag/cluster SKOS), `EntityProjector` + `EdgeProjector`
  (KG entities/relations).
- **Event-incremental sync.** `OntologyEventListener` re-projects a page's graph
  (and its concept graphs) on save/rename and removes it on a true delete
  (rename vs delete is disambiguated via `canonical_id` liveness). A nightly
  `OntologyRebuildScheduler` reconciles **entity** graphs (no KG-change events
  exist).
- **Manual rebuild & status:** `/admin/ontology/rebuild`, `/admin/ontology/*`
  (status + SHACL-conformance violations); the store also rebuilds on startup if
  empty.

Config: `wikantik.ontology.enabled` (default `true`),
`wikantik.ontology.tdb2.dir` (default `${wikantik.workDir}/ontology-tdb2`),
`wikantik.ontology.rebuild.interval.hours` (default `24`, `0` = disabled). Full
design: [`docs/superpowers/specs/2026-06-08-wiki-ontology-design.md`](superpowers/specs/2026-06-08-wiki-ontology-design.md).

---

## Evolving the vocabulary (Layer 1)

When you genuinely need a new class, predicate, or constraint — a **developer
task**, not a wiki edit:

1. **Add/adjust the T-Box** in `wikantik.ttl` (the class/property, its
   `rdfs:domain`/`rdfs:range`, and any `rdfs:subClassOf` mapping to
   schema.org/SKOS/DC/PROV). A class entry reads like:

   ```turtle
   wk:Technology  rdfs:subClassOf  wk:Entity ;
                  rdfs:subClassOf  schema:SoftwareApplication .

   wk:implements  a            owl:ObjectProperty ;
                  rdfs:domain  wk:Technology ;
                  rdfs:range   wk:Concept .
   ```

2. **Update the code vocabularies** so the extractors, validators, and UI agree:
   `EntityTypeVocabulary` (the 9 entity classes), `RelationshipTypeVocabulary`
   (the 21 predicates), and `NodeTypeMapping` (free-text type → `wk:` class +
   schema.org type). Drift guards (e.g. `EntityTypeVocabularyDriftTest`) tie
   these together — keep them green.
3. **Tighten constraints** by adding a `shapes.ttl` SHACL shape, which the
   write-time gate will then enforce.
4. **Surface the new value** in the UIs that hardcode the vocab (the structured
   editor's enums, the Knowledge panel's type/predicate pickers).

The page-concept enums (`type`, `status`) are **curated-open**: new values are
tolerated (with a stern, suggestion-bearing warning) until the corpus is
normalized, then escalatable to hard errors via
`wikantik.frontmatter.enum.nonCanonical.severity`. This is the lever for evolving
the page vocabulary gradually under shared human/AI control.

---

## Measuring drift (the burn-down dashboard)

Advisory warnings only matter if someone can see them in aggregate. The **drift
dashboard** (`/admin/drift`) makes vocabulary drift measurable:

- A **corpus-wide sweep** runs the same schema validator over every page (plus the
  SHACL conformance check over the materialized ontology) and persists one count per
  `(family, code, severity)` — family `frontmatter` for field warnings, `shacl` for
  non-conformant edges. Sweeps run automatically after each nightly ontology rebuild,
  or on demand via the dashboard's *Run sweep now* (`POST /admin/drift/sweep`).
- The dashboard shows the latest counts with **deltas vs. the previous sweep**, a
  per-code **trend sparkline**, and a live **per-code page list** — each offender
  linked to the editor with the validator's suggested fix.
- This is the evidence for the escalation lever: when a code's count reaches zero
  and stays there, it is safe to ratchet that check from warning to error
  (`wikantik.frontmatter.enum.nonCanonical.severity`).

Endpoints: `GET /admin/drift/summary`, `GET /admin/drift/trend?days=N`,
`GET /admin/drift/pages?family=F&code=C`, `POST /admin/drift/sweep`.

---

## Quick reference

**Surfaces**

| Purpose | Endpoint / tool |
|---|---|
| Field schema for the editor | `GET /api/frontmatter-schema` |
| Dry-run frontmatter validation | `POST /api/frontmatter/validate` |
| Page-scoped KG read + curation | `/api/page-knowledge/{name}` (view read, edit-gated writes) |
| KG admin (nodes/edges/proposals) | `/admin/knowledge-graph/*` |
| Ontology rebuild + status | `/admin/ontology/*` |
| Drift burn-down (sweep + counts) | `/admin/drift/*` |
| Public SPARQL | `GET /sparql` |
| Resource dereferencing | `GET /id/{type}/{id}` (JSON-LD / Turtle) |
| RDF dumps | `GET /export/ontology.ttl`, `/export/graph.nt` |
| Agent tools | `knowledge-mcp` (`get_ontology`, `sparql_query`, retrieval); admin MCP (`curate_nodes`, `curate_edges`) |

**Where the code lives**

- Vocabulary / T-Box / projectors / SHACL: `wikantik-ontology`
- KG service, extractors, curation, inclusion policy: `wikantik-main`
  (`com.wikantik.knowledge.*`), `kg_*` tables
- Structured editor + frontmatter schema/validator: `wikantik-api`
  (`com.wikantik.api.frontmatter.schema`), `wikantik-main`
  (`com.wikantik.frontmatter.schema`), `wikantik-frontend`
- REST surfaces: `wikantik-rest`

**Related docs**

- [PageGraphVsKnowledgeGraph.md](wikantik-pages/PageGraphVsKnowledgeGraph.md) — Page Graph vs Knowledge Graph
- [KgInclusionPolicy.md](KgInclusionPolicy.md) — which pages' entities are in the KG
- [KnowledgeGraphRerank.md](KnowledgeGraphRerank.md) — KG-aware retrieval rerank
- [wiki-ontology-design](superpowers/specs/2026-06-08-wiki-ontology-design.md) — the RDF/OWL layer design
- [structured-page-curation-design](superpowers/specs/2026-06-08-structured-page-curation-design.md) — the editor + curation surfaces
