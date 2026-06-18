# Adjacent Lucene levers — efSearch + code-aware analyzer (2026-06-19)

Follow-on to the chunk-BM25 hybrid ship. Two Lucene features evaluated against the bundle harness.

## 1. HNSW `efSearch` (dense ANN fidelity) — no headroom, ruled out

Local runs `inmemory` (exact brute-force) = the recall ceiling; prod runs `lucene-hnsw` (ANN). Dense-only
recall, isolating the lever:

| dense backend | overall @5 | overall @12 |
|---|---|---|
| inmemory (exact ceiling) | 0.456 | 0.706 |
| lucene-hnsw, efSearch=100 | 0.456 | 0.706 |
| lucene-hnsw, efSearch=600 | 0.456 | 0.706 |

Bit-identical. The HNSW ANN (m=16, ef_construction=64) is **lossless at the bundle's operating point** —
prod is not losing recall to approximation. Mechanically the bundle requests `denseTopK=300` and the index
uses `fetch=max(k, efSearch)`, so the beam is already ≥300 regardless of `efSearch`. **Dead lever.**

## 2. Code-aware analyzer (WordDelimiter: camelCase + snake split) — real, but regime-conflicted

**Corpus gap:** the standard 54-query corpus is all natural-language — zero identifier/code-symbol queries.
Built a 13-query identifier corpus (`eval/bundle-corpus/queries-identifiers.csv`): developer queries naming
code symbols / config keys, golds verified against the sections that contain them.

**Chunk-level proof the analyzer works** (gold-section rank in each ranking, top-300):

| query | dense rank | bm25 StandardAnalyzer | bm25 **code** analyzer |
|---|---|---|---|
| e09 "add conditional edges" → `add_conditional_edges` | 26 | miss | **0** |
| e01 "allowed CORS origins" → config key section | 53 | weak | **3** |
| e03 "default KG inclusion policy property" | 160 | weak | **4** |

StandardAnalyzer keeps `ActorSystem`/`add_conditional_edges` as single tokens, so spaced queries miss; the
code analyzer splits them and BM25 finds the gold at rank 0–4.

**But the fusion suppresses it.** Recall@12 by config:

| config | identifier corpus | natural corpus |
|---|---|---|
| dense-only | 0.538 | 0.706 |
| standard hybrid (shipped: bm25=0.5, trunc=20) | 0.538 | **0.721** |
| code analyzer, dense-heavy fusion | 0.538 | 0.706 |
| code analyzer, **BM25-heavy** (bm25=2.0, dense=1.0, trunc=100) | **0.692** | 0.353 |

The shipped dense-heavy fusion (bm25=0.5, truncate=20) — tuned so BM25 is a *minor reorder* for
natural-language — can't *inject* a dense-cold section even when BM25 ranks it #0: past truncate-20 dense
contributes nothing and BM25's 0.5 weight can't outrank dense's top-20. Only a **BM25-heavy** fusion
surfaces the identifier golds (+0.154 @12) — and that **same config collapses** natural-language
(0.721 → 0.353, similarity 0.737 → 0.290).

## Conclusion

The two query regimes need **mirror-image, mutually-exclusive** fusion configs:
- **Natural-language** (dominant traffic): dense-heavy, StandardAnalyzer → 0.721.
- **Identifier/code-lookup**: BM25-heavy + code analyzer → 0.692 (vs 0.538).

A single global config cannot serve both. So the code-aware analyzer is a **real, proven lever** but only
realizable via **query-adaptive routing** — cheaply detect identifier-ish queries (dotted config key /
camelCase / snake_case token) and route them to a BM25-heavy + code-analyzer path; everything else stays on
the dense-heavy default. That's a feature (a query classifier + a second fusion profile), not a knob.

**Decision deferred to a product question:** does real agent traffic include a meaningful share of
code-symbol / config-lookup queries? If yes, query-adaptive routing is worth building. If traffic looks like
the natural-language corpus, the shipped dense-heavy hybrid is correct and analyzer tuning is moot.

**Shipped infra (kept, default-inert):** `wikantik.bundle.bm25.analyzer` (default `standard`;
`code` = WordDelimiterGraphFilter) + the already-configurable `wikantik.bundle.bm25.{bm25_weight,
dense_weight,rrf_k,truncate}`. Harness: `bin/eval/measure-corpus.py <base> <corpus.csv>` (any corpus),
identifier corpus + the `?debug=rankings` chunk-rank diagnostic.
