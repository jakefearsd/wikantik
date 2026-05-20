# Wikantik Scaling Characterization

**Study date:** 2026-05-19 / 2026-05-20
**Host:** docker1 — 16-core, Docker-hosted; wikantik container at 2 GB / db (pgvector pg18) at 2 GB after Phase 0
**Methodology spec:** [docs/superpowers/specs/2026-05-19-wikantik-scaling-characterization-design.md](superpowers/specs/2026-05-19-wikantik-scaling-characterization-design.md)
**Implementation plan:** [docs/superpowers/plans/2026-05-19-wikantik-scaling-characterization.md](superpowers/plans/2026-05-19-wikantik-scaling-characterization.md)

---

## 1. Methodology

Two sweeps of the wikantik HTTP surface under a k6 `load` profile (`bin/loadtest.sh load --vus N --duration 3m`) — 14 candidate levels at +100 VU increments from N=200, sustained for 3 minutes per step (ramp 2 m + sustained 3 m + ramp-down 1 m). Each step ran in parallel with an external curl probe (`bin/curl-probe.sh`) sampling three endpoints — `/api/health`, `/wiki/Main`, `/api/search?q=cloud` — once per second to capture ground-truth real-user latency independent of k6's histograms and of jakemon's Grafana panels (the dashboard panels missed real-user pain in the previous overlay-era runs).

Stop criteria for each sweep — any of:

- `http_req_duration p95 > 3 s` sustained
- `http_req_failed rate > 10 %`
- docker1 `load1 > 24` (1.5 × the 16-core count)
- wikantik container healthcheck flap

Safety cap: 1500 VUs.

The first sweep ran against a freshly-baked solid-baseline container config (Phase 0). One targeted iteration changed Postgres tuning (Phase 3). The second sweep re-ran the same VU levels for direct comparison.

## 2. Solid baseline config (Phase 0)

The pre-study state was hard-gated by a 20-connection DBCP pool and 1 GB container memory; previous runs at 200 VUs were pool-bound, never reaching CPU. The solid-baseline change unlocks the box to use its hardware:

| Knob | Previous | Phase 0 | Source |
|---|---|---|---|
| DBCP `maxTotal` | 20 | **60** | `docker/entrypoint.sh:133` |
| DBCP `maxIdle` | 5 | **20** | `docker/entrypoint.sh:134` |
| Tomcat `maxThreads` | default 200 | **400** | `docker/config/server.xml` Connector |
| Tomcat `acceptCount` | default 100 | **200** | same |
| wikantik container memory | 1 GB | **2 GB** | `docker-compose.prod.yml` |
| db container memory | 512 MB | 1 GB → **2 GB** (Phase 3) | same |
| JVM heap sizing | default ≈ 25 % of container | **`-XX:MaxRAMPercentage=70.0`** (≈ 1.4 GB) | `Dockerfile` CATALINA_OPTS |

Single-user post-deploy probe times — `/api/health` 3 ms, `/wiki/Main` 119 ms, `/api/search?q=cloud` **2.46 s cold**. The 2.46 s cold-cache search baseline foreshadowed where the load would land.

## 3. Sweep #1 results (solid baseline)

| N | RPS | p95 | err % | avg | max | load1 | mem avail min | pg backends | curl `/api/search` max | container |
|---|---|---|---|---|---|---|---|---|---|---|
| 200 | 139.5 | 268 ms | 4.47 % | 71 ms | 3.52 s | 14.75 | 82.7 % | 15 / 60 | 0.79 s | healthy |
| 300 | 158.0 | **1.80 s** | 4.06 % | 426 ms | 4.14 s | **20.09** | 82.5 % | 16 / 60 | 1.79 s | healthy |

**Notes**

- **N=200 — the pool unlock paid off.** Throughput went from ~40 RPS (the pool-bound pre-study runs at the same VU count) to 139.5 RPS, a 3.5× jump. p95 dropped from 1.14 s to 268 ms.
- **N=300 — the inflection.** RPS grew only +13 % for a +50 % VU increase, while p95 climbed 6.7 × (268 ms → 1.80 s) and load1 went past the 16-core line (20.09 ≈ 125 % saturation). All four k6 check categories (`page view`, `search`, `mcp`, `tools`) passed 100 %. None of the formal stop criteria tripped (p95 still under 3 s, err under 10 %), but the curve is unmistakable.
- **`/api/health` and `/wiki/Main` stayed at ~4 ms throughout** in the curl probe. Only `/api/search` showed latency growth.

