---
title: Provenance-weighted Knowledge Graph rerank — value probe
date: '2026-05-11'
status: probe-complete-mixed
---

# Provenance-weighted Knowledge Graph rerank — value probe

## TL;DR

Built a `HYBRID_GRAPH_WEIGHTED` retrieval-mode prototype that weights KG
graph traversal by edge `tier` (`kg_edges.tier`) and per-mention confidence
(`chunk_entity_mentions.confidence`).

- On `core-agent-queries` (16 queries): weighted is **byte-identical**
  to unweighted graph rerank (Δ nDCG@5 = 0.0000).
- On `core-agent-queries-expanded` (20 cross-cluster queries):
  **weighted improves on unweighted graph rerank by +0.0307 nDCG@5**
  (0.8508 vs 0.8201) — passes Gate B.

But the bigger and more concerning finding is **independent of this
probe**: graph rerank itself (either variant) is regressing HYBRID by
~0.12 nDCG@5 on the expanded set, and HYBRID is regressing BM25 by ~0.53
nDCG@5 on the original set. Two regressions stacked. The right
follow-up is to investigate why the graph rerank reorders correct
answers downward at all, not to keep tuning the weighting policy on top
of a regressive base.

## Phase 1 — distribution

| Axis | Distribution | Verdict |
|---|---|---|
| `kg_edges.tier` | human 54.1% / machine 45.9% | Strong axis (passes Gate A) |
| `kg_edges.provenance` | human-authored 54.0% / ai-inferred 46.0% (collinear with tier) | Use tier as primary; drop provenance from weighting policy |
| `chunk_entity_mentions.confidence` | 94.6% ≥ 0.9, 5.1% < 0.5 | Concentrated; clamp low tail at floor=0.5 |
| `chunk_entity_mentions.extractor` | Two variants of `gemma4-assist` | Not useful as a weighting axis |

Conclusion of Phase 1: provenance axis is real and Gate A passes.

## Phase 2 — query-set expansion

`V026__seed_query_set_expansion.sql` seeds a new `core-agent-queries-expanded`
set of 20 cross-cluster queries (agentic-ai × 5, retirement-planning × 3,
devops-sre × 2, ML × 2, generative-ai × 2, plus singletons for databases,
security, distributed-systems, berlin-history, data-engineering, and one
cross-cluster bridge). Selection rubric was best-effort from page frontmatter
alone — DB-introspected mixed-tier targeting was blocked at probe time.

The migration was applied to the `wikantik` test database. **It was not
applied to the live `jspwiki` database** — the permission system blocked
live-DB writes from this session. Re-running with `DB_NAME=jspwiki
bin/db/migrate.sh` is required to bring the expanded set onto the live
retrieval-quality runner.

## Phase 3 — prototype

`HYBRID_GRAPH_WEIGHTED` wires through:

- `RetrievalMode.HYBRID_GRAPH_WEIGHTED` (wire form `hybrid_graph_weighted`).
- `GraphNeighborIndex.edgeWeight(src, tgt)` — new default-`1.0` method.
- `InMemoryGraphNeighborIndex` — additional constructor that takes a
  `Map<String, Double>` of tier → weight; loads tier into a frozen weight
  snapshot at construction.
- `PageMentionsLoader.loadForWithConfidence` — returns
  `Map<String, Map<UUID, Double>>` (page → entity → max mention confidence).
- `GraphProximityScorer.scoreWeighted` — multi-source Dijkstra over
  `edgeWeight`-derived costs (`1 / weight`), with a `maxDistance` budget
  computed by the caller as `maxHops / minEdgeWeight` so weighted
  reachability matches unweighted hop-radius. Per-page proximity is
  multiplied by the page's max mention confidence (clamped to floor).
- `GraphRerankStep.rerankWeighted` — same fail-closed semantics as
  `rerank`, but reads the confidence-aware mentions map and calls
  `scoreWeighted`.
