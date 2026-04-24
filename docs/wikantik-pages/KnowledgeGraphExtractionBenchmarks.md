---
canonical_id: 01KQ0P44RJB1S0M1ZPBSYKQ7KT
title: Knowledge-Graph Extraction Benchmarks — April 2026
cluster: wikantik-development
type: article
tags:
- knowledge-graph
- extraction
- benchmarks
- embeddings
- operations
- models
summary: Seven-run benchmark comparing entity-extractor models (gemma4-assist, qwen2.5:7b, gemma4:e2b) at concurrency 1–4 against the Wikantik corpus, plus a before/after look at search ranking impact from the save-time chunker rebuild that consolidated 39k chunks into 23k. Captures the data behind the shipping defaults and the rejected alternatives.
related:
- KnowledgeGraphCore
- HybridRetrieval
- GraphRAG
- KnowledgeGraphsAndGenAIWorkflows
---

# Knowledge-Graph Extraction Benchmarks — April 2026

This page records the benchmark runs performed on 2026-04-23 while choosing
the shipping configuration for the Phase 2 entity extractor and the
save-time chunker. The goal was to pick a model/concurrency/chunk-size
combination that could extract the full ~40k-chunk corpus in an operator-friendly
wall-clock without swamping the review queue with noisy proposals.

The final choices in production:

- Extractor backend: **Ollama** against `inference.jakefear.com:11434`
- Model: **`gemma4-assist:latest`**
- Concurrency: **2**
- Chunker merge-forward floor: **150 tokens** (up from 8)

Everything below shows why — and why each of the alternatives that looked
promising on paper lost on actually-measured numbers.

## Corpus baseline

| | Before rebuild | After rebuild |
|---|---|---|
| `kg_content_chunks` | 39,264 | 23,256 |
| `content_chunk_embeddings` | 39,264 | 23,256 |
| `chunk_entity_mentions` | 344 | 0 (FK-cascaded on chunk delete) |
| Mean tokens / chunk | 103 | 174 |
| p50 / p95 tokens / chunk | 77 / 261 | 166 / 335 |
| Max tokens / chunk | 1,963 | 1,963 (atomic blocks unchanged) |
| Embedding rebuild wall-clock | — | 6m 18s |

The chunker rebuild was driven by one configuration change: raising
`wikantik.chunker.merge_forward_tokens` from 8 to 150. That change also
exposed two previously-advertised-but-unwired knobs (`target_tokens`,
`min_tokens`) which were removed from the `ContentChunker.Config` record.

## Summary of extractor runs

All runs use the same system prompt, same chunker output (with differing
chunk sizes between "old" and "new"), and the same post-processing
(`chunk_entity_mentions` upsert, `kg_proposals` insert, `kg_rejections`
suppression). Per-chunk RPC mean is the wall-clock latency of a single
extraction call; effective s/chunk is per-chunk-RPC divided by concurrency.

| Run | Model | Prompt | Concurrency | Chunks | Per-chunk RPC | Effective s/chunk | Full-corpus projection |
|---|---|---|---|---|---|---|---|
| 1 | gemma4-assist:latest | verbose | 1 | 39k | 8.5 s | 8.5 s | ≈ 93 h |
| 2 | qwen2.5:7b-instruct-q5_K_M | verbose | 1 | 39k | 14.2 s | 14.2 s | ≈ 155 h |
| 3 | qwen2.5:7b-instruct-q5_K_M | tightened | 2 | 39k | 18.8 s | 9.4 s | ≈ 104 h |
| 4 | gemma4:e2b | tightened | 3 | 39k | 20.2 s | 6.7 s | ≈ 73 h |
| 5 | gemma4-assist:latest | tightened | 4 | 39k | 41.0 s | 10.3 s | ≈ 112 h |
| **6** | **gemma4-assist:latest** | **tightened** | **2** | **23k** | **25.3 s** | **12.7 s** | **≈ 82 h** |
| 7 | gemma4-assist:latest | tightened | 1 | 23k | 13.6 s | 13.6 s | ≈ 88 h |

**Run 6 is the shipping configuration.** Runs 4 and 3 are faster on the
clock but lose to run 6 on quality (detailed below). Run 5's c=4 shows
scaling has negative returns past c=2 on a 4060 Ti serving a 7–8B-class
model — the GPU is already bandwidth-bound.

## Proposal volume comparison

On the same page (AbstractAlgebra, 42 chunks in the old corpus / 27 in the
new):

| Model / prompt | Mentions | Proposals | Mentions:Proposals |
|---|---|---|---|
| gemma4-assist + verbose (run 1) | 8 | 108 | 1 : 13 |
| qwen2.5:7b + verbose (run 2) | 1 | 213 | 1 : 213 |
| qwen2.5:7b + tight (run 3) | 2 | 158 | 1 : 79 |
| gemma4:e2b + tight (run 4) | 4 | 129 | 1 : 32 |
| gemma4-assist + tight c=4 (run 5) | 15 | 68 | 1 : 4.5 |
| gemma4-assist + tight c=2 (run 6) | 12 | 48 | 1 : 4 |