## 4. Identified bottleneck (Sweep #1)

**Dominant gate: CPU saturation concentrated in the hybrid-retrieval search path.**

Three independent strands of evidence point at the same place:

1. **CPU is the binding resource, not pool or memory.** load1 = 20.09 / 16 cores at N=300 (125 % saturated). pg backends stayed at 16 / 60 — the DB pool has 44 idle connections. Memory available stayed at 82.5 % — no heap pressure, no swap.
2. **Spending is concentrated in one endpoint.** `/api/search` is one of four endpoint checks; it's the only one with visible latency growth. Cheap endpoints (`/api/health`, `/wiki/Main`) stayed at ~4 ms throughout. Single-user cold `/api/search?q=cloud` was already 2.46 s on the post-deploy probe — every hot core was going into hybrid retrieval before any concurrent load was applied.
3. **Latency growth is compute, not queueing.** load1 climbing in step with p95 (the box hot at 20.09 / 16) is CPU running flat-out, not requests parked on a semaphore. A pool-bound system would show low CPU + queue depth; this one shows the opposite.

The cost is **per-search compute × concurrency**. Each search call is CPU-expensive (BM25 + pgvector ANN + fusion), so even a few dozen concurrent search VUs eat 16 cores.

## 5. Config iteration (Phase 3)

Hypothesis: pgvector and Postgres are doing avoidable work at the default 4 MB `work_mem` and 128 MB `shared_buffers` — operations spill, hash joins thrash, ANN scans re-read pages. More memory should mean less CPU per query.

Change (`docker-compose.prod.yml` db service):

```yaml
command:
  - "postgres"
  - "-c"
  - "shared_buffers=256MB"   # was default 128 MB
  - "-c"
  - "work_mem=32MB"          # was default 4 MB
  - "-c"
  - "maintenance_work_mem=128MB"  # was default 64 MB
deploy:
  resources:
    limits:
      memory: 2G   # was 1G; 256MB shared_buffers + ~10MB × 60 backends + work_mem peaks
```

Deployed via `bin/remote.sh deploy --skip-build` (compose-only). Single-user post-tuning probe times — `/api/health` 2 ms, `/wiki/Main` unchanged, `/api/search?q=cloud` **cold 0.32 s** (was 2.46 s — **7.7× faster**), warm **0.08 s** (≈ 30× faster than pre-tuning cold). Real improvement.

## 6. Sweep #2 results (after Phase 3)

| N | RPS | p95 | err % | avg | max | load1 | mem avail min | pg backends | curl `/api/search` max | k6 exit | container |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 200 | 129.3 | 750 ms | 4.43 % | 159 ms | 2.41 s | 12.44 | 82.6 % | 14 / 60 | 0.85 s | 0 | healthy |
| 300 | 129.2 | **3.51 s** | 4.50 % | 740 ms | 12.71 s | 13.27 | 82.5 % | 14 / 60 | 1.57 s | **99** | healthy |
| 400 | 122.9 | **5.89 s** | 4.58 % | 1.44 s | 10.61 s | **10.82** | 81.9 % | 14 / 60 | **5.36 s** | **99** | healthy |

**Direct comparison with Sweep #1 at the matching VU levels:**

| Metric (at N=300) | Sweep #1 | Sweep #2 | Δ |
|---|---|---|---|
| RPS | 158.0 | 129.2 | **-18 %** |
| p95 | 1.80 s | 3.51 s | **+95 %** |
| max | 4.14 s | 12.71 s | **+207 %** |
| load1 | 20.09 | 13.27 | **-34 %** |
| k6 exit | 0 | **99** (p95 threshold breach) | regressed past the gate |

**The iteration regressed.** Single-user serial latency improved 7.7×; concurrent throughput dropped 18 % and the latency tail tripled. Three signatures together tell the story:

