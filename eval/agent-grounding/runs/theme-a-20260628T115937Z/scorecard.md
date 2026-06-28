# Grounded-agent eval scorecard

Run: `theme-a-20260628T115937Z` | lexical(BM25-forced): False | N questions: 16

| Arm | N | Mean correctness (0-2) | Citation hit rate |
|-----|---|------------------------|-------------------|
| cold | 16 | 0.062 | 0% |
| grounded_bundle | 16 | 1.500 | 81% |
| grounded_mcp | 16 | 1.625 | 81% |

**grounded_bundle − cold delta:** +1.438

**grounded_mcp − cold delta:** +1.563

_Small-N directional result; not a statistical claim._
_Citation-hit rates are per-arm heuristics (bundle = retrieval slugs; mcp/cold = the model's Sources: line) and are NOT directly comparable across arms; the correctness column is the comparable headline. Errored rows score correctness 0._

## Per-question

| qid | cold | bundle | mcp |
|---|---|---|---|
| agent-grade-runbook-frontmatter | 0 | 1 | 1 |
| bundle-vs-synthesis | 0 | 2 | 2 |
| canonical-id-rename | 0 | 1 | 2 |
| chunker-heading-fidelity | 0 | 1 | 1 |
| contextual-embeddings-prefix | 0 | 2 | 2 |
| dense-backend-options | 0 | 0 | 2 |
| entity-class-count | 0 | 2 | 2 |
| kg-inclusion-default | 0 | 1 | 1 |
| kg-predicates-count | 0 | 2 | 1 |
| kg-rerank-default | 0 | 2 | 2 |
| mcp-tool-counts | 0 | 1 | 1 |
| ontology-shacl-gate | 0 | 2 | 2 |
| page-vs-knowledge-graph | 0 | 2 | 2 |
| read-path-acl | 0 | 1 | 2 |
| scim-group-admin-restriction | 1 | 2 | 1 |
| stale-citation-grading | 0 | 2 | 2 |