The tighter prompt reduced gemma4-assist's proposal-per-chunk rate from
~2.6 to ~1.3 while simultaneously doubling the mention rate. Roughly half
the review queue to work through for equivalent coverage.

## Quality observations — same page, different models

Sample of top entities emitted for **AbstractAlgebra** (first chunks):

**gemma4-assist, verbose prompt (run 1)**

```
rings, Ore rings, localization procedures, Ore condition, quantum groups,
C*-algebras, Ring theory, algebraic structures, integers, polynomials,
algebraic geometry, representation theory, non-commutative geometry,
categorical frameworks, geometric frameworks
```

All domain-appropriate named concepts. Reasoning grounded in specific
chunk text.

**qwen2.5:7b, verbose prompt (run 2)**

```
Ring, Algebraic structures $(R, +, \x08oldsymbol{⋅})$,   ← LaTeX corruption
Integers ($\x08oldsymbol{ℤ}$),                            ← LaTeX corruption
AdvancedTechniques,                                        ← CamelCase phrase
SharedRigorousUnderstanding,                               ← phrase-as-entity
FoundationalObject,                                        ← meta-term
R, +, ⋅, R, abean,                                         ← operators, typo
ring, unital ring, non-unital ring
```

The `\x08oldsymbol` string is a JSON escape-character collision where
the model's output of `\b` was decoded as the ASCII backspace control
character. The `abean` entry is a hallucinated typo of "abelian". Both
are pre-existing qwen JSON-mode issues on technical prose.

**qwen2.5:7b, tightened prompt (run 3)**

```
Ring theory, algebraic structures $(R, +, ⋅)$, arithmetic of integers ($ℤ$),
polynomials ($k[x]$), advanced techniques, foundational object,
R, +, ⋅, R, 0, Unity, FunctionalAnalysis (type=Article),
commutative rings, non-commutative rings
```

LaTeX corruption resolved. Still emits operators and single letters.
`FunctionalAnalysis` correctly tagged as `Article` — the one win.

**gemma4:e2b, tightened prompt (run 4)**

```
Ring, R, +, ×, 0, a, b, c, FunctionalAnalysis, Rings, Commutativity,
Non-commutative, Ring, ideal, Prime Ideals, R, P, Spec(R), M
```

Single letters (`a`, `b`, `c`, `R`, `M`, `P`) appear frequently — the 2B
model is the least discriminating about what qualifies as a named entity.
Does correctly pick up `Spec(R)`, `Prime Ideals`, `FunctionalAnalysis`.

**gemma4-assist, tightened prompt (runs 5–7)**

Quality is equivalent to run 1 but with the proposal-volume and
mention-rate improvements from the tighter prompt.

## Why concurrency doesn't help gemma4-assist

Adding concurrent requests to Ollama on a single 4060 Ti slows each
in-flight request proportionally, because the GPU is bandwidth-bound on a
7–8B-class model. The net-net for gemma4-assist:

| Concurrency | Effective s/chunk | Relative |
|---|---|---|
| c=1 | 13.6 s | baseline |
| c=2 | 12.7 s | **7% faster** |
| c=4 | 10.3 s (old-chunks), degraded on new chunks | modest gain then regression |

Concurrency does help the 2B `gemma4:e2b` — there's enough VRAM headroom
for the GPU scheduler to actually run requests in parallel. But the
quality regression on small models is severe enough that we didn't ship
it.

## Per-page timing detail (first 10 pages, run 6)

Reference for anyone tuning a sample: first 11 pages of run 6 (shipping
config, 23k-chunk corpus, gemma4-assist c=2):

| Page | Chunks | Total (ms) | Per-chunk (ms) | Mentions | Proposals |
|---|---|---|---|---|---|
| 2026IranWar | 4 | 73,691 | 18,423 | 8 | 27 |
| AbstractAlgebra | 27 | 322,274 | 11,936 | 12 | 48 |
| AcceleratingAiLearning | 13 | 140,737 | 10,826 | 8 | 6 |
| AccountTypeStrategy | 12 | 165,757 | 13,813 | 12 | 24 |
| AcidTransactionsAndIsolation | 29 | 493,587 | 17,020 | 1 | 77 |
| ActorModelProgramming | 26 | 322,939 | 12,421 | 8 | 43 |
| AdapterPattern | 28 | 370,073 | 13,217 | 18 | 45 |
| AdjustmentOfStatusProcess | 13 | 130,194 | 10,015 | 7 | 21 |
| AdminSecurityUi | 2 | 30,972 | 15,486 | 2 | 8 |
| AdvancedSkillPatterns | 24 | 251,145 | 10,464 | 3 | 22 |
| AdventureTravelPlanning | 23 | 293,852 | 12,776 | 2 | 39 |

## Chunker rebuild — search-ranking impact

Before the full extractor run we verified that the 39k → 23k chunker
rebuild didn't regress search quality. The test compared top-10 results
for the same queries before and after the rebuild, with graph rerank
disabled (no mentions populated yet).