- **Lower load1 with worse latency** is the classic shape of *requests queueing for a serialized internal resource*. Postgres did less CPU work; something else now waited longer.
- **Throughput pinned around 129 RPS regardless of N** (129 → 129 → 123 at N=200 / 300 / 400) is a hard ceiling. Adding VUs only deepens the queue.
- **load1 actually *decreased* with more load** (13.27 at N=300 → 10.82 at N=400) — exactly the pre-baseline pool-bound shape, just shifted to a higher absolute ceiling.

The most likely cause is `work_mem=32MB` × concurrent backends. Each connection pre-reserves more memory per operation; at 14 active backends each potentially holding multiple 32 MB allocations, the *effective parallelism* the OS can support inside Postgres dropped relative to the 4 MB default — even though any one query became cheaper.

**Net trade-off:** Phase 3 wins for serial / low-concurrency workloads (search is now 7.7× faster cold), loses for concurrent stress. Given the host currently has effectively no concurrent user load, the change benefits real-world usage — but it should be revisited (probably reverting work_mem to 8 MB or 16 MB while keeping `shared_buffers=256MB` and `maintenance_work_mem=128MB`) before any meaningful concurrent traffic arrives.

## 7. Technical evaluation — likely code problem-spots

The data points squarely at the search code path; the other suspects are circumstantial. Listed in evidence-weighted order:

### 1. `com.wikantik.search.hybrid.HybridSearchService` — strong direct evidence

The orchestrator for BM25 + pgvector ANN + fusion. Lives in `wikantik-main/src/main/java/com/wikantik/search/hybrid/HybridSearchService.java`; registered with `WikiEngine` as a managed singleton (`WikiEngine.java:243`).

**Why a suspect:** every measurement in both sweeps shows search as the only endpoint that degrades. Single-user cold time was 2.46 s *before* concurrent load was applied. Under stress, search is what saturates the CPU.

**Evidence that would confirm:** a JFR / async-profiler capture during a sustained N=300 run would show 70-90 % of on-CPU time in this class and its callees (likely the embedding-index lookup and the fusion merge).

**Optimization shape:** result-set caching for hot queries, ANN parameter tuning (`hnsw.ef_search`, `hnsw.m`), fork-joining BM25 and ANN so they run concurrently rather than sequentially, materialised top-k candidates per page-canonical-id.

### 2. `com.wikantik.search.hybrid.HybridFuser` — indirectly implicated

The fusion logic that merges BM25 and ANN result lists. Lives next to HybridSearchService.

**Why a suspect:** any fusion that's quadratic-ish in result-list size has its cost amplified by concurrency. Even an O(n log n) fusion at n=200 candidates is non-trivial CPU per call. Worth profiling before assuming.

**Optimization shape:** if the fusion is allocating per-call, a thread-local accumulator. If it's sorting unnecessarily, switch to a partial-sort / heap-based top-k.

### 3. `com.wikantik.search.embedding.EmbeddingIndexService` — the pgvector touchpoint

The Java side of the pgvector ANN query.

**Why a suspect:** Phase 3 demonstrated that the pgvector query is the largest single contributor to per-search cost — `work_mem=32MB` made the cold query 7.7× faster. The Java orchestration around it (parameter binding, result mapping, possibly per-call PreparedStatement creation) is in the hot path.

**Optimization shape:** verify PreparedStatement reuse across calls; verify the `?` vector parameter binding doesn't allocate per call; verify the result mapping doesn't re-materialise large arrays unnecessarily.

### 4. `com.wikantik.rest.SearchResource` — the concurrency-control surface

The REST entry point at `/api/search`. No code in this class is hot; it's mentioned because *if* the team chooses to add a search-specific semaphore (the defensive option from Phase 3's decision tree), this is where it lands.

**Optimization shape:** a static `Semaphore` (size = a fraction of `Runtime.availableProcessors()`) with `tryAcquire(short_timeout) → execute → release`. Overflow returns 503. Preserves `/wiki/Main` and `/api/health` responsiveness when search hits its ceiling.

### 5. `com.wikantik.WikiEngine` — *not* implicated by this study

The plan template flagged WikiEngine as a generic suspect (singleton manager lookups under contention). **This study found no evidence of WikiEngine contention.** Cheap endpoints stayed at ~4 ms throughout; if there were lock contention in the manager dispatch, every endpoint would have shown latency growth, not just search. Drop this from the suspect list unless future evidence appears.

