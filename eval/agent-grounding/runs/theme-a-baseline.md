# Theme A — Corpus-Gap Baseline Audit

**Date:** 2026-06-28  
**Inference host:** UP (`inference.jakefear.com:11434`)  
**MCP access:** WORKED — all `read_page` and `assemble_bundle` calls returned real content  

---

## Gap Baseline Table

| # | Gap | Live page slug | query used | Live page has fact? | Bundle surfaces fact? | Classification |
|---|-----|---------------|-----------|--------------------|-----------------------|----------------|
| 1 | Chunker fix | HybridRetrieval | What chunker bug was fixed to improve section recall and what config controls fragment merging? | NO | NO | missing-content |
| 2 | Contextual embeddings | HybridRetrieval | What text prefix is prepended to sections when building dense embeddings and why? | NO | NO | missing-content |
| 3 | Dense backends | HybridRetrieval | What are the three dense index backend options and which is the production default? | YES | YES | already-works |
| 4 | Rerank default | KnowledgeGraphRerank | Does hybrid search use the Knowledge Graph to rerank results by default? | NO (wrong content) | NO (wrong content) | missing-content |
| 5 | KG predicates | WikantikKnowledgeGraph | How many KG predicates are in the wikantik.ttl T-Box and what external vocabularies do they map to? | YES | YES | already-works |
| 6 | KG inclusion default | KgInclusionPolicy | Which pages contribute entities to the Knowledge Graph by default and how does a page override it? | YES | NO | not-retrievable |

---

## Per-Gap Notes

### Gap 1 — Chunker fix (missing-content)

**Target page:** `HybridRetrieval`

The `HybridRetrieval` page mentions `EmbeddingIndexService` in its Wiring section but contains **no description of the heading-boundary force-emit bug fix** (`ContentChunker` force-emitting the merge-forward buffer at each heading boundary) and **no mention of** the config keys `wikantik.chunker.fragment_floor_tokens` or `wikantik.chunker.overlap_tokens`. The page was last modified 2026-06-07, predating the 2026-06-14 recall-lever work.

Bundle result: Top sections come from `RetrievalExperimentHarness` (rank 1–7) describing the *earlier* 2026-04-19 chunker fix (atomic list chunking + heading-path prepend) and the `merge_forward_tokens` config. This is a different fix than the heading-boundary force-emit bug. The current `fragment_floor_tokens`=24 config is absent from the bundle entirely.

**Action needed (Task 2):** Add a "Chunker improvements" section to `HybridRetrieval` covering the heading-boundary fix and the `wikantik.chunker.fragment_floor_tokens`/`overlap_tokens` config keys.

---

### Gap 2 — Contextual embeddings (missing-content)

**Target page:** `HybridRetrieval`

The `HybridRetrieval` page's Wiring section mentions `EmbeddingIndexService` only at a component-name level. It contains **no description of the `EmbeddingTextBuilder.forDocument` prefix format**: `"Page: {title} | Cluster: {cluster} | Section: {heading}"` + frontmatter summary prepended before embedding. There is no mention of *why* this prefix improves recall (document-level context disambiguation).

Bundle result: Rank 1 is `TextAnalysisWithDataScience` about general dense vector theory — wrong page entirely. Rank 8 is `RetrievalExperimentHarness` describing the *older* heading-path-only prefix (`"<Top> > <Mid> > <Leaf>\n\n<body>"`). The current production format with page title + cluster + section + summary is not present in any returned section.

**Action needed (Task 3):** Add a "Contextual embeddings" subsection to `HybridRetrieval` describing the prefix format and the recall improvement rationale.

---

### Gap 3 — Dense backends (already-works)

**Target page:** `HybridRetrieval`

The page contains a complete "Dense backend selection" table listing all three options (`inmemory`, `pgvector`, `lucene-hnsw`) with descriptions. It explicitly states `lucene-hnsw` is "the docker1 production default." Bundle rank 1 is the "Dense backend selection" section from `HybridRetrieval` — exact match.

