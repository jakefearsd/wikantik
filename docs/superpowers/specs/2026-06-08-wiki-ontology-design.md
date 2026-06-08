# Wiki Ontology — Design Spec

**Date:** 2026-06-08
**Status:** Design approved; awaiting spec review → implementation plan
**Topic:** Apply a formal ontology to Wikantik's wiki concepts (pages, clusters, tags) and Knowledge Graph entities, expressed in RDF, serving external interoperability *and* agent retrieval as two faces of one shared semantic model.

---

## 1. Goal & Framing

Wikantik already has *partial, ad-hoc* ontology structure but no actual ontology:

- **KG relationship types** are a fully closed 21-term vocabulary (`RelationshipTypeVocabulary`, DB CHECK) — and already contain a latent taxonomy (`is_a` / `instance_of` / `generalizes`, `part_of` / `contains`) — but have **no domain/range constraints**.
- **KG entity types (`node_type`)** are essentially **free-text** (format-validated only), and the *two* extractors (chunk vs. page) use **inconsistent** vocabularies (page extractor enforces 7 types; chunk extractor free-texts, defaulting to `Concept`).
- **Page `type:`** is a small closed Java enum (`PageType`: hub/article/reference/runbook/design) — not DB-constrained.
- **`cluster` / `tags`** are free-text with no hierarchy.
- The old typed page-to-page `page_relations` table was **deliberately dropped** (V023) — the Page Graph is now strictly real wikilinks.
- **No RDF/OWL/SKOS** anywhere; schema.org JSON-LD exists but is **SEO-only** (`SemanticHeadRenderer`).

So this work is partly *formalizing what is latent* (the 21 predicates, the type enums) and partly *net-new* (a closed entity-class hierarchy, domain/range, a SKOS concept scheme, an RDF store + SPARQL endpoint).

### Decisions (locked during brainstorming)

1. **Purpose.** RDF + interoperability focused, with **improved agent retrieval as a co-equal critical deliverable** — interop and agent interaction are two faces of one shared semantic model. Richer navigation/SEO are *opportunistic* side effects and must **never** interfere with the core agentic/interop goals.
2. **Storage.** Postgres remains the source of truth (`kg_nodes`/`kg_edges`/`page_canonical_ids`/frontmatter, pgvector). A **materialized Apache Jena (TDB2) model**, synced from Postgres, backs a **live SPARQL endpoint** with `rdfs:subClassOf` inference.
3. **Vocabulary.** **Reuse-first hybrid** — align to public ontologies (schema.org, SKOS, Dublin Core Terms, PROV-O) wherever the fit is clean; mint a **minimal `wikantik:` namespace** only for predicates/classes with no public equivalent, each tied back via `rdfs:subPropertyOf` / `skos:closeMatch` / `owl:equivalentClass`. **Be aggressive about standard-compliance**, including reshaping existing content to fit public vocabularies where reasonable.
4. **Agent retrieval.** **Both** — (a) make the *existing* hybrid retriever ontology-aware (class filter, `subClassOf` + SKOS expansion) so every current MCP call improves with no agent changes, *and* (b) expose new read-only MCP tools (`get_ontology` + `sparql_query`) over the same endpoint external clients use.
5. **Sync engine.** **Materialized model, event-incremental + periodic full-rebuild backstop** (Approach A). Reuses the existing `WikiEvent` system; full control over inference; all Apache-2.0.

### Constraints

- **Licensing:** new dependencies must be license-clean for this Apache-2.0 project. Apache Jena is Apache-2.0 → acceptable. RDF4J (EDL/EPL) and other copyleft-encumbered options are avoided. Heavy dependencies require a *clear* need where lighter/existing choices are clearly insufficient.
- **SEO subordinate:** SEO/navigation improvements are opportunistic and gated; they must not block or regress the core deliverables.
- **Repo conventions:** TDD (failing test first); full IT reactor (`mvn clean install -Pintegration-tests -fae`, sequential — **no `-T`**) before any prod-code commit; MCP-touching changes pair a Mockito unit test with a Cargo wire-level IT; one-time data backfills go in one-shot scripts, **not** `Vxxx` migrations; the Page Graph stays strictly wikilinks (do **not** resurrect `page_relations`).