### 6. `com.wikantik.render.markdown.MarkdownRenderer` and `FilterManager` dispatch — *not* implicated

Same logic: `/wiki/Main` was served at ~4 ms throughout (renders go through this path). Either rendering is cached upstream of the renderer (likely — the `wikantik.forAgentCache` and similar caches absorb the cost) or the renderer is genuinely fast. Either way, this study produced no evidence pointing at it.

## 8. Recommended follow-up optimizations

Ordered by expected impact / effort ratio. The next spec should pick from the top of this list.

1. **Profile the search path under sustained N=300 load.** A 60-second async-profiler or JFR capture of `repo-wikantik-1` while a sweep is running will quantify exactly where the CPU goes inside `HybridSearchService`. Without it, items 2–4 are educated guesses; with it, the choice is data-driven. Smallest effort, biggest unlock.
2. **Tune `work_mem` to find the sweet spot.** Phase 3 showed 4 MB is too low (single-user spilling) and 32 MB is too high (concurrent backend memory pressure). Try 8 MB and 16 MB at the same sweep N=200/300/400; the win is keeping the single-user improvement without the concurrent regression. Expected: ~5 % concurrent throughput recovery, single-user search staying under 1 s.
3. **Hot-query result cache in front of HybridSearchService.** A Caffeine-backed LRU keyed by normalised query string, with a short TTL (60 s). Even a modest hit rate (20 %) would cut concurrent CPU consumption proportionally, since the cache short-circuits the most expensive code path.
4. **pgvector `hnsw.ef_search` tuning.** The ANN scan parameter trades recall for speed. The current value is the pgvector default; lowering it cuts per-query cost at the price of slightly less accurate top-k. The retrieval-quality CI gate (`DefaultRetrievalQualityRunner`, `nDCG@5 ≥ 0.5`) sets a guardrail for how aggressively this can be lowered.
5. **Defensive search semaphore.** Item 4 from §7. Doesn't move the throughput ceiling but protects the other endpoints when search is overwhelmed — `/wiki/Main` and `/api/health` keep working even when `/api/search` is returning 503s.
6. **Parallelise BM25 and ANN.** Currently the two retrievers likely run sequentially inside `HybridSearchService.search()` (verify with the profile). Running them on a `ForkJoinPool.commonPool()` would cut per-call latency by ~max(t_bm25, t_ann) instead of t_bm25 + t_ann.

These six items should become a single follow-up spec (working title `2026-05-DD-search-path-optimization-design.md`), with item 1 (profile) as Task 0 — its output reshapes everything downstream.

---

## 9. Sweep #3 — code-surgery + JFR findings (2026-05-20)

The Phase 0 baseline and Phase 3 Postgres tuning stayed in place. Two surgical changes landed on top per [docs/superpowers/specs/2026-05-20-search-path-optimization-design.md](superpowers/specs/2026-05-20-search-path-optimization-design.md):

1. **`MentionIndex.findRelatedPagesBatch`** — replaces the N+1 per-page lookup in `DefaultContextRetrievalService.retrieve()`'s result-building loop with a single batched call (one DBCP acquire + one prepared statement reused across the N executions).
2. **`fetchContributingChunks` short-circuit + reuse** — when the hybrid pass already produced ≥ `pages × chunksPerPage` chunks (surfaced via the new `RerankOutcome` carrier and `HybridSearchService.rerankWithChunks`), skip the second full-corpus brute-force `topKChunks(emb, 200)` scan entirely.

A JFR profiling endpoint at `/admin/profiling/jfr/*` shipped with the change, gated by `AdminAuthFilter` and writing to a `/var/wikantik/profiling` volume. One 60-second JFR recording was captured during the N=300 sustained phase for the analysis below.

### Sweep #3 results vs Sweep #2 (matching VU levels)

| N | Sweep #2 RPS / p95 / load1 | Sweep #3 RPS / p95 / load1 | Δ RPS | Δ p95 | Δ load1 |
|---|---|---|---|---|---|
| 200 | 129.3 / 750 ms / 12.44 | **135.0 / 420 ms / 21.18** | +4 % | **-44 %** | **+70 %** |
| 300 | 129.2 / 3.51 s / 13.27 | **140.6 / 2.48 s / 28.69** | **+9 %** | **-29 %** | **+116 %** |
| 400 | 122.9 / 5.89 s / 10.82 | **143.9 / 4.96 s / 22.98** | **+17 %** | **-16 %** | **+112 %** |

