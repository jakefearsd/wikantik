# Wikantik Scaling Characterization Implementation Plan (v1)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (recommended for empirical/research tasks — each task's outcome shapes the next) or superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Characterize wikantik's scaling behaviour on docker1 from a solid-resource baseline, identify the first real ceiling, apply one targeted config iteration, re-measure, and deliver a written report identifying code-level problem spots for follow-up.

**Architecture:** One persistent config bake (DBCP/threads/memory/heap) → external curl probe + k6 sweeps starting at 500 VUs (+100/step, 5 min each) until impact → analyse → one targeted iteration → re-sweep → written deliverable at `docs/ScalingCharacterization.md`.

**Tech Stack:** Docker Compose, k6 v2.0.0 (load harness), bash (curl probe + orchestration), jakemon central Prometheus (`192.168.0.10:9090`), Java 21 / Tomcat 11 inside the wikantik container.

---

## Spec

Design spec: `docs/superpowers/specs/2026-05-19-wikantik-scaling-characterization-design.md`

## File Structure

**Persistent edits (Phase 0 baseline — committed to `main`):**
- `docker/entrypoint.sh` — DBCP `maxTotal` 20→60, `maxIdle` 5→20
- `docker/config/server.xml` — Tomcat Connector adds `maxThreads="400"`, `acceptCount="200"`
- `docker-compose.prod.yml` — wikantik memory 1G→2G, db memory 512M→1G
- `Dockerfile` — `CATALINA_OPTS` adds `-XX:MaxRAMPercentage=70.0`

**New tooling (small, focused):**
- `bin/curl-probe.sh` — external real-user latency probe (~40 lines bash, one job: sample 3 endpoints/sec, log to file)