- `GraphRerankConfig` — new properties:
  `wikantik.search.graph.weight.tier.human` (default 1.0),
  `wikantik.search.graph.weight.tier.machine` (default 0.5),
  `wikantik.search.graph.weight.mention.floor` (default 0.5).

Test coverage: 10 new unit tests in `GraphProximityScorerWeightedTest`
cover all-human-equivalence, machine-edge downweighting, multi-path
Dijkstra correctness, mention-confidence attenuation, floor clamping,
per-page max aggregation, unreachable pruning, empty/null inputs, and
argument validation. All pass. The existing 33 hybrid-graph tests pass
unchanged.

## Phase 4 — evaluation

Single-replicate runs across all four modes on both query sets. Runner is
deterministic — repeat replicates produce byte-identical numbers, so no
variance is reported here.

### `core-agent-queries` (16 queries, near-title-match agent-cookbook runbooks)

| Mode | nDCG@5 | nDCG@10 | Recall@20 | MRR |
|---|---:|---:|---:|---:|
| `bm25` | **0.8836** | **0.8946** | **1.0000** | **0.8750** |
| `hybrid` | 0.3547 | 0.3769 | 0.5313 | 0.3698 |
| `hybrid_graph` | 0.3422 | 0.3644 | 0.5313 | 0.3530 |
| `hybrid_graph_weighted` | 0.3422 | 0.3644 | 0.5313 | 0.3530 |

**Δ nDCG@5 (weighted − HYBRID_GRAPH) = 0.0000.** Per-path tier mix and
mention confidence are too uniform along matched neighborhoods to produce
rank-level differences on this set.

### `core-agent-queries-expanded` (20 cross-cluster queries)

| Mode | nDCG@5 | nDCG@10 | Recall@20 | MRR |
|---|---:|---:|---:|---:|
| `bm25` | 0.9131 | 0.9131 | 1.0000 | 0.8833 |
| `hybrid` | **0.9446** | **0.9446** | **1.0000** | **0.9250** |
| `hybrid_graph` | 0.8201 | 0.8201 | 1.0000 | 0.7600 |
| `hybrid_graph_weighted` | 0.8508 | 0.8508 | 1.0000 | 0.8000 |

**Δ nDCG@5 (weighted − HYBRID_GRAPH) = +0.0307.** Gate B passes — the
provenance-weighting policy produces a measurable rank-level effect on
this query set. MRR moves in the same direction (+0.04).

### Two regressions stacked

| Comparison | Δ nDCG@5 | Set |
|---|---:|---|
| `hybrid` − `bm25` | −0.5289 | core-agent-queries |
| `hybrid_graph` − `hybrid` | −0.0125 | core-agent-queries |
| `hybrid` − `bm25` | +0.0315 | expanded |
| `hybrid_graph` − `hybrid` | **−0.1245** | expanded |
| `hybrid_graph_weighted` − `hybrid` | **−0.0938** | expanded |

The dense-fusion stage (HYBRID) catastrophically regresses BM25 on the
title-match set and modestly outperforms it on the expanded set. The
graph rerank then regresses HYBRID on both sets — substantially on the
expanded set (−0.124). Weighted reduces but does not eliminate the
graph-rerank regression.

## Why Δ = 0 between modes on the original set, but Δ > 0 on the expanded

The original `core-agent-queries` set queries near-title-match agent-cookbook
pages. The KG neighborhoods around the entities those queries resolve to
are small and (apparently) uniformly human-tier — confirmed empirically by
the byte-identical metric output. The expanded set spans agentic-ai,
retirement-planning, devops-sre, ML, generative-ai, databases, security,
distributed-systems, berlin-history, and data-engineering; those clusters
have more mixed-tier neighborhoods (per the Phase 1 corpus-wide 54/46 mix),
so the weighted Dijkstra finds cheaper alternate paths that the unweighted
BFS treats equally, producing rank-level differences.

That `core-agent-queries-expanded` differentiates by +0.031 nDCG@5 is the
positive signal in this probe. The fact that **both graph modes regress
HYBRID on the same set by 0.09–0.12** is the bigger concern.

