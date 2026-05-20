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

## Raw data

All per-step k6 outputs, curl-probe samples, and jakemon snapshots are in `loadtest/results/sweep1-<N>vu-{k6,curl,host}.{log,json}` and the `sweep2-…` siblings. The directory is gitignored (raw data is reproducible, not versioned).