**Raw-data outputs (preserved):**
- `loadtest/results/sweep1-<N>vu-k6.log` — k6 stdout per step
- `loadtest/results/sweep1-<N>vu-curl.log` — curl probe per step
- `loadtest/results/sweep1-<N>vu-host.json` — jakemon Prometheus snapshot per step
- `loadtest/results/sweep2-…` — Phase 3 re-sweep over affected range
- `loadtest/results/.gitignore` — ignores all of the above (raw data is reproducible, doesn't belong in git)

**Deliverable:**
- `docs/ScalingCharacterization.md` — the eight-section report

---

### Task 1: Bake the solid-baseline config (Phase 0)

**Files:**
- Modify: `docker/entrypoint.sh:133-134`
- Modify: `docker/config/server.xml:19-24`
- Modify: `docker-compose.prod.yml:15`, `docker-compose.prod.yml:23`
- Modify: `Dockerfile:87`

- [ ] **Step 1: Bump DBCP pool in `docker/entrypoint.sh`**

The Resource block (~lines 125-138) generates ROOT.xml at container start. Change `maxTotal="20"` to `maxTotal="60"` and `maxIdle="5"` to `maxIdle="20"`. The two lines currently read:

```
              maxTotal="20"
              maxIdle="5"
```

Replace with:

```
              maxTotal="60"
              maxIdle="20"
```

- [ ] **Step 2: Add Tomcat Connector capacity in `docker/config/server.xml`**

The current Connector (line 19-24) reads:

```xml
    <Connector port="8080"
        protocol="HTTP/1.1"
        connectionTimeout="20000"
        address="0.0.0.0"
        xpoweredBy="false"
        allowTrace="false" />
```

Replace with (adds `maxThreads`, `acceptCount`):

```xml
    <Connector port="8080"
        protocol="HTTP/1.1"
        connectionTimeout="20000"
        address="0.0.0.0"
        maxThreads="400"
        acceptCount="200"
        xpoweredBy="false"
        allowTrace="false" />
```

- [ ] **Step 3: Bump container memory in `docker-compose.prod.yml`**

The `db` service block has `memory: 512M` (line 15). Change to `memory: 1G`. The `wikantik` service block has `memory: 1G` (line 23). Change to `memory: 2G`.

- [ ] **Step 4: Add JVM heap percentage in `Dockerfile`**

Line 87 reads:

```dockerfile
ENV CATALINA_OPTS="-Djava.security.egd=file:/dev/./urandom"
```

Replace with:

```dockerfile
ENV CATALINA_OPTS="-Djava.security.egd=file:/dev/./urandom -XX:MaxRAMPercentage=70.0"
```

- [ ] **Step 5: Validate the prod compose still renders**

Run: `DB_HOST_BIND=172.17.0.1 docker compose -f docker-compose.yml -f docker-compose.prod.yml config >/dev/null && echo OK`
Expected: `OK`. Inspect the rendered output for the new `memory` values on db (1G) and wikantik (2G).

- [ ] **Step 6: Commit**

```bash
git add docker/entrypoint.sh docker/config/server.xml docker-compose.prod.yml Dockerfile
git commit -m "perf: bake the solid-baseline config for the scaling study

DBCP maxTotal 20→60, maxIdle 5→20; Tomcat maxThreads default→400,
acceptCount default→200; wikantik container 1G→2G, db container
512M→1G; JVM heap -XX:MaxRAMPercentage=70.0 so heap auto-sizes to
~1.4G of the new 2G container. Baseline for the scaling
characterization in docs/superpowers/specs/2026-05-19-wikantik-scaling-characterization-design.md.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

- [ ] **Step 7: Deploy via `bin/remote.sh deploy`**

Run: `bin/remote.sh deploy`
This rebuilds the docker image (config files are baked in via Dockerfile COPY), ships it, and recreates the wikantik + db containers on docker1 with the new compose. Expected: health-poll passes; the script reports success. Allow ~10 minutes.

- [ ] **Step 8: Verify the new config is active on docker1**

Run:
```bash
echo "=== container memory limits ==="
ssh jakefear@docker1 'docker inspect repo-wikantik-1 repo-db-1 --format "{{.Name}}: {{.HostConfig.Memory}}"'
echo "=== Tomcat connector config (from access log line) ==="
ssh jakefear@docker1 'docker logs repo-wikantik-1 2>&1 | grep -i "started Connector" | tail -1'
echo "=== JVM flags ==="
ssh jakefear@docker1 'docker exec repo-wikantik-1 ps -ef | grep -o "MaxRAMPercentage=[0-9.]*"'
echo "=== DBCP maxTotal in the generated ROOT.xml ==="
ssh jakefear@docker1 'docker exec repo-wikantik-1 grep -E "maxTotal|maxIdle" /usr/local/tomcat/conf/Catalina/localhost/ROOT.xml'
echo "=== health under no load ==="
curl -fsS -o /dev/null -w "health -> %{http_code}, %{time_total}s\n" http://192.168.0.4:8080/api/health
```
Expected: wikantik memory = `2147483648` (2 G), db = `1073741824` (1 G); `MaxRAMPercentage=70.0`; `maxTotal="60"` and `maxIdle="20"`; health 200 in < 50 ms.

If any check fails, **stop and report** — do not proceed to the sweep against an unconfirmed baseline.

- [ ] **Step 9: Push**

Run: `git push origin main`

---

### Task 2: Build the external curl probe

**Files:**
- Create: `bin/curl-probe.sh`

- [ ] **Step 1: Write the probe script**

Create `bin/curl-probe.sh` with this exact content (executable bash; one job, two args, three endpoints):

```bash
#!/usr/bin/env bash
# curl-probe.sh — external real-user latency probe for the scaling study.
#
# Samples three endpoints once per second for a given duration, recording
# (timestamp, endpoint, HTTP code, total seconds) to <prefix>.log. Runs
# outside k6 so the data is independent of what k6 sees and of the
# server-side metrics histograms. Useful when the dashboard claims fine
# latencies but pages won't load.
#
# Usage: bin/curl-probe.sh <duration-seconds> <output-prefix>
#   BASE_URL env var overrides the default (docker1 prod).
set -uo pipefail

DUR="${1:?duration seconds required}"
OUT="${2:?output prefix required (path without .log)}"
BASE="${BASE_URL:-http://192.168.0.4:8080}"

mkdir -p "$(dirname "${OUT}")"
LOG="${OUT}.log"
: > "${LOG}"  # truncate

END=$(( $(date +%s) + DUR ))
while (( $(date +%s) < END )); do
  ts=$(date +%s.%3N)
  for ep in "/api/health" "/wiki/Main" "/api/search?q=cloud"; do
    out=$(curl -sS -o /dev/null -w "%{http_code}\t%{time_total}" \
            --max-time 30 "${BASE}${ep}" 2>/dev/null || echo $'ERR\t30.000000')
    printf '%s\t%s\t%s\n' "${ts}" "${ep}" "${out}" >> "${LOG}"
  done
  sleep 1
done
```

- [ ] **Step 2: Make executable and smoke-test**

Run:
```bash
chmod +x bin/curl-probe.sh
bin/curl-probe.sh 5 /tmp/probe-smoke
cat /tmp/probe-smoke.log
```
Expected: 15 lines (5 seconds × 3 endpoints), each with a timestamp, endpoint, HTTP code (200 or 404), and a sub-second `time_total`. If any line shows `ERR` or HTTP 500+, debug before proceeding.

- [ ] **Step 3: Add `loadtest/results/.gitignore`**

Create `loadtest/results/.gitignore` to keep raw run artifacts out of git:

```gitignore
# Raw scaling-study artifacts — reproducible, large, not versioned.
*.log
*.json
```

Also add a `.keep` marker so the dir is tracked but empty: `mkdir -p loadtest/results && touch loadtest/results/.keep`.

- [ ] **Step 4: Commit**

```bash
git add bin/curl-probe.sh loadtest/results/.gitignore loadtest/results/.keep
git commit -m "tool: add bin/curl-probe.sh for external real-user latency sampling

Samples /api/health, /wiki/Main, /api/search?q=cloud once per second for
the requested duration, capturing HTTP code and total time per request to
a log file. Independent ground-truth source for the scaling study — the
Grafana panels missed real-user latency during the earlier 600-VU run.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 3: Run Sweep #1 against the solid baseline

**Files:**
- Outputs: `loadtest/results/sweep1-<N>vu-k6.log`, `loadtest/results/sweep1-<N>vu-curl.log`, `loadtest/results/sweep1-<N>vu-host.json`

This task is an iterative procedure. At each VU level, you (a) start the curl probe in the background, (b) run the k6 step, (c) capture jakemon host metrics for the window, (d) decide whether to continue to the next step. Records and decisions stay in this conversation; raw artifacts land in `loadtest/results/`.

**Sweep parameters:**
- Levels: 200, 300, 400, 500, 600, 700, 800, 900, 1000, 1100, 1200, 1300, 1400, 1500. Starting low so the first stress signals are visible against a known-quiet baseline.
- Per step: `bin/loadtest.sh load --vus N --duration 3m` (uses the WIKANTIK_VUS / WIKANTIK_DURATION env vars after the recent fix). Total wall-clock per step ≈ 6 min (2m ramp + 3m sustained + 1m down).
- Curl probe duration: 360 s (6 min — covers the whole step including ramp + ramp-down).
- Stop after **any** of:
  - k6 reports http_req_duration `p(95) > 3s`
  - k6 reports `http_req_failed rate > 0.10` (10%)
  - docker1 `load1 > 24` sustained over the 5-min sustained phase
  - `docker ps` shows `repo-wikantik-1` unhealthy or restarted during the step
- Safety cap: stop after `N=1500` regardless.

- [ ] **Step 1: Sanity-check pre-conditions**

```bash
# Health 200, low latency from cold
curl -fsS -o /dev/null -w "%{http_code} %{time_total}\n" http://192.168.0.4:8080/api/health
# pg_up = 1 from jakemon
ssh inference 'curl -s localhost:9090/api/v1/query --data-urlencode "query=pg_up{host=\"docker1\"}" | grep -o "\"[01]\"\]"'
# k6 binary present
k6 version | head -1
# loadtest.env still pointed at docker1
grep "^BASE_URL=" loadtest/loadtest.env
```
Expected: health 200, pg_up=1, k6 v2.0.0 present, BASE_URL=`http://192.168.0.4:8080`.

- [ ] **Step 2: Per-VU-level run procedure**

For each `N` in 200, 300, …, 1500 in order, run this exact procedure. Stop the loop on the first step that hits any of the stop criteria above. Pay particular attention to the low-end transition steps — the first emergence of any non-trivial latency tail (e.g. p90 first crossing 200 ms, or the first curl-probe sample over 1 s on `/wiki/Main`) gets recorded as a notable observation even before the formal stop criteria fire.

```bash
N=500   # update each iteration
PREFIX="loadtest/results/sweep1-${N}vu"

# (a) Start the curl probe in background (360s window).
bin/curl-probe.sh 360 "${PREFIX}-curl" >/dev/null 2>&1 &
PROBE_PID=$!

# (b) Record start time for the jakemon query later.
START_TS=$(date +%s)

# (c) Run the k6 load step (foreground; ~6 min wall-clock).
bin/loadtest.sh load --vus "${N}" --duration 3m > "${PREFIX}-k6.log" 2>&1
K6_EXIT=$?

# (d) Wait for the curl probe to finish.
wait "${PROBE_PID}"
END_TS=$(date +%s)

# (e) Quick post-step summary from k6.
echo "=== sweep1 N=${N} k6 thresholds + totals ==="
grep -A2 -E "THRESHOLDS|TOTAL RESULTS|http_req_duration|http_req_failed|http_reqs|checks_failed" "${PREFIX}-k6.log" | tail -40

# (f) jakemon host metrics over the step window.
RANGE=$((END_TS - START_TS))
ssh inference "curl -s localhost:9090/api/v1/query --data-urlencode \"query=max_over_time((100 - (avg(rate(node_cpu_seconds_total{host=\\\"docker1\\\",mode=\\\"idle\\\"}[1m])) * 100))[${RANGE}s:30s])\"" > "${PREFIX}-host.json"
ssh inference "curl -s localhost:9090/api/v1/query --data-urlencode \"query=max_over_time(node_load1{host=\\\"docker1\\\"}[${RANGE}s])\"" >> "${PREFIX}-host.json"
ssh inference "curl -s localhost:9090/api/v1/query --data-urlencode \"query=min_over_time((node_memory_MemAvailable_bytes{host=\\\"docker1\\\"} / node_memory_MemTotal_bytes{host=\\\"docker1\\\"} * 100)[${RANGE}s:30s])\"" >> "${PREFIX}-host.json"
ssh inference "curl -s localhost:9090/api/v1/query --data-urlencode \"query=pg_up{host=\\\"docker1\\\"}\"" >> "${PREFIX}-host.json"
echo "--- host snapshot ---"
cat "${PREFIX}-host.json"

# (g) Container health check.
ssh jakefear@docker1 'docker ps --filter name=repo-wikantik-1 --format "{{.Status}}"'

# (h) Curl-probe quick read: how many requests failed (HTTP != 2xx) and the worst latencies.
echo "--- curl probe summary ---"
awk -F'\t' '{ if($3 !~ /^2/) bad++; if($4+0 > max) max=$4 } END { printf "samples=%d bad=%d max_latency=%.3fs\n", NR, bad+0, max+0 }' "${PREFIX}-curl.log"

# (i) Manual stop-criterion check:
#   - p95 from the k6 THRESHOLDS line: is it > 3s?
#   - failed rate from THRESHOLDS: is it > 10%?
#   - load1 from host.json: > 24?
#   - container status: still 'healthy'?
# If any YES, this is the impact step — break out of the loop.
```

After each step, record the row (VUs / RPS / p95 / err% / load1 / CPU busy peak / mem available min / pg_up / max curl latency) in a running notes block. That table becomes section 3 of the final report.

- [ ] **Step 3: Note the impact step (or the safety-cap final step)**

Once stopped, identify which VU level is "the impact step" — the first one that triggered. Record it explicitly:

> Sweep #1 impact step: N=____, triggered by ____ (which criterion), at which the wiki showed ____ (one-sentence symptom).

If the safety cap (N=1500) is reached without any criterion firing, record:

> Sweep #1 reached the 1500-VU safety cap without breaching any criterion. The solid-baseline config handles ≥1500 read-only VUs.

---

### Task 4: Analyse Sweep #1 (Phase 2)

**Files:**
- Read: every `loadtest/results/sweep1-*` artifact
- Output: in-conversation notes that feed Task 5's decision and Task 7's report

- [ ] **Step 1: Cross-reference all four data sources for the impact step**

For the impact step `N`, open and read:
- `loadtest/results/sweep1-${N}vu-k6.log` — full k6 output (THRESHOLDS, TOTAL RESULTS, per-check pass rates, http_req_duration percentiles split by `expected_response:true`/all)
- `loadtest/results/sweep1-${N}vu-curl.log` — real-user latency per endpoint over time; specifically check whether `/wiki/Main` and `/api/search` diverged from `/api/health`
- `loadtest/results/sweep1-${N}vu-host.json` — CPU busy peak, load1, memory available min, pg_up
- jakemon Postgres metric live (in case the embedded exporter exposed it): `pg_stat_database_numbackends{host="docker1"}` over the window

- [ ] **Step 2: Classify the dominant gate**

Pick exactly one (with a short justification):

- **DB pool re-exhaustion** — symptoms: `/api/search` and `/wiki/Main` curl latencies climb together while `/api/health` stays flat; CPU stays well below saturation (load1 < ~12); Postgres backends pinned at 60.
- **CPU saturation** — symptoms: load1 climbs through and past 16 toward 24; CPU busy approaches 100%; user-time dominant (≥70% of CPU).
- **Memory pressure / GC** — symptoms: GC pauses visible in container logs; memory available falls below ~20%; latency tail expands.
- **Endpoint-localised** — symptoms: only `/api/search` curl latency climbs; `/wiki/Main` and `/api/health` stay flat; CPU has headroom; pool not pinned.

Write a 3-5 sentence diagnosis to use in Task 7 section 4.

---

### Task 5: One targeted config iteration (Phase 3)

**Files:** depend on the diagnosis from Task 4. Decision tree:

| Diagnosis (Task 4) | Change to make in Task 5 |
|---|---|
| DB pool re-exhaustion | `docker/entrypoint.sh` DBCP `maxTotal: 60 → 100`, `maxIdle: 20 → 30` AND add `command: ["postgres", "-c", "max_connections=200"]` to the `db` service in `docker-compose.prod.yml` |
| CPU saturation | Skip Phase 3 (no easy bump on the same host). Document the ceiling honestly in the report; Phase 3 becomes "(none — host CPU is the gate)". |
| Memory pressure | `Dockerfile` `MaxRAMPercentage=70.0 → 60.0`, AND `docker-compose.prod.yml` wikantik memory `2G → 3G` |
| Endpoint-localised (search) | Add a `SearchConcurrencyLimiter` semaphore wrapping the search REST resource. **This bends "code optimizations deferred" — only do it if Task 4 unambiguously implicates search.** |

- [ ] **Step 1: Apply the targeted change**

Make the edits dictated by the row that matches Task 4's diagnosis. Each row is small and focused.

If "CPU saturation" was the diagnosis: **skip to Step 4 (Commit) and note in the message that Phase 3 is intentionally a no-op.** Re-sweeping wouldn't move the ceiling — the host is the limit.

For the "Endpoint-localised (search)" row, the concurrency-limiter sketch (only applied if data demands it):

- Add a class `com.wikantik.rest.SearchConcurrencyLimiter` in `wikantik-rest/src/main/java/com/wikantik/rest/SearchConcurrencyLimiter.java` that holds a static `Semaphore` sized from a property (`wikantik.search.max_concurrent`, default 20). Wrap calls in `SearchResource` (the REST handler) with `tryAcquire(timeout) → execute → release`; on timeout, return HTTP 503 with a short JSON body. Add the property to `wikantik-main/src/main/resources/ini/wikantik.properties` with a leading comment.
- Add one unit test: a Mockito-driven check that a 21st concurrent call (with limit=20) returns 503.

- [ ] **Step 2: Validate locally**

For pure config edits (DB pool / memory / heap rows): `DB_HOST_BIND=172.17.0.1 docker compose -f docker-compose.yml -f docker-compose.prod.yml config >/dev/null && echo OK`.

For the code row (semaphore): `mvn -pl wikantik-rest -am clean install -DskipITs` (a fast targeted build that compiles + runs the new unit test).

- [ ] **Step 3: Deploy**

```bash
bin/remote.sh deploy
```
Same ~10-minute round-trip. Verify the new config / code is active using the same Step 8 checks from Task 1 (adapted for whatever changed).

- [ ] **Step 4: Commit + push**

```bash
git add -- <the specific files changed>
git commit -m "perf: phase-3 targeted iteration — <one-line summary of what changed and why>

<2-3 lines: which diagnosis from Task 4 this addresses, expected effect.>

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
git push origin main
```

---

### Task 6: Run Sweep #2 over the affected range

**Files:**
- Outputs: `loadtest/results/sweep2-<N>vu-{k6,curl,host}.{log,json}`

- [ ] **Step 1: Define the re-sweep range**

The range is the Sweep #1 impact step ± 2 increments (i.e. 5 levels total). Example: if Sweep #1 impacted at 900 VUs, re-sweep at 700, 800, 900, 1000, 1100.

If Sweep #1 reached the safety cap without impact, Sweep #2 runs at 1500 (one confirmation step) and we stop the study early — there's nothing to iterate against.

If Task 5 was a no-op (CPU saturation), skip Task 6 entirely.

- [ ] **Step 2: Re-sweep with the same procedure as Task 3 Step 2**

Use the same script template, replacing `sweep1` with `sweep2` in the PREFIX. Record the equivalent table.

- [ ] **Step 3: Compare the two sweeps at the same VU level**

For each VU level present in both sweeps, compute: ΔRPS, Δp95, Δerror-rate, Δload1, Δmax-curl-latency. A short side-by-side ("Sweep #1 vs Sweep #2 at 900 VUs") goes into report section 6.

---

### Task 7: Write the characterization report

**Files:**
- Create: `docs/ScalingCharacterization.md`

- [ ] **Step 1: Write the report**

The report has eight sections (per the spec). Each section is 3-10 paragraphs except the result tables. Use this concrete template — fill every blank with real numbers from the artifacts in `loadtest/results/`:

```markdown
# Wikantik Scaling Characterization

**Run date:** 2026-05-19
**Host:** docker1 (16 cores, current container limits below)
**Methodology spec:** [docs/superpowers/specs/2026-05-19-wikantik-scaling-characterization-design.md](superpowers/specs/2026-05-19-wikantik-scaling-characterization-design.md)

## 1. Methodology

(Restate the spec methodology in 3-5 sentences: solid baseline, sweep 500→+100/5m, four-criterion impact gate, external curl probe, one targeted iteration, re-sweep. Link to the spec for details.)

## 2. Solid baseline config

| Knob | Value | Source |
|---|---|---|
| DBCP `maxTotal` | 60 | docker/entrypoint.sh |
| DBCP `maxIdle` | 20 | docker/entrypoint.sh |
| Tomcat `maxThreads` | 400 | docker/config/server.xml |
| Tomcat `acceptCount` | 200 | docker/config/server.xml |
| wikantik container memory | 2 G | docker-compose.prod.yml |
| db container memory | 1 G | docker-compose.prod.yml |
| JVM heap | `MaxRAMPercentage=70.0` (~1.4 G) | Dockerfile CATALINA_OPTS |

(One paragraph: why these values — pointed at by the earlier 600-VU run that showed pool-bound queueing.)

## 3. Sweep #1 results (solid baseline)

| VUs | RPS sustained | p95 (s) | err % | load1 peak | CPU busy peak | mem avail min | pg_up | curl max (s) |
|----|---------------|---------|-------|-----------|--------------|--------------|-------|--------------|
| 500 | … | … | … | … | … | … | 1 | … |
| 600 | … | … | … | … | … | … | 1 | … |
| … | | | | | | | | |

(Then a 1-line note per row pointing out the most notable change vs the previous row.)

## 4. Identified bottleneck (Sweep #1)

(Diagnosis from Task 4 Step 2: which gate, supported by which specific numbers from the table.)

## 5. Config iteration

(What Task 5 changed, and why this should address the bottleneck. Include the exact diff snippet.)

## 6. Sweep #2 results (after iteration)

(Same table as section 3, plus a "Δ vs Sweep #1 at the same VU level" column for the overlap.)

## 7. Technical evaluation — likely code problem-spots

For each of the following code paths, in order of suspicion:

1. **`com.wikantik.knowledge.retrieval.HybridRetrievalQueryService`** — orchestrates BM25 + pgvector queries. (Why a suspect, what evidence in the sweeps points at it, what optimization shape would help — e.g. result-set caching for hot queries, ANN index parameter tuning, parallel-fork-join the two retrievers.)
2. **`com.wikantik.rendering.MarkdownRenderer` + FilterManager dispatch** — synchronous per-render Flexmark parser instantiation. (Why a suspect: rendering is per-page on every uncached page view. Evidence: page-view check timing in k6, GC behaviour. Optimization: pooled `Parser`/`Renderer` instances, cache rendered HTML keyed by `(canonical_id, updated_at_millis)`.)
3. **The structural-spine resolution path** — every save/read may touch it; under load it's a likely hotspot. (Cite the relevant `StructuralSpinePageFilter` / index lookup.)
4. **`WikiEngine` singleton-managed manager lookups** — synchronized accessor under high concurrency. (Evidence: thread contention if visible; mitigation: switch to a final, lazily-initialised holder pattern or move to ConcurrentHashMap-backed lookup.)
5. **`/for-agent` projection memoisation cache** — sized 5K entries with 1 h TTL. (Under load with diverse queries, hit rate may drop. Cite `wikantik.forAgentCache` and recommend either resizing or per-cluster scoping.)

Each entry: why a suspect, what evidence in the sweep data would confirm, what optimization shape would help. Three minimum, five if the data supports it.

## 8. Recommended follow-up optimizations

Bulleted, ordered by expected (impact / effort) ratio. This list is the input to the next spec (`docs/superpowers/specs/2026-05-DD-<topic>-design.md`).

- (item 1)
- (item 2)
- (item 3)
```

- [ ] **Step 2: Spec self-review of the report**

Re-read the report. Confirm:
- Every numeric cell in §3 and §6 is filled in from the artifacts (no `…`, `TBD`, `TODO`).
- §4 names exactly one dominant gate.
- §7 lists at least three code suspects with concrete `package.Class` references (verify each class exists with a quick `grep -rn "class HybridRetrievalQueryService\|class MarkdownRenderer" --include="*.java" .`).
- §8 has at least three bulleted follow-up items.
- All section headings exist (no section accidentally dropped).

Fix any issues inline.

- [ ] **Step 3: Commit**

```bash
git add docs/ScalingCharacterization.md
git commit -m "docs: scaling characterization report — sweep, diagnosis, iteration, code suspects

Phase-0 baseline established and committed; sweep #1 ran <range> at
that baseline; impact step at N=<N> driven by <gate>; Phase-3
iteration <summary>; sweep #2 over <range>. Report identifies <K>
specific code paths as candidates for the next optimisation round.

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 8: Log the work in News.md and push

**Files:**
- Modify: `docs/wikantik-pages/News.md`

Per the project convention, each prod-changing commit gets a News.md entry as its own separate content commit. This study's commits to log are:

- `perf: bake the solid-baseline config for the scaling study` (Task 1)
- `tool: add bin/curl-probe.sh for external real-user latency sampling` (Task 2)
- The Task 5 commit (whose subject depends on the diagnosis — copy it verbatim)
- `docs: scaling characterization report — …` (Task 7)

- [ ] **Step 1: Add the entries**

Read the top of `docs/wikantik-pages/News.md`, add 2026-05-19 entries for each of the four commit subjects in newest-first order, matching the existing entry style.

- [ ] **Step 2: Commit**

```bash
git add docs/wikantik-pages/News.md
git commit -m "content: log the scaling characterization study in News

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

- [ ] **Step 3: Push**

```bash
git push origin main
```

---

## Self-Review

- **Spec coverage:**
  - Phase 0 baseline (spec §Phase 0) → Task 1. ✓
  - External curl probe (spec §Ground-truth real-user latency probe) → Task 2. ✓
  - Phase 1 sweep procedure + impact criteria + safety cap (spec §Phase 1) → Task 3. ✓
  - Phase 2 analyse + classify gate (spec §Phase 2) → Task 4. ✓
  - Phase 3 one targeted iteration with decision tree (spec §Phase 3) → Task 5. ✓
  - Phase 4 deliverable doc at `docs/ScalingCharacterization.md` with all eight sections (spec §Phase 4) → Task 7. ✓
  - Done criteria (raw data preserved in `loadtest/results/`, three code suspects with concrete refs, follow-up topic agreed) → Task 7 self-review covers this; raw data preservation handled by Task 2 Step 3's `.gitignore`/`.keep`. ✓
  - News.md log per project convention → Task 8 (added because the spec didn't mention it explicitly but it's a memorised project convention). ✓
- **Placeholder scan:** Task 5 Step 4 uses `<one-line summary of what changed and why>` and similar inside a commit-message template — that's a template the executor fills in based on which decision-tree row applied, not a placeholder in the plan itself. Same for Task 7's report template (the executor fills in numbers from the raw data; the plan can't enumerate them ahead of time, since this is empirical). No "TBD"/"TODO" leakage that the executor would have no way to resolve.
- **Type/name consistency:** `bin/curl-probe.sh` and its arguments (`<duration-seconds>`, `<output-prefix>`) match between Task 2 and Task 3. The `loadtest/results/sweep<N>vu-{k6,curl,host}` filename convention is used consistently across Tasks 3, 4, 6, 7. The four-criterion stop gate is described identically in spec §Phase 1, plan Task 3 header, and Task 3 Step 2. The Task 5 decision-tree options match exactly the four candidate gates from Task 4 Step 2. No drift.