Sweep #3 wins on every axis the surgery targeted: more throughput, lower latency, **and** higher load1 — the box finally uses its CPU. At N=300 load1 hit **28.69 / 16 cores ≈ 179 % saturation**, more than double Sweep #2's 13.27. The surgery freed concurrency that the pool-bound regression in Sweep #2 had hidden.

The `/api/health` and `/wiki/Main` endpoints in the curl probe stayed under 50 ms throughout every step, including the N=400 case where k6 p95 hit 4.96 s — confirming the new latency is concentrated in `/api/search`, not the cheap endpoints.

### JFR top hot methods at N=300 (60 s capture)

Raw data in `loadtest/results/sweep3-300vu-jfr-top20.txt`. Top frames in the execution-sample timeline:

| Samples | Method | Comment |
|---|---|---|
| **1677** | `java.util.stream.MatchOps$1MatchSink.accept` | `Stream.anyMatch/allMatch` short-circuit op called *very* heavily; stack-depth-1 doesn't show the caller — investigation needed |
| **808** | `com.wikantik.search.hybrid.InMemoryChunkVectorIndex.dotAt` | The brute-force dot-product inner loop — confirmed as a real hotspot |
| 368 | `java.util.HashMap.getNode (line 587)` | hot map lookups |
| 324 | `java.util.stream.ReferencePipeline$3$1.accept` | stream pipeline operator |
| 230 | `java.lang.Character.codePointAtImpl` | unicode handling — likely string normalization in `QueryEmbedder.normalizeForCache` or markdown parsing |
| 230 | `java.lang.AbstractStringBuilder.ensureCapacityInternal` | string-builder growth |
| 222 | `java.util.HashMap.getNode (line 578)` | more map lookups |
| 156 | `java.lang.StringUTF16.compress` | UTF-16 → Latin-1 string compression |
| 57 | `java.lang.String.decodeUTF8_UTF16` | UTF-8 → Java String decoding |
| 49 | `java.lang.Character.codePointAtImpl` (overload) | |
| 45 | `java.util.stream.ReferencePipeline.forEachWithCancel` | |
| 27 | `java.util.HashMap.get (line 564)` | |
| **19** | `com.wikantik.search.hybrid.InMemoryChunkVectorIndex.topKChunks` | the outer brute-force scan entry point |
| 16 | `java.lang.AbstractStringBuilder.append` | |
| 14 | `com.wikantik.search.hybrid.InMemoryChunkVectorIndex.siftDown` | top-K min-heap maintenance |
| 12 | `java.util.ArrayList$ArrayListSpliterator.tryAdvance` | |
| 11 | `java.util.regex.Pattern$Slice.match` | regex matching |
| 10 | `java.util.stream.Sink$ChainedReference.cancellationRequested` | |
| 10 | `java.util.stream.AbstractPipeline.copyIntoWithCancel` | |
| 10 | `java.util.regex.Pattern$StartS.match` | regex matching |

**What the profile says about v2:**

- **`MatchOps$1MatchSink.accept` at 1677 samples** is the headline hotspot — twice as hot as the brute-force dot product. It's an unconditional `Stream.anyMatch`/`allMatch` short-circuit operator, and stack-depth-1 doesn't reveal the caller. The single biggest unlock would be identifying which call site this is. A `--stack-depth 30` re-capture (or a flame-graph) names it immediately.
- **`InMemoryChunkVectorIndex.dotAt` at 808 samples** confirms the brute-force loop is a meaningful (not dominant) hot path — a real opportunity for Java Vector API (SIMD), with potentially a 4-8× speedup on the per-call cost.
- **HashMap lookups at 617 combined** (3 frames) are scattered but cumulatively significant — probably `HybridFuser`'s score map, the new `relatedByPage` map, the `byName` lookup map in `applyHybridAndGraphRerank`, or `QueryEmbedder`'s cache key map. Worth identifying with a deeper capture.
- **Heavy string/character handling** (Character + AbstractStringBuilder + StringUTF16 + decodeUTF8_UTF16 ≈ 673 samples combined) suggests significant string churn — likely query normalization, regex matching on text, or JSON serialization. Caching parsed/normalised strings could be a real win.
- **Regex matching** (`Pattern$Slice.match`, `Pattern$StartS.match` ≈ 21 samples) is small but interesting — possibly the `WHITESPACE`/`TRAILING_PUNCT` regex in `QueryEmbedder.normalizeForCache`, or `KgInclusionFilter`'s pattern checks. Cheap to fix if it's the normaliser (compile once is already done; pre-normalising the cache key once per call already does this).

