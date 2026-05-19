# Wikantik Scaling Characterization — Design (v1)

**Date:** 2026-05-19
**Status:** Approved — ready for implementation plan
**Scope:** One full cycle (baseline bake → first sweep → analysis → one targeted config iteration → second sweep → written report). Code-level optimizations are explicitly deferred to a follow-up spec.

## Background

Wikantik runs in a Docker container on host `docker1` (16 cores, 1 GB container memory limit, behind a 20-connection DBCP pool and Tomcat's default 200-thread connector). Initial stress testing against the host revealed a pool-bound throughput plateau: at 200 VUs (round 1) docker1 ran at load1=18/16 ≈ saturated, but at 600 VUs the CPU went *idle at 75%* with a 50/50 user/system split — clear evidence the box was queueing behind the DBCP semaphore well below the hardware's capacity. The wiki was sleeping behind a small locked door.

This study (a) establishes a "solid baseline" configuration that lets the box actually use its resources, (b) sweeps load against that baseline to find the first real impact threshold, (c) diagnoses the bottleneck and applies one targeted iteration, (d) re-sweeps, and (e) produces a written characterization report identifying probable code-level problem spots for follow-up optimization.

The host is in production but currently has no meaningful user traffic, so we can hammer it without coordination.

## Goals

1. Find the actual scaling ceiling of wikantik on docker1 once configured to use its hardware.
2. Produce a written, technically grounded scaling-characteristics report — methodology, results, identified gates, code suspects.
3. Set up the next iteration cycle: the report should make the *next* optimization spec obvious.

## Non-goals (deferred to follow-up specs)

- Implementing code-level optimizations. v1 *identifies* the suspect code paths; a separate spec/plan does the surgery.
- Multi-round config tuning beyond the single targeted iteration in Phase 3.
- Write-mutating load tests (`--writes`). All runs stay read-only.
- Multi-host horizontal scaling. Single-box vertical characterization only.

## Methodology

### Phase 0 — Solid-baseline config

One persistent config change so docker1 can exercise its resources, then build + deploy via `bin/remote.sh deploy`.

| Knob | Location | From | To | Rationale |
|---|---|---|---|---|
| DBCP `maxTotal` | `docker/entrypoint.sh` (Resource block in generated ROOT.xml) | 20 | **60** | 3× DB connections; well under Postgres' default `max_connections=100` |
| DBCP `maxIdle` | same | 5 | **20** | Keeps a third of the pool warm |
| Tomcat `maxThreads` | `docker/config/server.xml` Connector | (default 200) | **400** | 2× concurrent request capacity |
| Tomcat `acceptCount` | same | (default 100) | **200** | Larger TCP accept backlog so excess connections queue instead of dropping |
| wikantik container memory | `docker-compose.prod.yml` | 1G | **2G** | Room for the larger thread pool + heap |
| db container memory | `docker-compose.prod.yml` | 512M | **1G** | Room for 60 concurrent Postgres backends |
| JVM heap | `Dockerfile` `CATALINA_OPTS` | default (~25 % of container) | **`-XX:MaxRAMPercentage=70.0`** | ~1.4 G heap of the new 2 G limit; actually uses the box |

Verification before sweeping: container healthy, `/api/health` returns 200, Tomcat startup log shows the new Connector parameters.

### Phase 1 — Sweep

The same `load` profile (`bin/loadtest.sh load --vus N --duration 5m`) at increasing VU counts:

- Start: **500 VUs**
- Increment: **+100** per step
- Sustain: **5 minutes** at each step
- Stop criterion — **any** of:
  1. http_req_duration **p95 > 3 s** sustained over the 5-min window
  2. http_req_failed **rate > 10 %**
  3. docker1 **load1 > 24** (1.5× the 16-core count — genuine CPU saturation, not just queueing)
  4. wikantik container **healthcheck flap** (transition to unhealthy or container restart)
- Safety cap: **stop after 1500 VUs** even if no impact has appeared.

### Ground-truth real-user latency probe

The previous round produced a notable disconnect: pages were unloadable but the Grafana panels showed flat latency. To resolve this every sweep step runs a **parallel external curl probe** for the duration of the step:

- Every 1 second, time three GETs from outside k6 against docker1 — `/api/health` (cheapest), `/wiki/Main` (full page render path), `/api/search?q=cloud` (the known-expensive hybrid retrieval path).
- Each request capped at `--max-time 30`.
- Output: timestamp, endpoint, HTTP code, total time → `loadtest/results/curl-probe-<vus>vu.log` for the run.

This data is independent of k6's own measurements and independent of jakemon's dashboard panels, so it cannot lie about real-user perceived latency. It also captures *what happens during a timeout* (60 s hangs that never finish never enter k6's histogram either).

### Phase 2 — Analyze

For the step that triggered the stop criterion, cross-reference:

- **k6 output:** http_req_duration p50/p95/p99 split by check (`page view`, `search`, `mcp`, `tools`), http_req_failed rate, sustained req/s
- **jakemon host metrics over the step's window:** CPU busy % and user/system split, load1, memory available, network I/O, disk I/O
- **Postgres metrics:** `pg_up`, `pg_stat_database_numbackends` (active connections), `pg_stat_database_xact_commit` rate
- **curl probe log:** real-user latency over time per endpoint; this is the source of truth for the user-visible experience

Goal: identify the dominant gate. Likely candidates by what shows up:

- DB pool re-exhausted (pg active connections pinned at 60 for the full window) → bump pool further or queue search.
- CPU-bound (load1 climbing past cores, user CPU dominant) → bump CPU allocation; identify the hot code path from `/metrics` histograms.
- Memory-bound (heap pressure, GC pauses long, container OOM-kill) → tune heap / GC.
- Endpoint-localised (only search times out, everything else fine) → search-path concurrency limit or caching.

### Phase 3 — One targeted iteration

Apply **one** targeted change based on the analysis. Examples:

- DB pool re-exhausted → DBCP `maxTotal: 60 → 100` and Postgres `max_connections: 100 → 200`.
- CPU-bound → bump container `cpu` reservation, identify the hot path via `/metrics`.
- Search-localised → add a search concurrency limiter (in-process semaphore) — borderline code change, but a small enough one that v1 can accommodate it.

Deploy. Re-sweep the affected VU range (the step that triggered + the two on either side). Capture the same metrics for comparison.

### Phase 4 — Write the report

`docs/ScalingCharacterization.md` (engineering doc at a memorable repo-root path). Sections:

1. **Methodology** — what we ran, exact parameters, what counts as "impact"
2. **Solid baseline config** — the Phase 0 values and why
3. **Sweep #1 results** — table of VUs × (throughput, p95, error %, load1, CPU busy, memory, pg backends) plus a one-line note per row
4. **Identified bottleneck** — the dominant gate at the impact step, with the supporting evidence
5. **Config iteration** — what we changed in Phase 3 and why
6. **Sweep #2 results** — same shape as #1 over the affected range
7. **Technical evaluation of likely code problem-spots** — specific files / classes the next ceiling will hit; based on observed behaviour plus a reading of the relevant code paths. Initial suspects to inspect at minimum: `com.wikantik.knowledge.retrieval.HybridRetrievalQueryService`, `com.wikantik.rendering.MarkdownRenderer` plus the FilterManager dispatch, the structural-spine resolution path, `WikiEngine` singleton-managed manager lookups, and the `/for-agent` projection memoisation. Each gets a short paragraph: why it's a suspect, what evidence would confirm it, what optimization shape would help.
8. **Recommended follow-up optimizations** — bulleted, ordered by expected impact / effort ratio. Becomes the input to the next spec.

## Component boundaries

The work decomposes into small, independently runnable units:

- **`docker/` config edits** (Phase 0) — entrypoint.sh, server.xml, docker-compose.prod.yml, Dockerfile. Each one targeted, one knob per file edit.
- **`bin/remote.sh deploy`** — already-built tool. Single invocation per config iteration.
- **`bin/loadtest.sh`** — already-built harness, with the recent fix to `--vus`/`--duration` env vars. Drives k6.
- **`bin/curl-probe.sh`** (new, small) — the external real-user latency probe. ~30 lines of bash, takes a duration and an output prefix.
- **`docs/ScalingCharacterization.md`** — the deliverable. Built up section-by-section as data lands; final form at the end.

## Risks and notes

- **Live host, no users yet.** Hammering is sanctioned. If real users start arriving mid-study (unlikely given current state) we pause.
- **Container OOM during the heap bump.** New JVM `MaxRAMPercentage=70` of 2 G ≈ 1.4 G heap. With 400 threads × ~1 MB stacks ≈ 400 MB + native + GC overhead, we're at ~1.9–2.0 G — close to the 2 G limit. If OOM-killed during Phase 0 we drop heap to 60 %.
- **Postgres `max_connections=100`.** A future DBCP bump past ~80 needs `max_connections` raised first (Phase 3 only if needed). DB container restart on `max_connections` change.
- **Run-to-run noise.** Single 5-min runs per step. If a step looks anomalous (e.g. p95 suddenly jumps then drops next step) we repeat once for confirmation rather than chase ghosts.
- **One config iteration.** If Phase 3 doesn't move the needle, the report says so honestly and the follow-up spec runs more iterations.

## Verification / done criteria

v1 is done when:

- `docs/ScalingCharacterization.md` exists, has all eight sections filled in, and lists at least three specific code suspects with concrete file/method references.
- Both sweeps' raw data is preserved in `loadtest/results/` (k6 logs + curl-probe logs + a JSON dump of the relevant jakemon Prometheus queries).
- The Phase 0 config bake is committed to `main` so it's the durable baseline going forward.
- The follow-up code-optimization spec has a topic name agreed (e.g. `2026-05-DD-search-path-optimization-design.md`) — its body is out of scope.
