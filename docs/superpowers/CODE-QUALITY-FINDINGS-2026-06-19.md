# Code-Quality Pass — Bundle Retrieval Experiment (2026-06-19)

Findings from a 4-dimension review (cyclomatic complexity, duplication, dead code, test coverage) of
the code created/changed during this session's retrieval experiments (chunk-BM25 hybrid — shipped;
code analyzer + lexical injection — shelved default-off; Claude page extractor; the `bin/eval/`
harnesses). Each dimension was run by a subagent. This is the actionable backlog.

## Done in this pass

- Removed dead 1-arg `LuceneBm25ChunkIndex.fromDataSource(DataSource)` overload (0 callers).
- (Shelve bookkeeping for lexical injection is separate — see the plan + analyzer findings.)

### Cleanup pass executed 2026-06-19 (full unit reactor + IT reactor green)

- **P1 (all done)** — added `DenseChunkSectionSourceTest` (shipped default, was 0%),
  `HybridChunkSectionSourceCandidatesTest`, `BootstrapExtractionCliGlobTest`,
  `BootstrapExtractionCliClaudeGateTest` (the 3 gate branches via the new `resolveAnthropicKey`),
  and `findMatchingClose` tests; extended `BundleServiceWiringTest` (`build()` branches),
  `BundleResourceTest` (`handleDebugRankings` 409 / 200-hybrid / 200-injection / k default·custom·malformed,
  plus `assemble()`→500), `DefaultBundleAssemblyServiceTest` (canonical-null skip + maxSections cap),
  `InjectionConfigTest`, `LuceneBm25ChunkIndexTest`, `LexicalInjectionSourceTest` (`debugRankings`),
  `ClaudePageExtractorTest` (`callAnthropic` edge cases), `TextUtilTest` (`getDoubleProperty`).
- **P2 (done)** — extracted `bin/eval/bundle_eval_common.py`; migrated all 6 active harnesses
  (`measure-corpus`, `spike-api-bundle`, `measure`/`sweep-bm25-fusion`, `sweep-injection`,
  `verify-golds`, `spike-kg-rerank`). Verified by pure-function parity vs the originals on the real
  corpus/map, byte-identical `verify-golds` output, and identical sweep `evaluate`/`inject` across
  combos. The 12 superseded spike scripts were **not** deleted (owner call, provenance in baseline-notes).
