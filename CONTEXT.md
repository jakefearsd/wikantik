# Wikantik

Glossary for the Wikantik wiki engine. This file pins the canonical word for each
project-specific concept so issues, PRs, tests, and code all speak the same language.
It is a glossary, not a spec — definitions say what a thing *is*, never how it is built.

## Agentic access patterns

How external AI agents and RAG pipelines consume the wiki. These three are distinct;
name the one you mean.

**Retrieval-as-a-tool**:
The wiki exposes retrieval and content tools (MCP, `/api`, the for-agent projection)
and the *calling agent* owns its own reasoning loop. The current, mature baseline.
_Avoid_: "the MCP", "tool mode", "agent API" (too vague — say which surface).

**RAG-as-a-Service**:
The *wiki* owns retrieval-and-assembly: a caller sends a question and gets back a
ranked, de-duplicated, citation-bearing [[context-bundle]]. The active goal, built on
top of Retrieval-as-a-tool, not replacing it.
_Avoid_: "RAG endpoint", "the RAG", "answer API".

**Content-as-corpus**:
The wiki as a clean *source* that external pipelines crawl and ingest on their own
(`?format=md`, `/api/changes`, sitemap). Considered good-enough; not a current focus.
_Avoid_: "the export", "crawl mode".

**Context bundle**:
The unit RAG-as-a-Service returns: a ranked, de-duplicated set of evidence pieces, each
carrying a stable citation handle and provenance, assembled for one question.
_Avoid_: "results", "context", "the chunks", "answer payload".

## The three "graph" senses

The bare word "graph" is a code smell — always disambiguate. (See
`docs/wikantik-pages/PageGraphVsKnowledgeGraph.md` and CLAUDE.md.)

**Page Graph**:
Edges are real page-to-page wikilinks parsed from page bodies. Companion structures:
`canonical_id`, `cluster:`/hub membership.
_Avoid_: "the graph", "link graph", "site graph".

**Knowledge Graph**:
Nodes are LLM-extracted entities; edges are co-mention or typed-relation predicates
between them. Lives in `kg_*` tables.
_Avoid_: "the graph", "entity graph" (when you mean the KG as a whole).

**Ontology dataset**:
The RDF/OWL projection of Page Graph + Knowledge Graph into Jena (T-Box + per-resource
named graphs), queried via SPARQL. A *projection*, downstream of the other two. Serves
two consumers: the [[knowledge-base]] (humans) and [[linked-data-publishing]] (machines).
_Avoid_: "the graph", "the RDF", "the triplestore" (when you mean the modelled dataset).

## Knowledge base & the two senses of "semantic web"

"Semantic Web" is overloaded — it names two consumers with opposite priorities. Say which.

**Knowledge Base**:
The curated, human-facing view of the [[knowledge-graph]] (+ ontology) that people browse,
query, and trust as an interface. A first-class product, justified by human value
independent of any retrieval lift it provides.
_Avoid_: "the KG" (that's the data), "the graph", "the ontology" (that's the projection).

**Linked Data publishing**:
Exposing the [[ontology-dataset]] to the open web — crawlers, reasoners, federation
(`owl:sameAs`, VoID/DCAT, crawler-facing `/sparql`). A subordinate, compromise-where-forced
concern, not a first-class goal.
_Avoid_: "the semantic web" (too broad — say which), "the SPARQL stuff", "linked data" alone.

## Ingestion & content

**Derived page**:
A first-class wiki page whose body is *generated* by extracting a source document (PDF,
office/text doc). The original binary is retained as an attachment on that page and the page
records `derived_from` provenance. The retained source is the durable source-of-truth; the
body is a *regenerable projection* — re-running improved extraction reflows it. Rides every
page rail (search, embeddings, Knowledge Graph, ontology, curation).
_Avoid_: "imported page", "converted doc", "attachment page".

**Source document**:
The original uploaded binary a [[derived-page]] is extracted from, retained as an attachment
for human download/viewing and for re-extraction. The provenance anchor, never silently
discarded.
_Avoid_: "the original", "the file", "the upload".

## Evaluation

**Evaluation corpus**:
The frozen, versioned, checked-in set of questions paired with section-level *gold passages*
(deliberately spanning similarity, relational/multi-hop, and boundary-straddling cases) that
gates merges on [[context-bundle]] quality. The single source of truth for "is retrieval
good." Expected to be levelled up over time — appending questions/golds and ratcheting
thresholds is a cheap data edit, not a code change.
_Avoid_: "the test set", "the sample project", "retrieval-queries.csv", "the 40 queries".

**Gold passage**:
The section-level span an [[evaluation-corpus]] question is expected to be answered from.
Section-level (not chunk-level) so it stays valid across re-chunking and re-extraction.
_Avoid_: "the answer", "the relevant chunk", "ground truth" (alone).

## Citations & grounding

**Citation handle**:
What a [[context-bundle]] evidence piece carries so a claim can be traced back: target
`canonical_id`, page *version*, section heading-path, the verbatim span, and a content hash
of that span. Version-pinned, so staleness is *detectable* rather than silent.
_Avoid_: "the source", "the link", "ref".

**Ephemeral bundle citation**:
A [[citation-handle]] on an evidence piece in a *query response*. Transient — regenerated
every query, never persisted, never a curation task.
_Avoid_: "citation" (alone — ambiguous with the persisted kind).

**Citation edge**:
A *persisted*, version-pinned, verifiable reference embedded in one page's content that
grounds a claim against a specific version + section of another page. A distinct edge type —
**not** a wikilink ([[page-graph]]) and not an [[ephemeral-bundle-citation]].
_Avoid_: "link", "wikilink", "reference" (alone), "citation" (alone).

**Stale citation**:
A [[citation-edge]] whose *cited span* has changed since it was pinned (detected via the
span content hash) — **not** mere page-version drift, which in a rapidly agent-edited base is
near-constant and meaningless. A *patient* curation task (re-ground or re-pin), never a
save-time error and never alarming — in a highly dynamic base, churn is the steady state.
Surfaced per page two ways: **outbound** (stale citations this page makes) and **inbound**
(citations others make to a span of this page that has since changed).
_Avoid_: "broken link", "dead reference", "drift" (that's the ontology term).
