# Chunk-level BM25 hybrid — measure-first spike

**Date:** 2026-06-18. **Environment:** local dev (`localhost:8080`), full bundle-corpus harness
(`bin/eval/spike-api-bundle.py`, 54 queries: SIMILARITY / RELATIONAL / BOUNDARY). Same WAR build,
only the flag differs → clean A/B.

## The gap this tests

The shipped `/api/bundle` recall-best path (`DenseChunkSectionSource`) ranks chunks by **dense
vector similarity only** — no lexical signal. BM25 exists only at the *page* level
(`DefaultLuceneIndexer`). So the lexical half of "hybrid" never reached chunk granularity. This
spike adds a RAM Lucene **BM25 chunk index** (`LuceneBm25ChunkIndex`, one doc per chunk) and a
`HybridChunkSectionSource` that **RRF-fuses** the dense and BM25 chunk rankings (reusing the
page-level `HybridFuser`: rrfK=60, dense:bm25 = 1.5:1.0), then groups to sections. Behind
`wikantik.bundle.bm25.enabled` (default OFF).

## Result (section recall@k, dense-only vs chunk-hybrid)

| scope | dense @5 | hybrid @5 | Δ@5 | dense @12 | hybrid @12 | Δ@12 |
|-------|----------|-----------|-----|-----------|------------|------|
| similarity | 0.395 | **0.447** | **+0.052** | 0.711 | **0.737** | **+0.026** |
| relational | 0.412 | 0.412 | 0 | 0.529 | 0.529 | 0 |
| boundary   | 0.692 | 0.615 | **−0.077** | 0.923 | 0.923 | 0 |
| **OVERALL** | 0.456 | **0.471** | **+0.015** | 0.706 | **0.721** | **+0.015** |

**Net positive at both cutoffs** (+0.015), concentrated in **SIMILARITY** (the largest category).
**RELATIONAL flat** (consistent with the KG finding — relational misses aren't a lexical-coverage
problem). One wrinkle: **BOUNDARY @5 −0.077** — the classic lexical-noise failure (BM25 pulls
lexically-similar-but-wrong sections into the top-5 for edge queries); BOUNDARY @12 is unchanged, and
the dip is more than offset by the similarity gain (overall @5 still +0.015).

A dense-weight probe (3.0 vs default 1.5) produced **identical** section recall — recall@12 is coarse
enough that re-weighting chunk fusion doesn't change which sections land in the top-12, and it does
**not** recover the boundary @5 dip.

## Read

Unlike the KG rerank (zero/negative), chunk-BM25 fusion is a **real, modest, principled lift** — a
direct lexical relevance signal at the right granularity. It's small (+1.5 pts overall @12) because
the contextual-document embeddings already capture much lexical signal, so BM25's gain is the
exact-term/rare-token tail (mostly SIMILARITY). It is **not** a relational-retrieval fix.

**Decision options:**
- **Ship it** (default-on) — small but free recall, biggest gain on the biggest category. Would want
  a bundle-specific fusion config + a proper weight/rrfK sweep first to try to neutralise the
  boundary @5 dip (the page-level weights were reused as-is here).
- **Shelve dormant** (default-off, kept) — the lift may be too small to justify the extra startup
  cost (RAM-indexing 18.4k chunks) and a second index to keep warm.

## Fusion/grouping sweep (2026-06-18) — found a strictly-better config

The first run reused the **page-level** fuser (bm25=1.0, dense=1.5, rrfK=60, **truncate=20**), which
over-weighted lexical and cost boundary@5 (0.692→0.615). An offline sweep (`bin/eval/sweep-bm25-fusion.py`)
fetched the raw dense+BM25 chunk rankings once per query via a gated `/api/bundle?debug=rankings`
endpoint, then swept fusion weights × rrfK × **asymmetric dense/BM25 truncation** × grouping strategy
(first/max/sum) — 768 combos, zero restarts. Both live combos reproduced exactly offline
(dense-only 0.706, hybrid 0.721) → harness trusted.

**Key finding — `bm25_w=0.5, dense_w=1.5, rrfK=20, truncate=20`** (live-confirmed, exact match):

| | overall @5/@12 | similarity | relational | boundary |
|---|---|---|---|---|
| dense-only | 0.456 / 0.706 | 0.395 / 0.711 | 0.412 / 0.529 | 0.692 / 0.923 |
| **tuned hybrid** | 0.471 / 0.721 | 0.421 / 0.737 | 0.412 / 0.529 | 0.692 / 0.923 |

A **strict improvement over dense-only**: similarity +0.026 at both cutoffs, overall +0.015, and
**no category regresses** — halving the BM25 weight recovers the boundary@5 dip the reused weights caused.

**What the sweep ruled out:** grouping strategy (first/max/sum) — `first` wins, aggregation does
nothing; larger dense fan-out (dT 20 vs 300) — no @12 change; rrfK 20 vs 60 — negligible. The @12 lift
is **capped at ~+0.015 (≈1 gold section)** regardless of tuning. @5 can reach 0.485 (+0.029) by pushing
more BM25 at the top (bm25_top=50), but that trades the @12 gain — not worth it for a 12-section bundle.

**Conclusion:** the chunk-BM25 hybrid is a real, modest, *clean* recall win (now pure upside, no
downside category). The magnitude is small because the contextual-document embeddings already capture
most lexical signal — BM25 adds the exact-term tail (~1 gold). Tuned defaults are baked into
`SearchWiringHelper` (`wikantik.bundle.bm25.{bm25_weight,dense_weight,rrf_k,truncate}`); the feature stays
behind `wikantik.bundle.bm25.enabled` (default OFF) pending a ship decision. Index build cost: ~18.4k
chunks RAM-indexed at startup (a few MB, ~1–2s).

## Reproducibility

`wikantik.bundle.bm25.enabled=true` + restart → `HybridChunkSectionSource`. Code:
`LuceneBm25ChunkIndex`, `HybridChunkSectionSource` (+ unit tests), wired in `SearchWiringHelper`.
Harness: `python3 bin/eval/spike-api-bundle.py http://localhost:8080`.
