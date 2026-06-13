# The Knowledge Graph is a first-class Knowledge Base and a retrieval signal; RDF is its projection

We treat the LLM-extracted Knowledge Graph as a first-class, **human-facing Knowledge
Base** — something people browse, query, and trust as an interface — justified by that human
value *independent of any retrieval lift* it provides, and *also* as a retrieval signal for
the context bundle (relational / multi-hop retrieval and entity-resolution-driven
de-duplication — not similarity retrieval, which dense vectors own).

The RDF/SPARQL ontology dataset is a **projection** of the Knowledge Graph (plus Page
Graph), not a separately-built artifact — so "invest in the semantic web" means "extract and
curate the Knowledge Graph well." Exposing that dataset to the open web as **Linked Data**
(`owl:sameAs` to external KBs, VoID/DCAT, federation, crawler-facing SPARQL) is explicitly
**subordinate** — pursued only where forced — because our primary customer is agentic RAG,
not linked-data crawlers.

Considered and rejected: *shelving the KG because graph rerank didn't beat vectors.* That
verdict came from a rigged trial — a weak extraction LLM, rerank `boost = 0` (off since a
2026-05-11 diagnostic), ~7% mention coverage, and a similarity-only eval with no relational
questions — so the KG has never been fairly tested. It earns or loses its retrieval role on
a fair relational-question trial; its Knowledge-Base role stands regardless.