### Methodology

For each query, capture top-10 page names from `/api/search` against the
old corpus (39k chunks, stored baseline from 07:25 local), and again
against the new corpus (23k chunks, captured immediately after the
rebuild completed). Compare set overlap and ordering.

### Results — `"knowledge graph"` top 10

| Rank | Old chunks | New chunks |
|---|---|---|
| 1 | WikantikKnowledgeGraphAdmin | InventionOfKnowledgeGraph |
| 2 | InventionOfKnowledgeGraph | WikantikKnowledgeGraphAdmin |
| 3 | KnowledgeGraphCore | KnowledgeGraphCore |
| 4 | KnowledgeGraphDogfooding | KnowledgeGraphVsRelationalDatabase |
| 5 | KnowledgeGraphVsRelationalDatabase | GraphRAG |
| 6 | GraphRAG | KnowledgeGraphsAndManagement |
| 7 | IndustrialKnowledgeGraphUseCases | IndustrialKnowledgeGraphUseCases |
| 8 | KnowledgeGraphsAndManagement | FederatedKnowledgeGraphs |
| 9 | KnowledgeGraphCompletion | KnowledgeGraphCompletion |
| 10 | FederatedKnowledgeGraphs | KnowledgeGraphConstructionPipeline |

Set overlap: **8 of 10** preserved. Dropped: `KnowledgeGraphDogfooding`.
Added: `KnowledgeGraphConstructionPipeline`. Positions 1–2 swapped; both
are highly relevant.

### Results — `"GraphRAG"` top 10

| Rank | Old chunks | New chunks |
|---|---|---|
| 1 | GraphRAG | GraphRAG |
| 2 | KnowledgeGraphsAndManagement | KnowledgeGraphsAndManagement |
| 3 | KnowledgeGraphsAndGenAIWorkflows | KnowledgeGraphsAndGenAIWorkflows |
| 4 | InventionOfKnowledgeGraph | InventionOfKnowledgeGraph |
| 5 | RagImplementationPatterns | RagImplementationPatterns |
| 6 | NameOfArticle | NameOfArticle |
| 7 | IndustrialKnowledgeGraphUseCases | IndustrialKnowledgeGraphUseCases |
| 8 | AiFunctionCallingAndToolUse | AiMemoryAndPersistence |
| 9 | WikantikKnowledgeGraphAdmin | ResourceDescriptionFramework |
| 10 | ResourceDescriptionFramework | WikantikKnowledgeGraphAdmin |

Set overlap: **8 of 10** preserved. Top 7 identical. Dropped:
`AiFunctionCallingAndToolUse`. Added: `AiMemoryAndPersistence`.

### Interpretation

- **BM25 is mostly unaffected** — Lucene indexes full pages, not chunks,
  so the BM25 half of the hybrid score doesn't move with chunk
  boundaries.
- **Dense retrieval sees the bigger chunks.** A chunk at 174 tokens
  averages a slightly less peaky embedding than one at 103 tokens, so
  chunks that were narrowly winning on a laser-focused topic sentence
  can slip a rank or two; chunks now containing multiple related
  sub-topics can win queries they previously missed. Net effect: ~80%
  overlap in top-10, slight topology shuffle.
- **Graph rerank remains a no-op** until mentions are populated. The
  ranking improvement this stack was designed for has to wait on the
  full extractor run.

## Takeaways

1. **Quality dominates throughput** on this corpus. A "perfect throughput,
   low signal-to-noise" model is worse than "modest throughput, high
   signal-to-noise" because the admin review queue is the real bottleneck.
   Shipping gemma4-assist cost about 12 % more wall-clock than gemma4:e2b
   but produces about half the review noise.
2. **Prompt tightening beat model swapping.** The same gemma4-assist
   model went from 2.6 proposals/chunk (verbose) to 1.3 (tight) while
   doubling its mention rate. No model change matched that on
   signal-to-noise.
3. **Concurrency is model-dependent.** c=2 helps 7–8B models marginally
   (~7 %); c≥3 regresses. Small 2B models scale to c=3 with real gains.
   One knob doesn't fit all.
4. **Chunker floor is the biggest lever we pulled.** 39k → 23k chunks is
   a 41 % reduction in extraction RPCs for ~0 search-quality cost,
   against a configuration change that took seconds.
5. **Claude Haiku 4.5 remains the "definitely finishes in a workday"
   fallback** at ≈ $75 and ≈ 25 h for the full corpus, if the ~82 h
   local-only projection ever becomes operationally unacceptable.

## Further reading

- [docs/KnowledgeGraphRerank.md](KnowledgeGraphRerank) — configuration and
  tuning guide.
- [docs/superpowers/plans/2026-04-22-kg-rag-uplift.md](KgRagUpliftPlan) —
  the original three-phase plan this benchmarks.
- `wikantik-extract-cli/` — standalone CLI that runs the batch without a
  Tomcat instance, added so long runs survive local-development
  restarts.