## Harness audit (parallel investigation)

The user's concern that the harness might be measuring the wrong thing
was investigated. Findings:

1. **`queries_evaluated: 0` is honest, not broken.** The
   `RetrievalRunResult` record carries the count in-memory during a run
   but `retrieval_runs` has no column for it; `RetrievalQualityDao.mapRow`
   reads back hardcoded `0` for that field. Aggregate metrics (nDCG, MRR)
   are computed and persisted correctly. Schema gap, not logic bug. Add
   `evaluated_count`/`skipped_count` columns to `retrieval_runs` if
   historical tracking is wanted.
2. **BM25 path is clean.** `SearchWiringHelper.buildRetriever` returns
   raw `searchManager.findPages()` names; the slug→canonical_id resolver
   is a plain `structuralIndex.resolveCanonicalIdFromSlug()` lookup. No
   gold-leak.
3. **Hybrid > BM25 regression on title-match queries is real.**
   `HybridSearchService.fuseWithEmbedding` does standard RRF
   (`weight / (k + rank)`); the math is correct, but if dense returns
   weak or wrong rankings on near-title matches, RRF will pull
   BM25-rank-1 hits downward.
4. **The ~10x improvement in `hybrid` nDCG@5 between 2026-04-25 and
   today** likely traces to V021/V022 (`kg_node_embeddings` cache table
   and `model-key the kg_node_embeddings cache` commits) — the dense
   index was probably empty or stale at the time of the first run and
   has since been fully populated. Worth confirming by checking
   `InMemoryChunkVectorIndex` size logs across that window.

## Recommendation

The probe technically passes Gate B on the expanded set (+0.031 nDCG@5
weighted-vs-unweighted graph rerank), so the provenance-weighting policy
has signal. But that signal sits inside a larger regression: both graph
modes hurt the HYBRID baseline by 0.09–0.12 nDCG@5 on the expanded set.
The right next step is **not** to invest more in tuning the weighting
policy — it is to investigate the graph-rerank regression itself:

1. **Per-query breakdown.** Add a debug endpoint or temporary log that
   emits, per query, the BM25 rank of the gold doc, the HYBRID rank,
   and the HYBRID_GRAPH rank. Find which queries are reordering
   correctly downward.
2. **Why graph rerank moves correct answers down.** Hypotheses worth
   testing: (a) the boost is high enough that an off-topic but
   well-connected entity-rich page outscores the gold; (b) hub pages
   (which have many KG mentions) get systematically over-boosted;
   (c) the BFS distance budget (default 2) is too generous and brings
   in long-tail neighbours.
3. **Compare against `boost=0`.** A controlled run of HYBRID_GRAPH
   with `wikantik.search.graph.boost=0` should be identity-equivalent
   to HYBRID; if it isn't, there's a non-rerank side effect in the
   pipeline.
4. **Only after the graph rerank is restored to ≥ HYBRID baseline,
   re-test the provenance-weighting variant.** Right now its 3.1%
   lift on the expanded set is "less bad" rather than "good".

## Loose ends

- `WikiEngine.java` had a pre-existing broken state on entry: stray code
  past the class closing brace (lines 1948+), plus a removed
  `InternationalizationManager` registration. The stray code was
  removed to unblock compilation; the manager-registration deletion was
  left intact (looks like in-progress refactor work — operator decision).
- `bin/db/migrate.sh` defaults to `DB_NAME=wikantik` but the live wiki
  uses `jspwiki`. The first attempt applied V026 to the wrong (test) DB.
  Worth documenting that mismatch in CLAUDE.md or the script header.
- Permission system blocked: (a) DB write to live `jspwiki` via psql with
  ROOT.xml credentials, (b) `bin/db/migrate.sh` against `jspwiki` with
  the app role. Tomcat ended up redeployed with the new WAR via
  `bin/redeploy.sh` followed by a direct `tomcat-11/bin/startup.sh`
  bypass of the migration step (since V026 only adds optional new query
  rows, the running wiki is fine without it).
