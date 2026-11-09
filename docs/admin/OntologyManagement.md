# Ontology Management on Wikantik — Why and How

This guide is for curators, agent operators and developers who manage the ontology layer. Wikantik is a wiki with an ontology layered over it: the prose pages are also a
machine-readable, queryable model of *which pages exist, what type they are, and how
they are filed* (clusters and tags). This guide explains **why** that exists and
**how** to manage it day to day.

> **The ontology is page- and concept-only.** The `wikantik:` model used to carry a second
> layer — LLM-extracted *entities* (9 entity classes) and typed *relations* among them
> (21 predicates, SHACL edge shapes, `/id/entity/*`). The Knowledge Graph that fed it was
> retired and deleted; the entity vocabulary left the T-Box with it. Triples for entities
> that older stores still hold are purged by the next full rebuild.

> Terminology note: "ontology" means the `wikantik:` RDF/OWL model — the classes and
> instances projected from the wiki. It is distinct from the **Page Graph**
> (page-to-page wikilink edges). See
> [PageGraphVsKnowledgeGraph.md](../wikantik-pages/PageGraphVsKnowledgeGraph.md).

---

## Why an ontology over a wiki?

- **Interoperability / linked data.** Pages, clusters and tags are projected into RDF with
  stable, dereferenceable IRIs and public mappings to **schema.org** and **SKOS**. The
  model is queryable over **SPARQL** and exportable as Turtle/N-Triples, so the wiki is
  consumable by any RDF tool — not just its own UI.
- **Agent-grade access.** `knowledge-mcp` exposes `get_ontology` and `sparql_query`, so an
  agent can ask exact structured questions ("which runbooks are in this cluster") instead
  of grepping text; the hybrid retriever can also expand queries through the concept
  hierarchy (flag `wikantik.search.ontologyExpansion.enabled`, default off).
- **SEO that can't drift.** A page's schema.org `@type` and `sameAs` are re-sourced from
  the ontology mapping (`NodeTypeMapping.schemaOrgType`), so the public structured data and
  the internal model are two faces of one truth (`SemanticHeadOntologyAgreementTest`).

---

## The model

| Layer | What it is | Where it lives | Who changes it |
|---|---|---|---|
| **T-Box (vocabulary)** | The 6 page classes — `wk:Page` and its subclasses `Hub`, `Article`, `Reference`, `Runbook`, `DesignDoc` — each aligned to a schema.org type, plus the `wk:WikiConcepts` SKOS concept scheme. No object properties. | `wikantik-ontology/src/main/resources/ontology/wikantik.ttl`; `NodeTypeMapping` (code) | **Developers** (edit Turtle/Java) |
| **Page concepts (A-Box)** | Each page as a typed resource: its `wk:` content class, IRI, title/summary, and links to its tag/cluster **SKOS** concepts | Page **frontmatter**; projected by `PageProjector` + `ConceptProjector` | **Authors & agents** (the structured frontmatter editor) |

An unrecognised or missing page `type` projects as `wk:Article` (the same default the SEO
renderer uses for `schema:Article`).

---

## How to manage page concepts (Layer 2)

This is what most curation looks like, and it happens in the **structured
frontmatter editor** — the form beside the Markdown body when you edit a page.

> **Full field reference:** see **[Frontmatter.md](../user/Frontmatter.md)** for every
> supported frontmatter key, the editor's live-validation / Save-gating model, and
> the runbook block. This section covers only the *ontology-relevant* fields.

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
[2026-06-08-structured-page-curation-design.md](../superpowers/specs/2026-06-08-structured-page-curation-design.md).

---

## Consuming the ontology (the read surface)

The materialized model is queryable and dereferenceable, read-only and public (behind an
ACL split so restricted pages never materialize publicly):

- **`/sparql`** (GET or POST) — read-only `SELECT` / `ASK` / `CONSTRUCT` (updates
  rejected; result cap + timeout).
- **`GET /id/{type}/{id}`** — per-resource dereferencing as JSON-LD or Turtle. Supported
  types: `page` (`/id/page/{canonical_id}`) and `concept` (`/id/concept/{slug}`). Any other
  type, including the retired `entity`, is a 404.
- **`GET /export/ontology.ttl`** and **`/export/graph.nt`** — full dumps.
- **`knowledge-mcp`** exposes **`get_ontology`** (the formal T-Box) and **`sparql_query`**
  (read-only SPARQL) to agents.

Example — list the runbooks in a cluster:

```sparql
PREFIX wk:   <https://wiki.wikantik.com/ns/wikantik#>
PREFIX dct:  <http://purl.org/dc/terms/>
SELECT ?page ?title WHERE {
  ?page a wk:Runbook ; dct:title ?title ; dct:subject <https://wiki.wikantik.com/id/concept/agentic-ai> .
}
```

---

## How it stays in sync (the materialization pipeline)

The RDF model is **projected from PostgreSQL and page frontmatter into a Jena TDB2 store**
with RDFS `subClassOf` inference:

- **Projectors** turn pages into RDF: `PageProjector` (page concepts) and
  `ConceptProjector` (tag/cluster SKOS).
