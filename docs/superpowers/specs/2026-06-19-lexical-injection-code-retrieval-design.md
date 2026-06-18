# Code/Identifier Retrieval via Lexical Injection — Design

**Status:** Approved design (2026-06-19). Follow-on to the chunk-BM25 hybrid bundle source
(`eval/bm25-chunk-spike/`), extending it to serve code-symbol / config-lookup queries without
regressing natural-language retrieval.

## Problem

The shipped context bundle is a chunk-level **dense + BM25 hybrid** with **dense-heavy** fusion
(`bm25_weight=0.5`), which serves natural-language queries well (recall@12 0.721). A measured
experiment (`eval/bm25-chunk-spike/analyzer-and-efsearch-findings.md`) established two facts:

1. A **code-aware BM25 analyzer** (`WordDelimiterGraphFilter`: camelCase + snake_case splitting,
   already built + tested as `LuceneBm25ChunkIndex.analyzerFor("code")`, default-inert) makes BM25
   *find* code-symbol/config gold sections that dense ranks coldly: on a 13-query identifier corpus,
   gold sections at dense rank 26/53/160 jump to BM25(code) rank 0/3/4.
2. But realizing that gain needs **BM25-heavy fusion**, which **collapses** natural-language
   (0.721 → 0.353). The two query regimes need **mirror-image, mutually-exclusive** global fusion
   configs.

The user confirms code-symbol / config-lookup queries are a large share of agent **and** human
traffic, and wants to **maximize the combined / per-query-best** result across both regimes.

**Key constraint discovered:** detection is hard. Many code queries are *concept-phrased* natural
language ("the actor system in akka") with **no literal code token**, so a query-side regex can't
route them — yet the code analyzer still helps them, because it splits the *content's* camelCase so
the natural query matches. So a pure query classifier is structurally insufficient.

Since there are no gold labels at **serve** time, "pick the better result per query" is realized as a
**self-gating merge**: the lexical signal wins only when it is confidently right *and* dense is cold.

## Decision: dense base + code-aware lexical injection (self-gating)

Keep the shipped dense-heavy hybrid bundle as the **base, untouched** (so natural-language cannot
regress by construction), and **inject** sections that a code-aware BM25 index ranks highly **and**
dense ranks coldly. No query router; injection self-gates, so it catches both literal-symbol and
concept-phrased code queries while staying near-silent on natural-language queries.

(Considered and rejected: **run-both + RRF meta-merge** — a global merge weight reintroduces the same
regime tug-of-war; **query-classifier + route** — structurally misses concept-phrased code queries,
the common hard case. The useful slice of the classifier is retained as a confidence *booster*, below.)

## Architecture

**One new unit: `LexicalInjectionSource implements SectionCandidateSource`** — a decorator that wraps
the shipped hybrid source unchanged. Dependencies (all injected, each independently testable):

- `base` — the shipped `SectionCandidateSource` (dense + BM25-standard, dense-heavy). Untouched.
- A **second `LuceneBm25ChunkIndex` built with `analyzerFor("code")`** — used *only* for injection.
  The base keeps its `standard` BM25 index for its own fusion. (Two ~few-MB RAM indexes built at
  startup. A `code-only` collapse — one index, accepting the base's ~0.015 natural-language give — is
  a knob; default is two indexes.)
- `embedder` + dense `ChunkVectorIndex` — to compute each candidate's dense rank (the query embedding
  is already cached, so this is a cheap extra `topKChunks`).
- `chunkRepo` — hydration to sections.
- `InjectionConfig` — the knobs below.

**Data flow per query (bundle):**
1. `base.candidates(query)` → the shipped ranked sections (the result we protect).
2. The injector runs `BM25(code)` and the dense index → a code-lexical section ranking + each
   section's dense rank.
3. **Gate & merge** (below) → up to `N` injected sections spliced into the base list at position `P`.

**`/api/search` (Phase 2):** the same shape at **page** granularity — a page-level code-BM25 index +
a page-level injector over the search results. Same unit/pattern, different index + granularity.