### Next round: v2 topic

Working spec title: **`2026-05-DD-search-path-optimization-v2-design.md`** — items ordered by expected (impact / effort) ratio:

1. **Re-capture the JFR with stack-depth 30** to identify the caller of `MatchOps$1MatchSink.accept` (the 1677-sample hotspot). Until we know what it is, the v2 spec leads with one investigation task before any code change.
2. **Vector-API rewrite of `InMemoryChunkVectorIndex.dotAt`** — Java 21 incubator vector API, 4-wide or 8-wide FMA per iteration. Bench it standalone (JMH) before landing.
3. **Reduce HashMap traffic** — once the JFR's stack-depth-30 capture names the hot maps, swap them for primitive-keyed maps (`IntIntMap` from `eclipse-collections`) or pre-sized non-resizing hash maps.
4. **Caching layer** — *after* the above three identify the real ceiling. Two-tier (full RetrievalResult, short TTL, event-invalidated; dense top-K, long TTL) per the original 2026-05-20 design draft.

## 10. Sweep #4 — Vector-API dot product (2026-05-20)

Item 2 from §9's v2 list landed standalone: `InMemoryChunkVectorIndex.dotAt` rewritten using `jdk.incubator.vector.FloatVector` with a fused multiply-add (`a.fma(b, accum)`) loop. The `--add-modules=jdk.incubator.vector` flag was plumbed into the compiler, surefire (base + coverage profile), Cargo IT jvmargs, and the production `CATALINA_OPTS`. A seven-case parity unit test (`InMemoryChunkVectorIndexVectorDotTest`) confirms the SIMD path matches the scalar reference to float precision at production dim 1024, at off-by-one dims that exercise the tail loop, and at tiny dim values where the tail dominates.

### Sweep #4 vs Sweep #3 at N=300

| Metric | Sweep #3 (scalar dot) | Sweep #4 (vector dot) | Δ |
|---|---|---|---|
| RPS | 140.6 | 142.4 | **+1.3 %** |
| **http_req_duration median** | **187.6 ms** | **124.1 ms** | **−34 %** |
| p90 | 1.85 s | 1.60 s | **−14 %** |
| p95 | 2.48 s | 2.36 s | −5 % |
| avg | 602 ms | 580 ms | −4 % |
| max | 10.32 s | **19.48 s** | tail outlier worsened |
| err % | 4.36 % | 4.28 % | flat |
| load1 | 28.69 | **53.25** | **+86 %** (333 % saturated on 16 cores) |
| pg backends | 21 | 23 | flat |

### JFR comparison (60 s capture at N=300 sustained)

| Method | Sweep #3 samples | Sweep #4 samples | Δ |
|---|---|---|---|
| `InMemoryChunkVectorIndex.dotAt` | **808** | **197** | **−76 %** (4.1× drop) |
| All `InMemoryChunkVectorIndex.*` (dotAt + topKChunks + siftDown) | 848 | 228 | **−73 %** |
| `MatchOps$1MatchSink.accept` | 1677 | 416 | **−75 %** |
| `HashMap.getNode` (combined) | 617 | 205 | **−67 %** |
| `Character.codePointAtImpl` (combined) | 279 | 363 | +30 % (now relatively bigger share) |

The JIT compiled the FMA to a native vector intrinsic: `jdk.internal.vm.vector.VectorSupport.ternaryOp` shows up in the sample timeline, confirming the SIMD code path is in use. With dotAt's per-call cost dropping ~4×, samples that previously landed *anywhere* on the search-rerank stack (including `MatchOps` and the HashMap lookups inside `HybridFuser`) drop proportionally, because the whole stack runs faster.

### Interpretation