---

## 2. The Ontology Model (T-Box)

### 2.1 IRI scheme (rename-stable, built on `canonical_id`)

| Thing | IRI pattern | Prefix |
|---|---|---|
| Ontology terms (classes/properties) | `https://wiki.wikantik.com/ns/wikantik#{Term}` | `wk:` |
| Page instances | `https://wiki.wikantik.com/id/page/{canonical_id}` | — |
| KG entity instances | `https://wiki.wikantik.com/id/entity/{node_id}` | — |
| Tag/cluster concepts | `https://wiki.wikantik.com/id/concept/{slug}` | — |

Page IRIs key on the **rename-stable ULID `canonical_id`**, not the slug. The human URL `https://wiki.wikantik.com/wiki/{slug}` is carried as `schema:url`, so a rename only rewrites that one triple and every inbound edge IRI stays valid.

Reused namespaces: `schema:` (schema.org), `skos:`, `dct:` (Dublin Core Terms), `prov:` (PROV-O), `rdfs:`, `owl:`, `rdf:`.

### 2.2 Class hierarchy

**Content classes** (rooted at `schema:CreativeWork`; subsumes today's SEO JSON-LD; map 1:1 onto `PageType`):

```
wk:Page ⊑ schema:CreativeWork
  ├ wk:Hub        ⊑ schema:CollectionPage   (matches current hub JSON-LD)
  ├ wk:Article    ⊑ schema:Article
  ├ wk:Reference  ⊑ schema:CreativeWork
  ├ wk:Runbook    ⊑ schema:HowTo            (clean schema.org fit)
  └ wk:DesignDoc  ⊑ schema:TechArticle
```

**Entity classes** (rooted at `schema:Thing`) — **harmonizes the two divergent extractors into one set**:

```
wk:Entity ⊑ schema:Thing
  ├ wk:Person       ≡ schema:Person
  ├ wk:Organization ≡ schema:Organization
  ├ wk:Place        ≡ schema:Place
  ├ wk:Event        ≡ schema:Event
  ├ wk:Product      ⊑ schema:Product
  ├ wk:Technology   ⊑ schema:Thing  ; skos:closeMatch dbo:Technology
  ├ wk:Concept      ⊑ schema:Thing  ; skos:closeMatch skos:Concept (distinct class; see §7)
  ├ wk:Project      ⊑ schema:Project
  └ wk:Version      ⊑ schema:Thing  (no clean public match → custom)
```

Unified entity vocabulary = **{Person, Organization, Place, Event, Product, Technology, Concept, Project, Version}** — reconciles the chunk extractor's `Project`/`Version` with the page extractor's `Technology`. An entity's `node_type` becomes `rdf:type` against these classes, so `subClassOf` inference fires (query `schema:Thing` → all Technologies, etc.).

### 2.3 The 21 predicates → `wk:` object properties (with domain/range + public mappings)

Be aggressive about mapping to public terms; keep custom only where no reasonable public fit exists.

- **Structural / relational, mapped to public terms:**
  - `part_of` / `contains` → `wk:partOf` / `wk:contains` ⊑ `dct:isPartOf` / `dct:hasPart`
  - `related_to` → `wk:relatedTo` ⊑ `skos:related`
  - `alternative_to` / `contrasts_with` / `compatible_with` → `wk:*` ⊑ `skos:related`
  - `replaces` → `wk:replaces` ⊑ `dct:replaces`
  - `located_in` → `wk:locatedIn` ⊑ `schema:containedInPlace` (range `wk:Place`)
- **Taxonomic triad** (`is_a`, `instance_of`, `generalizes`) — **SKOS-style** (approved option (a)): map to `skos:broader` / `skos:narrower` / `skos:exactMatch` among entity concepts. Reasoner-safe; drives SKOS query expansion. Type-level subsumption still works via `rdf:type` + the class tree. (Rejected alternative: OWL punning — semantically "purer" but complicates the reasoner and risks surprising inferences.)
- **Remaining technical predicates** (`enables`, `requires`, `uses`, `produces`, `precedes`, `extends`, `implements`, `mitigates`, `defines`, `applies_to`) → custom `wk:` object properties, each given `rdfs:domain` / `rdfs:range` from the unified classes. No clean public equivalent — keeping them custom preserves the semantic precision a public-only mapping would have flattened.

**Domain/range are declared two ways:**
- In the T-Box, for **inference + documentation** (RDFS domain/range *infers* subject/object types).
- As **SHACL shapes** (`jena-shacl`), for actual **validation** of proposed KG edges at curation time (e.g. "`implements` must go `wk:Technology` → `wk:Concept`"). RDFS domain/range alone does not reject bad data; SHACL is the enforcement mechanism.

### 2.4 SKOS concept scheme (tags + clusters)

`wk:WikiConcepts a skos:ConceptScheme`. Each **tag** and **cluster** → `skos:Concept` (`skos:inScheme wk:WikiConcepts`); the existing `parent/sub` sub-cluster convention → `skos:broader`. Pages link to concepts via `dct:subject`. This powers SKOS broader/narrower **query expansion** in retrieval and feeds navigation/SEO for free.

### 2.5 PROV-O provenance

Each extraction run → `prov:Activity`; KG nodes/edges → `prov:wasGeneratedBy` it + `prov:wasDerivedFrom` the source page IRI; authorship → `prov:wasAttributedTo` (the `agents` service account vs. a human). The existing pending/accepted curation status rides along. Makes "human-authored vs. LLM-extracted" a first-class, queryable distinction.

---

## 3. Architecture & Sync Engine

### 3.1 New module: `wikantik-ontology`

Depends on `wikantik-api`, `wikantik-knowledge` (KG access), `wikantik-main` (PageManager + events), `wikantik-event`. New dependencies — **all Apache-2.0**:

- `jena-core` + `jena-arq` (model + SPARQL execution)
- `jena-tdb2` (persistent triple store for the materialized A-Box)
- `jena-shacl` (domain/range validation shapes)

**Deliberately *not* `jena-fuseki`** — Fuseki is a full server with its own UI/auth; we need only query *execution*. A thin servlet over `jena-arq` behind existing auth filters is the "lighter unless clearly insufficient" call.

### 3.2 Components

```
wikantik-ontology
├─ resources/ontology/
│    ├─ wikantik.ttl     ← T-Box: classes, 21 props, domain/range, public mappings, SKOS scheme
│    └─ shapes.ttl       ← SHACL: enforce domain/range on proposed KG edges
├─ OntologyModelManager  ← WikiEngine-registered manager; owns the Jena TDB2 Dataset,
│                           loads T-Box, wraps it in an RDFS inference model, serves queries
├─ projection/
│    ├─ PageProjector     ← page+frontmatter → triples (rdf:type wk:Article, dct:*, schema:url, prov, dct:subject)
│    ├─ EntityProjector   ← kg_nodes → entity individuals (rdf:type wk:Class, rdfs:label, props)
│    ├─ EdgeProjector     ← kg_edges → wk: object properties + prov
│    └─ ConceptProjector  ← distinct tags/clusters → skos:Concept + skos:broader
├─ sync/
│    ├─ OntologyEventListener  ← WikiEvent (PAGE_SAVED/DELETED/RENAMED) + KG node/edge events
│    └─ OntologyRebuildService ← full re-projection; startup-if-empty / nightly / admin-triggered
└─ web/  (wired in wikantik-war; PUBLIC read-only, ACL-filtered projection — no auth filter)
     ├─ /sparql            ← read-only SELECT/CONSTRUCT/ASK (timeout + result cap, no UPDATE)
     ├─ /id/{type}/{id}    ← content negotiation → JSON-LD / Turtle per resource
     └─ /export/*.ttl|.nt  ← T-Box + full A-Box dumps for ingestion
```

### 3.3 Key architectural decisions

1. **Named-graph-per-resource.** Each page/entity/concept gets its own named graph whose IRI *is* the resource IRI. Incremental update = "drop graph G, re-project G" — atomic, no orphaned triples, trivially correct.
2. **Sync = events + backstop.** `OntologyEventListener` re-projects exactly the affected named graph on each save/KG change (near-real-time). `OntologyRebuildService` does a full rebuild on startup-if-empty, on a nightly schedule, and via `POST /admin/ontology/rebuild` (mirrors `/admin/content/rebuild-indexes`). The rebuild is the self-healing guarantee against missed events.
3. **Inference at the T-Box level.** Jena's RDFS reasoner wraps the TDB2 graph for query-time `subClassOf`/`subPropertyOf` entailment. The T-Box is tiny, so query-time inference is cheap and always consistent — no separate materialization step to stale out.
4. **Rename-safe by construction.** IRIs key on `canonical_id`; a rename rewrites only the `schema:url` triple.
5. **Public, read-only, ACL-filtered.** `/sparql`, `/id/*`, and `/export/*` require **no auth** (maximizing interop reach, like the existing `/wiki/{slug}?format=md` and `/api/changes` feeds) — but "public" does **not** mean "ignores ACLs." A SPARQL endpoint is a far more powerful query surface than those feeds, so the projection enforces a **public/restricted split**: only ACL-public pages (and the KG nodes/edges derived from them, honoring the existing KG access policy / `GraphRoleClassifier` `restricted` role) land in the **public dataset** the endpoints serve. Restricted content is projected into separate named graphs that the public endpoints never query — so ACLs cannot be bypassed by query construction. Guardrails are *more* important when public: read-only (reject `UPDATE`/`LOAD`/`DROP`), query timeout, result-row cap, and (TBD on need) rate-limiting. Permissive CORS on these read-only endpoints for browser-based semantic clients. These are servlet/API endpoints (registered in `web.xml`), **not** SPA routes — no `SpaRoutingFilter` involvement. *(Reversible: an auth filter can be added later if a reason emerges; making them public first is not a one-way door.)*
6. **Module-cycle guard.** The consumed read API (`OntologyQueryService`) lives in `wikantik-api`, implemented in `wikantik-ontology`, injected into `wikantik-knowledge` at runtime — so `wikantik-ontology → wikantik-knowledge` (projection reads the KG) has no return edge.

---

## 4. Agent Retrieval, New MCP Tools & Displacement

### 4.1 Prong 1 — existing hybrid retriever becomes ontology-aware (no tool-signature changes)

Today's pipeline is BM25 + dense + KG rerank. Insert an `OntologyQueryExpander` (consulting `OntologyModelManager` via the `wikantik-api` interface) so every current `search_knowledge`/`query_nodes`/`traverse` call silently improves:

- **Query → class/concept mapping:** match query terms against class `rdfs:label` + concept `skos:prefLabel`/`altLabel`, and reuse the *existing dense embeddings* to find nearest concepts (no new model).
- **Expansion:** `subClassOf` (query "algorithm" → include instances of subclasses via the inference model) and `skos:broader/narrower` (query "retirement" → fold in narrower tag concepts).
- **Class boost in rerank:** results whose `rdf:type` is (a subclass of) the inferred query class get boosted; the KG rerank step graduates from raw co-mention to **typed-relation proximity + class match**.
- **Domain/range-coherent traversal:** `traverse` respects domain/range so multi-hop stays semantically sound.

### 4.2 Prong 2 — two new read-only knowledge-mcp tools (16 → 18)

- **`get_ontology`** — returns the formal T-Box: class tree (with `subClassOf` + public mappings), object properties (domain/range + descriptions), and the SKOS scheme summary. Complements the existing `discover_schema` (*empirical* — live distinct `node_type`s); `get_ontology` is *normative* — the schema agents should plan against. Document the split rather than merge.
- **`sparql_query`** — read-only `SELECT`/`CONSTRUCT`/`ASK` against the *same* Jena endpoint external clients use. Guardrails: reject `UPDATE`/`LOAD`/`DROP`, query timeout, result-row cap. Returns standard SPARQL-JSON.

Both are read-only → they belong in the `knowledge-mcp` server (`KnowledgeMcpInitializer`); the live tool count moves 16 → 18, so CLAUDE.md / README / CHANGELOG + the tool-surface memory get the bump.

### 4.3 Displacement / re-homing of existing constructs

| Existing | What happens | Risk posture |
|---|---|---|
| `RelationshipTypeVocabulary` (21 predicates) | Stays runtime source of truth; T-Box **mirrors** it + adds domain/range + SHACL. Drift-guard test asserts T-Box props ≡ `CLOSED_VOCAB`. | Low — additive |
| `node_type` (free-text, 2 divergent extractors) | Constrained to the unified 9-class set from the ontology; both extractors harmonized. **Extraction behavior change** → Phase 5, TDD + MCP unit+IT pairing. | Medium — behavior change |
| `PageType` enum | Mapped 1:1 to `wk:` content classes; enum kept; enum↔class binding test. | Low — no behavior change |
| `discover_schema` tool | Kept; `get_ontology` complements it (empirical vs normative). | Low |
| schema.org JSON-LD (`SemanticHeadRenderer`) | **Phase 6, opportunistic, gated:** eventually re-sourced as a projection of the page's ontology graph (one model → SEO + interop). Until then they coexist; a test asserts they *agree*. Never blocks core. | Deferred — SEO-isolated |
| `page_relations` (dropped V023) | **Not** resurrected. Page Graph stays strictly wikilinks; typed semantics live only in KG/ontology. | Guardrail — do not re-add |
| `tags`/`cluster` (free-text) | Values preserved; gain a `skos:Concept` layer + `skos:broader` from the sub-cluster convention (normalization pass). | Low — additive |

---

## 5. Phasing & Sequencing

Each phase is independently shippable and verifiable. Dependency spine: **0 → 1 → 2 → 3 → 4**; Phase 5 needs only 0–1; Phase 6 is last and optional.

| Phase | Ships | Verification gate |
|---|---|---|
| **0 — Ontology authoring + content normalization** | `wikantik.ttl`, `shapes.ttl`, IRI scheme. One-shot script migrating free-text `node_type`s + tags onto the standardized vocabulary (one-shot, **not** a `Vxxx` migration). No runtime wiring. | T-Box parses + consistent; SHACL valid; drift guards green (props ≡ `RelationshipTypeVocabulary`, classes ≡ `PageType`). |
| **1 — Module + materialization** | `wikantik-ontology` + Jena deps; `OntologyModelManager` (TDB2 + RDFS inference); 4 projectors → named graphs; `OntologyRebuildService` (full rebuild, startup-if-empty, `/admin/ontology/rebuild`). No live sync, no public endpoint. | Golden-triple projector unit tests; full rebuild == expected graph; `subClassOf` inference fires. |
| **2 — Incremental sync** | `OntologyEventListener` (save/delete/rename + KG node/edge events) → per-resource named-graph re-projection; nightly rebuild backstop. | Event → graph replaced; delete → graph removed; **full rebuild == sum of incrementals**. |
| **3 — Interop endpoints** | `/sparql` (public, read-only + guardrails), `/id/{type}/{id}` content negotiation (JSON-LD/Turtle), `/export/*.ttl\|.nt` dumps; public/restricted projection split; CORS; `web.xml` wiring. | Cargo IT: unauthenticated SELECT works, `UPDATE` rejected, timeout/cap enforced, dump reloads into a clean Jena model, **and a restricted (ACL-protected) page + its KG nodes are absent from public SPARQL/JSON-LD/dump**. |
| **4 — Agent retrieval (critical deliverable)** | `get_ontology` + `sparql_query` tools (knowledge-mcp 16→18); `OntologyQueryExpander` in the hybrid retriever (subClassOf + SKOS expansion, class boost, coherent traversal) via the `wikantik-api` interface. Docs/memory tool-count bump. | MCP unit + wire-level IT; **retrieval-quality CI shows demonstrable lift, no regression** vs `retrieval-queries.csv`. |
| **5 — Extraction harmonization + curation** | Both extractors unified onto the 9-class vocabulary; SHACL gate on proposed KG edges at acceptance; optional admin-mcp `list_ontology_violations`. | Extractor tests emit only ontology classes; SHACL rejects bad edges; admin unit + IT if MCP touched. |
| **6 — Opportunistic SEO/nav (gated)** | `SemanticHeadRenderer` JSON-LD re-sourced from the ontology graph; SKOS-driven nav. | Regression test: SEO output unchanged-or-better. Never blocks earlier phases; deferrable indefinitely. |

**Cross-cutting rules (every prod-code phase):** TDD (failing test first); full IT reactor `mvn clean install -Pintegration-tests -fae` (sequential, no `-T`) before any commit; MCP-touching changes pair a Mockito unit test with a Cargo IT.

**Earliest value:** interop (SPARQL + RDF dumps) at Phase 3; agent-retrieval deliverable at Phase 4. Phases 0–3 are hard prerequisites for 4 — ontology-aware retrieval needs the model, the inference, and (for the agent SPARQL tool) the endpoint to exist first.

---

## 6. Testing Strategy (TDD throughout)

- **T-Box:** parses + consistent (every `wk:` prop has domain+range; no dangling refs; SHACL valid). Drift guards: props ≡ `RelationshipTypeVocabulary`; content classes ≡ `PageType`.
- **Projectors:** golden-triple unit tests (known Postgres rows → expected triples) per projector.
- **Inference:** `subClassOf` entailment fires (query `schema:Thing` returns a `wk:Technology` individual).
- **SHACL:** a domain/range-violating edge fails; a valid one passes.
- **Sync:** event → named graph replaced; delete → graph removed; full rebuild == sum of incrementals.
- **SPARQL endpoint:** Cargo IT — authed SELECT works, `UPDATE` rejected, timeout/cap enforced.
- **MCP tools:** Mockito unit **+** wire-level Cargo IT (per the MCP pairing rule), even though read-only.
- **Retrieval lift:** extend the retrieval-quality CI / `RetrievalExperimentHarness` with an ontology-aware on/off comparison — demonstrates and guards the agent-retrieval deliverable against the `retrieval-queries.csv` ground truth.

---

## 7. Decisions Recorded / Risks

- **`wk:Concept` vs `skos:Concept` — RESOLVED: distinct classes + bridge convention.** Extracted KG Concept entities stay a distinct `wk:Concept` class (a `schema:Thing` individual that participates in domain/range-constrained typed relations); tags/clusters stay `skos:Concept` in `wk:WikiConcepts`. Aggressive consolidation was rejected because it (1) violates the codebase's Page-Graph-vs-Knowledge-Graph non-conflation invariant, (2) mixes SKOS's indexing layer with the assertional/individual layer, (3) forces SHACL carve-outs (tags would become illegal endpoints of `wk:enables`/etc.), (4) smears the human-vs-LLM PROV-O provenance this design makes first-class, (5) coarsens retrieval by blurring the entity-level signal into the topic-level one, and (6) breaks KG tooling that assumes entities (embeddings, co-mention, proposal lifecycle). **Bridge convention:** where an extracted concept and a curated tag genuinely co-denote, link them with `skos:closeMatch`/`skos:exactMatch` — consumers get the unified view on demand without conflation, and it stays reversible (mappings can be added/removed without re-minting URIs).
- **ACL leakage on public RDF — must be enforced, not assumed.** Because the read-only RDF endpoints are public (§3.3.5), the public/restricted projection split is a hard security requirement, not a nicety: a SPARQL endpoint can reconstruct content via query, so restricted pages and their derived KG nodes/edges must be physically absent from the public dataset (separate named graphs, never queried by the public endpoints). This is the one place where getting it wrong silently exposes ACL-protected content — covered by the Phase 3 IT gate.
- **Eventual-consistency window.** Between a write and its event-driven re-projection, SPARQL/JSON-LD may lag by seconds. Acceptable for interop + retrieval; the nightly rebuild bounds drift. Document it on the endpoints.
- **Inference cost at scale.** Query-time RDFS inference is cheap for a tiny T-Box but should be benchmarked once the A-Box is large; fall back to materialized inference only if measured need appears.
- **Content normalization blast radius.** Migrating free-text `node_type`s onto 9 classes may drop or remap entities; the one-shot script must report a before/after diff and be re-runnable/idempotent.