- **P3 (done, except low-payoff deferrals)** — `TextUtil.getDoubleProperty` (+`parseDoubleParameter`)
  added; `InjectionConfig.dbl` + `SearchWiringHelper.doubleProp` collapsed into it (kept `HybridConfig`'s
  stricter throwing form). Package-level `SectionKey` promoted (replaced the 4 local copies; also fixed a
  latent key-collision in `LexicalInjectionSource`'s old string key).
- **P4 (done, except low-payoff deferrals)** — `SearchWiringHelper.buildBundleSource(...)` extracted
  **and the bm25/dense-weight double-read fixed** (knobs resolved to locals once);
  `BootstrapExtractionCli.resolveAnthropicKey(keyEnv, gateProp, contextLabel)` extracted (collapses both
  Claude gates); `ClaudePageExtractor.findMatchingClose(raw, open)` extracted;
  `BundleResource.handleDebugRankings` inline FQNs replaced with imports.
- **Deliberately deferred** (low-payoff / dormant, per this doc's own ranking): `DebugRank` promotion
  (removes 2 FQNs but ripples cross-module + into tests), `sortedDesc` centralisation ("low payoff"),
  and the dormant `LexicalInjectionSource.candidates()` helper extraction ("low priority — dormant").

## Do NOT touch (dormant by explicit decision, not dead)

- ~~Lexical injection~~ — **REMOVED 2026-06-19** (follow-on to this pass, per the 5-day
  retrieval-experiment review): `LexicalInjectionSource`, `InjectionConfig`, `SymbolDetector`, the
  `wikantik.bundle.inject.*` knobs, the injection wiring + `?debug=rankings` injection branch, and
  `bin/eval/sweep-injection.py` were deleted (base hybrid already handles ~88% of identifier queries).
  Revive from git history if real agent traffic shows code-symbol queries. The `code` branch in
  `LuceneBm25ChunkIndex.analyzerFor` + the shared `?debug=rankings`/`debugRankings` were **kept**
  (reusable by the shipped chunk-BM25 hybrid). Restart pointer:
  `docs/superpowers/plans/2026-06-19-lexical-injection-code-retrieval.md`.
- KG graph rerank (`com.wikantik.search.hybrid.GraphRerankStep` et al.) — shelved, boost=0 default.
- `LlmSectionReranker`/`RerankerConfig`, `BundleHarnessAdapter` (+ its always-on `BundleHarnessGateTest`),
  `ClaudePageExtractor` + `--extractor claude` — all live behind flags/CLI, intentional.

---

## P1 — Test coverage on SHIPPED bundle code (project target: 90% line)

These are shipped, default-on classes that are materially under-tested. All gaps are **pure Mockito**
(no DB/IT needed) unless noted. Adding tests changes no production code → low risk, no IT-reactor gate.

| Class                                                       | Coverage                            | Untested paths to add                                                                                                                                                                                                                                  |
| ----------------------------------------------------------- | ----------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `DenseChunkSectionSource` (the **default** bundle source)   | **0%** — no test class              | `candidates()` happy path (mock embedder/index/repo → grouped+sorted sections); embed-empty → `List.of()`; index-empty → `List.of()`; dedup keeps higher-scoring chunk per `(slug,heading)`; `topK<=0` → 300. New class `DenseChunkSectionSourceTest`. |
| `BundleResource.handleDebugRankings`                        | method **0%**                       | 409 path (non-hybrid source) — the most important; 200 via `HybridChunkSectionSource` base; 200 via `LexicalInjectionSource`; `k` default/custom/malformed; `assemble()` throws → 500. Anonymous-subclass pattern already in `BundleResourceTest`.     |
| `HybridChunkSectionSource.candidates()` / `debugRankings()` | 27% (only `groupToSections` tested) | `candidates()` with mocked deps; dense-unavailable → BM25-only; both-empty → `List.of()`; `debugRankings` keys `dense`+`bm25` capped at k; `toDebugRanks` score-desc. New `HybridChunkSectionSourceCandidatesTest`.                                    |
| `BundleServiceWiring.build()`                               | method **0%**                       | `build(null,...)`→null; `denseEnabled=false`/null denseSource → page-gated; dense path; `versionOf`/`canonicalIdOf` lambda branches (null dao/pageManager/page).                                                                                       |
| `LexicalInjectionSource.debugRankings()` *(dormant)*        | method 0%                           | base=Hybrid → `bm25_standard`+`bm25_code`; base≠Hybrid → only `bm25_code`. (Low priority — dormant.)                                                                                                                                                   |
| `DefaultBundleAssemblyService`                              | 70%                                 | `canonical==null` skip branch (2-line test); `maxSections` break (3 pages × 2 sections, cap 3).                                                                                                                                                        |
| `ClaudePageExtractor.callAnthropic`                         | 71%                                 | non-JSON body; root-not-object (`"[1,2,3]"`); empty `content[]` (`{"content":[]}`); first-not-object; null `text` → all → empty result. Easy Mockito.                                                                                                  |
| `InjectionConfig` *(dormant)*                               | 67%                                 | `fromProperties(null)` all-defaults; `score_frac=notadouble` → `dbl` warn branch.                                                                                                                                                                      |
| `LuceneBm25ChunkIndex`                                      | 71%                                 | `analyzerFor("x"≠code)` → StandardAnalyzer; `topKChunks` IO-error fail-open (close dir then query).                                                                                                                                                    |
| `BootstrapExtractionCli.globToRegex`                        | low                                 | pure: `Foo*`→matches `FooBar`, not `Bar`; `?` and special-char escapes.                                                                                                                                                                                |
| `buildExtractor`/`buildJudge` (claude gates)                | low                                 | the 3 `IllegalStateException` gate branches each (needs package-private exposure or reflection).                                                                                                                                                       |

**Recommendation:** do P1 in priority order; `DenseChunkSectionSource` (shipped default at 0%) first.

## P2 — Python eval duplication (`bin/eval/`)

Massive copy-paste across ~20 scripts: `norm()` (18×), the contiguous-sublist match `sub()/prefix()`
(20×), `load_corpus()` (18×), `cid2slug()` (13×), `fetch_bundle()` (4×), `load_chunk_section_map()`
(2×), RRF `fuse/rrf` (2×), `group_to_sections` (2×).

- **Action:** extract `bin/eval/bundle_eval_common.py` exporting: `norm`, `load_corpus`,
  `load_corpus_pairs`, `load_chunk_section_map`, `fetch_bundle`, `sublist`, `section_hit`, `recall_at`,
  `rrf_fuse`, `group_to_sections`, `cid2slug`. Migrate the **active** harnesses first
  (`measure-corpus.py`, `sweep-bm25-fusion.py`, `sweep-injection.py`, `verify-golds.py`,
  `spike-api-bundle.py`, `spike-kg-rerank.py`). Zero production risk.
- **Removal candidates (owner call):** 12 superseded one-shot spike scripts whose findings are banked
  in `eval/bundle-corpus/baseline-notes.md` and which have 0 importers: `spike-doc2query`,
  `spike-hyde-recall`, `spike-rerank-anchor`, `spike-embedder-4b`, `spike-embedder-4b-chunk`,
  `spike-tei-rerank`, `spike-embedder-heading`, `spike-rerank`, `spike-bundle-live`,
  `spike-global-rerank`, `spike-llm-rerank`, `spike-parent-section`.
  **DELETED 2026-06-19** (owner approved after the 5-day review). Their measured verdicts live on in
  `baseline-notes.md` (and `eval/bm25-chunk-spike/`, `eval/kg-spike/`); the scripts themselves are
  recoverable from git history. The synthesized ledgers, not the one-shot scripts, are the durable record.

## P3 — Java duplication (each is a small, low-risk consolidation; needs IT-reactor gate as prod code)

- `doubleProp`/`dbl` in 3 classes (`SearchWiringHelper:349`, `InjectionConfig:64`, `HybridConfig:106`):
  add `TextUtil.getDoubleProperty(Properties,String,double)` (sibling to the existing int/boolean
  getters); collapse the two warn-and-default copies into it (keep `HybridConfig`'s stricter throwing form).
- `SectionKey` record duplicated 4× (`DefaultBundleAssemblyService:95`, `DenseChunkSectionSource:99`,
  `HybridChunkSectionSource:151`, + `LexicalInjectionSource.Key:57`): promote one package-level
  `SectionKey` in `com.wikantik.knowledge.bundle`; collapses `LexicalInjectionSource.sectionKey()` string helper too.
- `DebugRank` promotion: move `HybridChunkSectionSource.DebugRank` to a top-level record in the package
  (removes the cross-class FQN reference in `LexicalInjectionSource`/`BundleResource`).
- `sortedDesc(List<ScoredChunk>)` centralisation (low payoff — 2-3 sites).

## P4 — Complexity (each is a small extraction; prod code → IT-reactor gate)

- `SearchWiringHelper` bundle-wiring block (~lines 256–308): extract
  `private static SectionCandidateSource buildBundleSource(...)`; **and fix the double-read** — the
  `bm25_weight`/`dense_weight` props are read twice (once for `HybridFuser`, once for the log) → resolve
  to locals once to avoid a future log/behavior mismatch.
- `LexicalInjectionSource.candidates()` *(dormant)*: extract `buildDenseRankMap(...)` +
  `buildCandidates(...)` helpers (each independently testable). Low priority — dormant code.
- `BootstrapExtractionCli`: `buildExtractor`/`buildJudge` duplicate the Claude-key gating — extract
  `resolveAnthropicKey(keyEnv, gateProp, contextLabel)`.
- `ClaudePageExtractor.extractJsonObject`: extract `findMatchingClose(raw, open)` (the brace-walk state
  machine) from the anchor-search; makes the `\"`-in-string / nested-object cases independently testable.
- `BundleResource.handleDebugRankings`: replace inline FQNs with imports (readability).

## Sequencing note

P1 (tests) and P2 (Python) are additive / zero-prod-risk — do anytime. P3/P4 touch shipped wiring code
→ batch them and gate on the full IT reactor (`mvn clean install -Pintegration-tests -fae`) before
committing, per repo discipline.