Wiring lives in `SearchWiringHelper` behind a flag; the injector is a pure decorator at the
`engine.setBundleSectionSource` seam.

## The injection rule & signals

A candidate section is **injection-eligible** iff **all** hold:

- **Dense-cold gate (`C`):** dense ranked it poorly — its best chunk's dense rank is worse than `C`,
  or it is absent from the dense top-`C`. This is the self-gating signal: natural-language queries
  have almost nothing dense-cold, so almost nothing injects.
- **Lexical-confidence gate (`J`, `α`):** the section is in BM25(code)'s top-`J` **and** clears a
  *relative* score bar `score ≥ α × (this query's top BM25(code) score)`. Relative (not absolute)
  because BM25 scores are not comparable across queries — this stops a query with no real lexical hit
  from injecting its weak best match.
- **Not already present:** dedup against the base output by section key `(slug, heading-path)`.

Up to **`N`** survivors (ordered by BM25(code) score) are spliced in at position **`P`** (e.g., after
the top-3 base sections — the strongest base results keep @1–3, injections land in the @4–12 band).

**Literal-symbol booster (the useful slice of the classifier):** a cheap regex for camelCase /
snake_case / dotted-config-key tokens in the *query*. When it fires (the query definitely names a
symbol), relax the gates for that query (higher `J`, lower `α`) → inject more aggressively. Not a
router — a confidence input that turns injection up when code intent is certain. Default on.

**Knobs** (config `wikantik.bundle.inject.*`, all swept; defaults set by the Phase-1 sweep):
`enabled`, `bm25_rank_max` (`J`), `dense_cold_min` (`C`), `score_frac` (`α`), `max_inject` (`N`),
`position` (`P`), `symbol_boost` (booster on/off + its relaxed `J`/`α`), `code_only` (collapse to one
index).

## Tuning & evaluation (measure-first)

- **Expand the identifier corpus first.** 13 hand-built queries is too small to tune 5 knobs without
  overfitting → grow to ~35–40, mined the same way (distinctive identifiers → verified gold
  sections), spanning both literal-symbol and concept-phrased forms.
- **Sweep `J, C, α, N, P` offline** using the existing pattern — `?debug=rankings` (extended to expose
  the code-analyzer BM25 ranking) + `measure-corpus.py` — so combos run without a restart each;
  validate the chosen point against a live run (both anchors must reproduce, as in the fusion sweep).
- **Acceptance bar:** ship the operating point that **maximizes identifier recall@12 subject to
  natural-language recall@12 ≥ ~0.715 and no material @5 dip.** Report the full Pareto sweep so the
  point can be re-picked if real traffic data arrives. The natural-corpus no-regression check is the
  gate on every iteration.

## Phasing

- **Phase 0** — expand + commit the identifier eval corpus.
- **Phase 1** — bundle injector: `LexicalInjectionSource` + code index + wiring + the sweep; ship
  default-on once it clears the bar. Surfaces: `/api/bundle` + `assemble_bundle` MCP (parity).
- **Phase 2** — `/api/search` page-level analog + its own identifier harness. Separate plan; does not
  block Phase 1.

## Testing & degradation

- **Unit tests for every gate** (dense-cold, relative-score, dedup, max-`N`, placement `P`, regex
  booster) with hand-built ranking fixtures — the gate logic is pure and isolated.
- **Fails open:** the injector never throws; if the code index or dense is unavailable it returns the
  base bundle unchanged — a flaky code index can never break `/api/bundle`. Default-off until tuned.
- **Ship gate:** full IT reactor (`mvn clean install -Pintegration-tests -fae`) **and** the
  both-corpora eval (identifier gain + natural-language no-regression).

## Out of scope / deferred

- `/api/search` (Phase 2, separate plan).
- A learned/semantic query classifier (the regex booster is the only classification used).
- Re-tuning the *base* fusion — the base stays exactly as shipped.

## Validation

The sweep numbers on the expanded corpus + the live confirmation are the validation; the falsifiable
bar is "identifier recall up, natural-language flat." Reproducibility: corpus, sweep harness, and live
invocations recorded as with the prior bundle eval work.