**Minor note:** The config block header shows `wikantik.search.dense.backend = inmemory` (the property-file default), while `lucene-hnsw` is described as the docker1 default in the section below. An agent could misread the code block as authoritative for production. The page is clear enough on close reading, but the visual weight of the config block may mislead.

**No content action needed.** May be worth a prose clarifier but not a blocking gap.

---

### Gap 4 — Rerank default (missing-content, stale/wrong content)

**Target page:** `KnowledgeGraphRerank`

The page **actively contradicts the correct fact.** It shows:
```properties
jspwiki.search.graphRerank.enabled = true
```
…and describes KG rerank as "the final stage of the Wikantik retrieval pipeline" that is on by default. This is stale/wrong. Per CLAUDE.md (2026-06-16 measurement): KG rerank was shelved — `boost=0` default, never wired; ceiling spike measured ZERO net lift. The `jspwiki.*` property namespace is also the old (pre-rename) namespace.

Bundle result: The bundle surfaces sections from `KnowledgeGraphRerank` (rank 1–3, 7) describing the reranker as active and enabled — all wrong. `WikantikSearchAndRetrieval` (rank 6) and `WikiSearchOptimization` (rank 7) also describe graph rerank as active. Only `ChoosingARetrievalMode` (rank 5) gives a neutral description without claiming it's enabled. No section correctly states that KG rerank ships with `boost=0` and is not wired.

**Action needed (Task 4):** Rewrite `KnowledgeGraphRerank` to reflect shelved status: boost=0, default OFF, zero-lift measurement result, old `jspwiki.*` namespace corrected to `wikantik.*`.

---

### Gap 5 — KG predicates (already-works)

**Target page:** `WikantikKnowledgeGraph`

The page's "RDF/OWL Ontology Layer" section states: "**T-Box:** `wikantik.ttl` — 9 entity + 5 content classes, **21 KG predicates** with domain/range, public mappings to **schema.org/SKOS/Dublin Core/PROV-O**, a SKOS concept scheme." Bundle rank 1 is this exact section.

**No action needed.**

---

### Gap 6 — KG inclusion default (not-retrievable)

**Target page:** `KgInclusionPolicy`

The page contains the correct, detailed fact: the cluster-primary default-exclude policy, the 4-step decision model, and the frontmatter override mechanism (`kg_include: true/false`). The content is correct and complete.

Bundle result: All 12 sections returned are from `PageGraphVsKnowledgeGraph` (ranks 1, 3, 4, 6, 7, 8, 10, 11), `WikantikKnowledgeGraph` (rank 2), `News` (rank 5), and `ProposingKnowledgeGraphEdges` (ranks 9, 12). **`KgInclusionPolicy` did not appear in the bundle at all.** The `News` page (rank 5) has a passing mention of "defaulting to exclude" but no actionable detail on the decision model or override mechanism. `WikantikKnowledgeGraph`'s "KG Inclusion Policy" paragraph mentions "cluster-primary default-exclude" but defers to `KgInclusionPolicy`.

Root cause hypothesis: `KgInclusionPolicy` uses runbook frontmatter (`type: runbook`, `audience: humans`) and may be KG-policy-excluded or semantically distant from entity/KG vocabulary queries. The dense embedding for this page's chunks may not align with the query vocabulary ("which pages contribute entities," "override").

**Action needed (Task 5):** Improve retrievability — either add a summary section to `WikantikKnowledgeGraph` that inlines the core policy facts (default-exclude, `kg_include:` override), or add cluster/summary metadata to `KgInclusionPolicy` to improve dense alignment.

---

## MCP Probe Results

- `read_page` (admin): WORKED on all 4 pages — returned full page bodies with content hashes and versions
- `assemble_bundle` (knowledge): WORKED — returned 12 ranked, cited sections per query with real content from the live corpus
- Inference host check: UP
- All subsequent tasks can proceed.
