# Search-Path Optimization — Design (v1)

**Date:** 2026-05-20
**Status:** Approved — ready for implementation plan
**Predecessor:** [docs/superpowers/specs/2026-05-19-wikantik-scaling-characterization-design.md](2026-05-19-wikantik-scaling-characterization-design.md) — the scaling study that produced this spec's hotspot list.
**Successor (anticipated):** A v2 spec keyed by JFR profile evidence (`2026-05-DD-search-path-optimization-v2-design.md`) — its scope is decided by what the profile shows, not in advance.

## Background

The 2026-05-19 scaling characterization at the docker1 solid baseline established that wikantik's gate at N=300 VUs is CPU saturation in the Java code path (`load1=20.09/16 cores`; the Postgres container peaked at **0.01 cores**). A first-pass code review of `HybridSearchService` and its callers pointed at two concrete inefficiencies in `DefaultContextRetrievalService.retrieve()` *beyond* the brute-force dot loop:

1. `fetchContributingChunks` runs a *second* full-corpus brute-force scan (`InMemoryChunkVectorIndex.topKChunks(emb, 200)`) even when the caller doesn't want contributing chunks and even when the first dense scan already produced enough material.
2. `fetchRelatedPages` is called once per result page (N+1 lookup pattern).

A query-result cache was also proposed during the review. We are **deliberately not building caches in this spec** — the realistic production hit rate is unknown, and a cache that absorbs the synthetic-load hit rate of ~100% on `q=cloud` would mask the actual cost we want to see. Cache design becomes a v2 spec once we have JFR evidence on where time *actually* goes in the realistic cache-miss path.

The spec ships a JFR profiling endpoint so the next round of optimisation can be evidence-driven rather than hypothesis-driven.

## Goals

1. **Ship a JFR profiling toggle.** An admin endpoint that starts/stops Java Flight Recorder recordings during live load, with the output persisted to a volume for offline analysis with `jfr print` or JMC.
2. **Land the two code surgeries** (`fetchContributingChunks` short-circuit + reuse, `fetchRelatedPages` N+1 → batch) without changing the search-result shape.
3. **Re-sweep at the same VU levels as Sweep #2** (200, 300, 400), take one JFR capture during the N=300 sustained phase, and produce a §9 addendum to `docs/ScalingCharacterization.md`. The addendum names the next v2 topic.

## Non-goals (explicitly deferred)