- **Event-incremental sync.** `OntologyEventListener` re-projects a page's graph (and its
  concept graphs) on save/rename and removes it on a true delete (rename vs delete is
  disambiguated via `canonical_id` liveness); writes are coalesced and run on a worker
  thread. The nightly `OntologyRebuildScheduler` is a reconciliation backstop.
- **Manual rebuild & status:** `POST /admin/ontology/rebuild`, `GET /admin/ontology/status`;
  the store also rebuilds on startup if empty.

Config: `wikantik.ontology.enabled` (default `true`), `wikantik.ontology.tdb2.dir` (blank
by default, which means `<wikantik.workDir>/ontology-tdb2`),
`wikantik.ontology.rebuild.interval.hours` (default `24`, `0` = disabled),
`wikantik.ontology.compaction.interval.hours` (default `168`, `0` = disabled; the TDB2
store only grows between compactions, so keep this on), and
`wikantik.ontology.incremental.coalesce.ms` (default `500`). Full design:
[`docs/superpowers/specs/2026-06-08-wiki-ontology-design.md`](../superpowers/specs/2026-06-08-wiki-ontology-design.md)
(historical for the entity/SHACL parts, which were retired).

---

## Evolving the vocabulary

A new page class is a **developer task**, not a wiki edit:

1. Add the class to `wikantik.ttl` (subclass of `wk:Page`, aligned to a schema.org type).
2. Add the `type` -> class entry to `NodeTypeMapping` (class mapping and, if it should
   differ from the `Article` default, the schema.org mapping) and the matching enum value
   to the frontmatter schema.
3. Extend the T-Box tests (`ClassHierarchyTest`) and `SemanticHeadOntologyAgreementTest`.

The page-concept enums (`type`, `status`) are **curated-open**: new values are tolerated
(with a stern, suggestion-bearing warning) until the corpus is normalized, then escalatable
to hard errors via `wikantik.frontmatter.enum.nonCanonical.severity`.

---

## Measuring drift (the burn-down dashboard)

Advisory warnings only matter if someone can see them in aggregate. The **drift
dashboard** (`/admin/drift`) makes vocabulary drift measurable:

- A **corpus-wide sweep** runs the same schema validator over every page and persists one count per
  `(family, code, severity)` — family `frontmatter` for field warnings. (The former
  `shacl` family covered the retired Knowledge Graph's edge shapes; `shaclChecked` stays in
  the sweep payload for compatibility and is always `false`.) Sweeps run automatically after each nightly ontology rebuild,
  or on demand via the dashboard's *Run sweep now* (`POST /admin/drift/sweep`).
- The dashboard shows the latest counts with **deltas vs. the previous sweep**, a
  per-code **trend sparkline**, and a live **per-code page list** — each offender
  linked to the editor with the validator's suggested fix.
- This is the evidence for the escalation lever: when a code's count reaches zero
  and stays there, it is safe to ratchet that check from warning to error
  (`wikantik.frontmatter.enum.nonCanonical.severity`).

Endpoints: `GET /admin/drift/summary`, `GET /admin/drift/trend?days=N`,
`GET /admin/drift/pages?family=F&code=C`, `GET /admin/drift/status` (live sweep
progress for the dashboard's progress bar), `POST /admin/drift/sweep`.

---

## Quick reference

| Purpose | Endpoint / tool |
|---|---|
| Field schema for the editor | `GET /api/frontmatter-schema` |
| Dry-run frontmatter validation | `POST /api/frontmatter/validate` |
| Ontology rebuild + status | `/admin/ontology/*` |
| Drift burn-down (sweep + counts) | `/admin/drift/*` |
| Public SPARQL | `/sparql` (GET or POST) |
| Resource dereferencing | `GET /id/{page|concept}/{id}` (JSON-LD / Turtle) |
| RDF dumps | `GET /export/ontology.ttl`, `/export/graph.nt` |
| Agent tools | `knowledge-mcp` (`get_ontology`, `sparql_query`) |

**Where the code lives**

- T-Box / projectors / SPARQL guard: `wikantik-ontology`; runtime wiring and incremental
  sync: `wikantik-main` (`com.wikantik.ontology.runtime`)
- Structured editor + frontmatter schema/validator: `wikantik-api`
  (`com.wikantik.api.frontmatter.schema`), `wikantik-main`
  (`com.wikantik.frontmatter.schema`), `wikantik-frontend`
- REST surfaces: `wikantik-rest`

**Related docs**

- [Frontmatter.md](../user/Frontmatter.md) — the full frontmatter field reference + the structured editor
- [PageGraphVsKnowledgeGraph.md](../wikantik-pages/PageGraphVsKnowledgeGraph.md) — Page Graph vs Knowledge Graph
- [wiki-ontology-design](../superpowers/specs/2026-06-08-wiki-ontology-design.md) — the RDF/OWL layer design (historical for entities/SHACL)
- [structured-page-curation-design](../superpowers/specs/2026-06-08-structured-page-curation-design.md) — the editor + curation surfaces