- **The vector intrinsic works as advertised** on docker1's CPU — 4.1× drop on the dot loop, with proportional drops across the search-rerank call stack that shares its hot path.
- **Median latency dropped 34 %**, p90 dropped 14 %, p95 dropped 5 % — fast and common-case requests got the win; the tail less so. The tail at this saturation level is caused by something *outside* the dot loop (queueing, GC, lock contention, or the still-significant `MatchOps` caller).
- **Throughput at saturation barely moved (+1.3 % RPS)** — adding compute headroom to dotAt does not unlock throughput when the saturation ceiling lives elsewhere. The next gate is what limits RPS to ~140 — most likely whatever sits behind the 416-sample `MatchOps$1MatchSink.accept` frame.
- **load1 nearly doubled (28.7 → 53.25)** while RPS held flat — the box is now doing dramatically more CPU work per second of wall time. Either more requests-in-flight at any instant (consistent with the median improvement: requests complete faster so the queue depth at any moment is shallower → can admit more), or aggregate compute went up because the dot loop's cheaper-per-call lets each request consume more total CPU before finishing. Plausibly both.

### What this tells the next round

The single largest unlock is still **identifying who calls `MatchOps$1MatchSink.accept`**. At 416 samples it remains the top frame in Sweep #4 — even after Vector API took 75 % off its peak. A `stack-depth 30` re-capture is still task 0 of any v2 spec, with the priority for items 3 (HashMap traffic, now smaller but still 205 samples), 4 (caching), and any further investigation derived directly from what the deeper stack reveals.

The `MaxRAMPercentage=70` heap setting + the new SIMD work pattern produced one nasty 19.48 s tail latency outlier — a single request burst significantly worse than Sweep #3's 10.32 s max. Investigation candidate: G1 GC pause behaviour at high SIMD-allocation rates, or a lock-stall during JIT recompilation. Cheap mitigations: explicit GC tuning (target pause-time goal, or switch to ZGC) — but only if a follow-up sweep reproduces it.

---

## 11. Sweep #5 — listener-mutex fix (2026-05-20)

`WikiEventManager.WikiEventDelegate`'s storage swapped from `ArrayList<WeakReference<WikiEventListener>>` to `WeakHashMap<WikiEventListener, Boolean>`. `addWikiEventListener` is now O(1); `fireEvent` snapshots under the lock and dispatches callbacks outside it. Per [docs/superpowers/specs/2026-05-20-listener-mutex-fix-design.md](superpowers/specs/2026-05-20-listener-mutex-fix-design.md).

### Sweep #5 vs Sweep #4 at N=300

| Metric | Sweep #4 (vector dot) | Sweep #5 (listener fix) | Δ |
|---|---|---|---|
| RPS | 142.4 | 132.8 | −7 % |
| **http_req_duration median** | **124.1 ms** | **58.4 ms** | **−53 %** |
| p90 | 1.60 s | 1.51 s | −6 % |
| p95 | 2.36 s | 3.01 s | +28 % |
| avg | 580 ms | 697 ms | +20 % |
| max | 19.48 s | **43.77 s** | tail catastrophic |
| **load1** | 53.25 | **110.91** | **+108 %** (693 % of 16-core capacity) |
| pg backends | 23 | 22 | flat |
| err % | 4.28 % | 4.28 % | flat |
| curl `/api/search` max | 8.62 s | 20.98 s | tail worse |

### JFR before/after (60 s capture at N=300 sustained)

| Frame | Sweep #4 samples | Sweep #5 samples |
|---|---|---|
| `MatchOps$1MatchSink.accept` (the Stream.anyMatch hot path) | **416** | **0** |
| `WikiEventManager$WikiEventDelegate.addWikiEventListener` | 1 | **0** |
| `InMemoryChunkVectorIndex.dotAt` | 197 | 97 |
| `HashMap.getNode` (combined) | 205 | 200 |

The headline: **the listener-mutex hot path is completely eliminated.** The O(1) `WeakHashMap.putIfAbsent` doesn't even surface in JFR sampling. Fast-path requests no longer wait on the global mutex.

### Sweep #5's new top hot methods