- **Query-result caching** (both outer full-result and inner dense top-K from the earlier review draft). Let the JFR profile justify the cache shape, or show that batched I/O / SIMD / a graph-rerank fix beats caching for the realistic workload.
- **async-profiler / pprof.** Same admin-endpoint shape can host it later as a parallel class; not built here.
- **SIMD / Java Vector API on the dot loop.** Profile-first.
- **`GraphRerankStep` optimisation.** Profile-first (this class wasn't read in detail in the predecessor review).
- **HNSW / ANN-approximation** to replace brute force. 12 K chunks brute-force at ~3 ms/query is not the gate.
- **Config tuning beyond what's already on `main`.** Phase 0 + Phase 3 stay; no new tuning round here.

## Design

### 1. JFR profiling endpoint

A small backend service plus REST admin resource. Both default off — no recording active at boot, no thread pool spun up unless an endpoint is hit.

**Backend — `wikantik-observability/src/main/java/com/wikantik/observability/JfrProfilingService.java`:**

- Methods: `start(int durationSeconds, String label) → recordingId`, `stop(recordingId)`, `list() → List<RecordingInfo>`, `download(recordingId) → InputStream + filename`.
- Uses `jdk.jfr.Recording` programmatic API. One concurrent recording at a time (rejects a second `start` with HTTP 409 + clear message).
- Recording configuration: JFR's built-in `default` template (~1 % overhead). A future `profile` knob is out of scope.
- `durationSeconds`: required, max 600. Open-ended recordings are rejected.
- Files written as `/var/wikantik/profiling/wikantik-<ISO-timestamp>-<label>.jfr`. Directory created on first start, configurable via `wikantik.profiling.dir` property (default `/var/wikantik/profiling`).
- Size cap: reject `start` if the directory already holds > 5 GB of `.jfr` files (operator must clean up first). Pure safety; reasonable for a manual diagnostic flow.
- Returns a `RecordingInfo` record carrying recordingId, startTime, durationSeconds, label, filePath, sizeBytes (after stop), and status (RUNNING / FINISHED / FAILED).

**REST surface — `wikantik-rest/src/main/java/com/wikantik/rest/admin/ProfilingResource.java`:**

- `POST /admin/profiling/jfr/start` — JSON body `{ "duration_s": <1..600>, "label": "<optional>" }`. Returns `RecordingInfo`.
- `POST /admin/profiling/jfr/stop` — JSON body `{ "recording_id": "<id>" }`. Returns `RecordingInfo` with status FINISHED.
- `GET /admin/profiling/jfr/recordings` — list current + finished recordings.
- `GET /admin/profiling/jfr/recordings/{id}` — stream the `.jfr` file as `application/octet-stream`.
- All four protected by `AdminAuthFilter` (requires `AllPermission`), per the existing admin-endpoint convention.

**Servlet mapping:** the SPA-routes-need-dual-registration memory applies in reverse — admin REST is server-side and needs only the `web.xml` mapping (no SPA route). One `<servlet>` + `<servlet-mapping>` in `wikantik-rest/src/main/webapp/WEB-INF/web.xml` plus the resource's `getMapping()` implementation.

**Volume mount:** `docker-compose.prod.yml` `wikantik` service grows a `wikantik-profiling:/var/wikantik/profiling` named volume. Out-of-container access is via `bin/container.sh shell` + `docker cp`, or by exposing it through the download endpoint (preferred for the common case).

### 2. `fetchContributingChunks` surgery

In `wikantik-knowledge/src/main/java/com/wikantik/knowledge/DefaultContextRetrievalService.java`, two guards in front of the second `chunkIndex.topKChunks(embedding.get(), 200)` call (current line ~207):

1. **Skip-when-not-wanted:** if `chunksPerPage <= 0`, return `Map.of()` immediately. The current code already enters `fetchContributingChunks` from `retrieve()`, but the early-return inside protects callers that pass 0.
2. **Reuse-when-sufficient:** the method receives the BM25-ordered + hybrid-fused `ordered: List<SearchResult>`. If we plumb the first dense retrieval's `List<ScoredChunk>` (currently discarded inside `HybridSearchService.fuseWithEmbedding`) up to `retrieve()` and pass it in, *and* the first scan's `chunkTop` ≥ `ordered.size() × chunksPerPage`, the second full-corpus scan is redundant — group the existing chunks by interesting page name instead.

The plumb-up requires either (a) returning `List<ScoredChunk>` from `HybridSearchService.rerank(...)` alongside the fused page names, or (b) caching the first scan's chunks on a `ThreadLocal` (rejected — fragile under thread pools). Option (a) implies an API surface change.

**Decision:** introduce a new return type `RerankOutcome` that carries both the fused `List<String>` and the optional `List<ScoredChunk>`. `HybridSearchService.rerank(...)` becomes deprecated and forwards to `rerankWithChunks(...)`; existing tests/callers compile unchanged.

### 3. `fetchRelatedPages` N+1 → batch

In `DefaultContextRetrievalService`, the current per-page call (`fetchRelatedPages(page.getName())` inside the result-building loop) is replaced by a single pre-computed map:

- Before the result-building loop, collect the candidate page names into a `List<String>` once.
- Call a new batched method `fetchRelatedPagesBatch(List<String>) → Map<String, List<RelatedPage>>`.
- Inside the loop, look up by page name from the map (`Map::get`, O(1)).

The current `fetchRelatedPages` implementation must be located during the implementation plan to identify the actual data source (page-graph index? PageManager? a SQL query?). The batched version is its data-source-aware sibling — likely a single SQL `WHERE page_name = ANY(?)` query or one batched page-graph traversal.

### 4. Re-sweep + JFR capture + addendum

Identical structure to Sweep #2's procedure in the predecessor plan (Task 3 step procedure). For each N in {200, 300, 400}:

1. Start `bin/curl-probe.sh 360 loadtest/results/sweep3-${N}vu-curl`.
2. Run `bin/loadtest.sh load --vus ${N} --duration 3m`.
3. Capture k6 + curl + jakemon snapshots as before.

During the N=300 sustained phase, fire a 60-second JFR recording:

```
curl -u admin:<pw> -X POST http://192.168.0.4:8080/admin/profiling/jfr/start \
     -H 'Content-Type: application/json' \
     -d '{"duration_s":60,"label":"sweep3-300vu"}'
```

After the run, download the `.jfr` via the endpoint, run `jfr print --events jdk.ExecutionSample --stack-depth 30 <file>` to extract the top hot methods, and paste the top-20 (collapsed by method) into the addendum.

Append a **§9. Sweep #3 — code-surgery + JFR findings** section to `docs/ScalingCharacterization.md` containing:

- A row-for-row Sweep #3 vs Sweep #2 comparison table at N ∈ {200, 300, 400}.
- The JFR top-20 collapsed-method excerpt.
- A one-paragraph "what this profile says about v2" naming the next spec's topic.

## Component boundaries

Each new class has one clear responsibility and is testable in isolation:

| Class | Owns | Depends on |
|---|---|---|
| `JfrProfilingService` | start/stop/list/download of JFR recordings; file lifecycle | `jdk.jfr.Recording`; a `Path` for the recording dir |
| `ProfilingResource` | REST surface; auth (delegated to filter); error responses | `JfrProfilingService`; `RestServletBase` |
| `RerankOutcome` (record) | Immutable carrier for fused names + optional chunk evidence | — |
| `DefaultContextRetrievalService` (modified) | The two guards + the batched related-pages call | existing collaborators + new `RerankOutcome` plumbing |

Tests:
- Unit: `JfrProfilingServiceTest` — start/stop/duration-cap/concurrent-rejection/size-cap behaviours, against a temp directory.
- Unit: `ProfilingResourceTest` — Mockito-driven; covers happy paths + 409 (concurrent), 400 (bad duration), 404 (unknown recording id).
- Integration (Cargo-launched IT): `ProfilingResourceIT` — start a 2-second recording, poll until FINISHED, download, assert non-empty + magic bytes (`FLR\0`).
- Unit: `DefaultContextRetrievalServiceTest` — the existing tests must keep passing (search-result shape preserved). New tests assert `fetchContributingChunks` short-circuits at `chunksPerPage=0`, reuses chunks when sufficient, falls through otherwise.
- Unit: `FetchRelatedPagesBatchTest` (new) — assert the batched method returns the same per-page map as the previous N+1 loop for a representative input set.

## Risks and notes

- **JFR overhead on prod traffic.** JFR's `default` configuration is ~1 % CPU on typical workloads — safe for production. The endpoint refuses `profile`-template recordings in v1 because that template can cost a few percent. If the v2 profile reveals a finer-grain need, the toggle adds easily.
- **Code-surgery must not change search-result shape.** Both modifications in `DefaultContextRetrievalService` are guarded with unit tests asserting identical `RetrievedPage` lists for representative queries against a fixture corpus. The retrieval-quality CI gate (`RetrievalQualitySmokeTest`, `nDCG@5 ≥ 0.5`) remains the cross-cutting safety net.
- **`RerankOutcome` API change touches callers.** Every `HybridSearchService.rerank(...)` caller in production code is updated; tests reference the old method through a deprecated `default` forwarder so existing test fixtures don't churn. A separate v2 cleanup spec can remove the deprecated forwarder.
- **Volume permissions on docker1.** `/var/wikantik/profiling` is created by the container on first use; the user inside the container (`root` for the wikantik image) owns the directory. No host-side permission setup needed.
- **Concurrency model.** One recording at a time. If a developer hits `start` while a recording is running, the endpoint returns 409 with the running recording's id — straightforward.
- **Predecessor config stays.** Phase 0 baseline + Phase 3 Postgres tuning remain on `main` even though the predecessor report acknowledged the Phase 3 tuning likely didn't help. Reverting it is a follow-up spec, not this one.

## Done criteria

v1 is done when:

- `docs/ScalingCharacterization.md` has a §9 section with the Sweep #3 table, the JFR top-20 excerpt, and the named v2 topic.
- A representative `.jfr` recording from the N=300 sustained phase is preserved in `loadtest/results/sweep3-300vu.jfr` (gitignored, present locally and on docker1 for follow-up analysis).
- The two `DefaultContextRetrievalService` modifications + the JFR endpoint are committed to `main` and deployed to docker1; the existing `RetrievalQualitySmokeTest` plus the new unit + IT tests are all green in the full integration-test reactor (`mvn clean install -Pintegration-tests -fae`).
- The v2 follow-up spec topic is agreed in conversation (its body is out of scope).