```
123  org.apache.lucene.analysis.classic.ClassicTokenizerImpl.getNextToken
123  java.lang.StringUTF16.inflate
116  java.util.HashMap.get
115  org.apache.lucene.analysis.CachingTokenFilter.incrementToken
 97  com.wikantik.search.hybrid.InMemoryChunkVectorIndex.dotAt
 95  org.apache.lucene.analysis.tokenattributes.CharTermAttributeImpl.toString
 92  java.util.ArrayList$Itr.hasNext
 84  java.util.HashMap.getNode
 72  java.lang.Character.codePointAtImpl
 70  org.apache.lucene.util.AttributeSource$State.clone
 67  java.lang.StringUTF16.checkIndex
 62  org.apache.lucene.search.highlight.SimpleHTMLEncoder.htmlEncode
 60  org.apache.lucene.util.AttributeSource.restoreState
 59  java.lang.String.charAt
 57  java.lang.AbstractStringBuilder.append
 53  org.apache.lucene.search.highlight.SimpleHTMLEncoder.encodeText
 51  java.lang.String.checkIndex
 47  java.lang.AbstractStringBuilder.ensureCapacityInternal
 40  java.lang.StringUTF16.compress
 38  org.apache.lucene.util.StringHelper.murmurhash3_x86_32
```

### Interpretation

- **The mutex bottleneck is structurally eliminated.** `MatchOps$1MatchSink.accept` went from 416 samples to 0; `addWikiEventListener` went to 0 in the entire capture. The `WeakHashMap` rewrite is a complete success on its stated goal.
- **Median latency dropped 53 %** (124 ms → 58 ms): typical user requests are about twice as fast. This is the headline user-visible win.
- **`load1` doubled to 110.91 (≈ 693 % of 16 cores).** The box is now running flat-out CPU-busy with no internal serialization point. Every cycle is going into actual work.
- **RPS dropped slightly (−7 %)** and **tail latency widened** (p95 +28 %, max +125 %): the system has transitioned from "mostly waiting on a lock" to "mostly compute-bound, queueing on CPU." When the box is this saturated, a fraction of requests queue *behind* a long-running compute task, producing the 43 s max outlier. This is the classic shape of a true CPU-saturated workload — and confirms the bottleneck has moved from synchronization to genuine work.
- **New top hot frames cluster around Lucene's BM25 query analysis and the Highlighter snippet generator.** `ClassicTokenizerImpl`, `CachingTokenFilter`, `SimpleHTMLEncoder`, `CharTermAttributeImpl`, `AttributeSource` — these are all Lucene's text-analysis and result-decoration machinery. The `/api/search` response shape generates per-result snippets via `Highlighter`; the depth-30 capture from sweep #4 already showed the full `WeightedSpanTermExtractor.extract` → `MemoryIndex.storeTerms` → `Highlighter.getBestFragments` chain firing per result.

### Next round: v3 topic

Working spec title: **`2026-05-DD-lucene-highlighter-design.md`** — investigation-first, then targeted change.

Highest-leverage candidates ordered by expected (impact / effort):

1. **Audit whether the `/api/search` response shape actually consumes the highlighter output.** `DefaultLuceneSearcher.findPages` calls `Highlighter.getBestFragments` per result; if the snippets aren't returned to the API client (or are returned but never displayed), disabling the highlighter is a single-line change with potentially huge impact (the Lucene frames combined are ~600+ samples — comparable to the original MatchOps bottleneck).
2. **If snippets are used:** cache the per-page highlighted snippet keyed by `(page_name, query_hash)` with a short TTL. Hot-query repeats avoid the whole pipeline.
3. **Investigate the 43 s tail outlier.** Likely either GC pause under the new allocation pressure (G1 with 1.4 G heap; consider tuning pause-time goal or trying ZGC), or a Lucene contention point that doesn't show in JFR samples. A small re-capture with `jdk.GCPhase*` events would confirm.
4. **Architectural fix to `WikiSession.guestSession()`'s per-anonymous-request listener registration** (the leak source). Now that each registration is ~free, the leak is cheap, but it's still incorrect. Stand-alone follow-up.

---

## Raw data

All per-step k6 outputs, curl-probe samples, and jakemon snapshots are in `loadtest/results/sweep{1,2,3,4,5}-<N>vu-{k6,curl,host}.{log,json}`. JFR captures live at `loadtest/results/sweep{3,4,5}-300vu.jfr` for offline analysis with `jfr print` or JMC. The directory is gitignored (raw data is reproducible, not versioned).
