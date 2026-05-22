# Load Testing Wikantik

> **Tactical reference** for the k6 harness lives at
> [`loadtest/README.md`](../loadtest/README.md) — install, profiles, flags,
> seeding test data. This document is the **methodology**: when to run a load
> test, what variables to isolate, how to read the results, and how to pair
> k6 with the JVM profiler and Prometheus to actually find bottlenecks.

## What load testing is for

Three concrete uses, in order of how often they come up:

1. **Regression detection on every meaningful change.** A 5-minute smoke at
   the prod VU count proves a deploy didn't tank throughput. The k6 harness
   `--verify` mode also asserts that the right Grafana panels actually moved,
   so you catch broken instrumentation in the same run.
2. **Contention hunting.** When throughput plateaus despite CPU headroom or
   p95 climbs without an obvious cause, a 3-minute run with JFR profiling
   running alongside reveals what threads are actually doing.
3. **Capacity planning.** What VU count is "comfortable"? Where does the
   knee sit? What changes when the corpus grows? A staged sweep at multiple
   VU levels answers these.

Wikantik has been load-tested through several major optimisation arcs; the
running record is in
[`docs/ScalingCharacterization.md`](ScalingCharacterization.md). Append your
own findings there.

## Quick start

```bash
# Once per fresh stack — seeds the testbot admin + API key.
loadtest/seed-loadtest-data.sh --docker-project repo

# Copy the loadtest.env template, paste the credentials the seed script printed.
cp loadtest/loadtest.env.example loadtest/loadtest.env
$EDITOR loadtest/loadtest.env

# 2-minute smoke against the default BASE_URL (set in loadtest.env).
bin/loadtest.sh smoke

# Custom VU count + duration override the profile's defaults.
bin/loadtest.sh smoke --duration 3m --vus 500
```

The three built-in profiles and when to use each — copied from
`loadtest/README.md` for convenience:

| Profile | Shape | Use it for |
|---|---|---|
| `smoke` | constant-VUs for `--duration` (default 2 min, 5 VUs) | regression checks, A/B at a fixed load, sustained-run experiments |
| `load` | ramps to `--vus`, sustains for `--duration`, ramps down | finding steady-state at a target VU |
| `stress` | staged ramp: 25 % → 50 % → 100 % → 0 % over 8 min | finding the knee / capacity ceiling |

All three remote-write k6's own metrics into jakemon's Prometheus
(`192.168.0.10:9090`), so offered load and host response share a timeline in
Grafana.

## Reading the k6 output

The last block k6 prints is the aggregate over the whole run. The fields
that matter, in order of priority:

| k6 line | What it tells you |
|---|---|
| `http_reqs ......... 480.21/s` | The sustained RPS. If you're trying to compare runs, this is the headline number. |
| `http_req_duration ... avg=… med=… p(90)=… p(95)=… max=…` | The latency distribution. p50 (median) shifts with how busy the system is; p95 shifts with how often slow requests happen. Watch BOTH; p95 climbing while p50 is steady is the signature of contention. |
| `http_req_failed ......... 1.75%` | Non-2xx rate. Some failure is expected (the harness deliberately probes MCP/tools endpoints without auth keys to exercise the request path — those legitimately return 4xx and are exempted from the threshold per-request via `responseCallback`). A rate above ~3 % during the read scenario is unusual and worth investigating. |
| `iterations ......... 289463` | Total successful iterations across all VUs. Multiplied by request count per iteration ≈ `http_reqs`. |
| `verify_failures ......... 0` | When you passed `--verify`, this asserts every Grafana panel target actually moved. A non-zero count here means the run "worked" from a request perspective but the metric we care about didn't go up — usually a broken instrumentation regression. |

If you see thresholds get red text — `'p(95)<2000' p(95)=4.19s ✗` — the run
exited non-zero. The k6 process will return a failure exit code; the test
PROFILE (smoke/load/stress) sets the threshold values, you can tweak them in
`loadtest/lib/config.js`.

## A typical investigation workflow

This is the loop the optimisation work in
[`docs/ScalingCharacterization.md`](ScalingCharacterization.md) used:

1. **Establish the baseline.** One sustained smoke at a known-good VU count.
   Record RPS, p50, p95, failure rate, CPU partition, cache hit rates.
2. **Change ONE variable.** A config flip in `.env.prod` is ideal — a
   `--skip-build` redeploy is ~30 s. A code change is fine too but ships an
   image rebuild.
3. **Repeat the smoke.** Same VU count, same duration.
4. **Read the deltas.** RPS up = win. p95 down = win. Both unchanged with
   the host at 99 % CPU = ceiling.
5. **If something's off, capture a JFR.** See § Pairing with JFR below.
6. **Land the change** (or revert), then update
   `docs/ScalingCharacterization.md` with the new measurement row.

A core discipline: **change one variable at a time**. The pgvector cutover
during this codebase's optimisation session shipped multiple changes at once
(pgvector backend + bigger DBCP pool + mmap) and we ended up with a misleading
"+68 % pgvector win" that was actually attributable to the pool bump alone.
The wiring bug that meant pgvector was never actually queried only surfaced
after a forced JFR sweep, three runs later.

## Pairing with JFR

The runtime profiler is a `/admin/profiling/jfr/*` REST endpoint surface,
gated behind `AdminAuthFilter`. The flow:

```bash
TPASS=$(grep test.user.password test.properties | cut -d= -f2)

# Kick off a 220-second recording labeled for later attribution.
curl -s -u testbot:"${TPASS}" -X POST http://docker1:8080/admin/profiling/jfr/start \
    -H 'Content-Type: application/json' \
    -d '{"duration_s":220,"profile":"profile","label":"my-investigation"}' \
    | python3 -m json.tool

# Run the load test inside the JFR window. 220 s covers a 3-min sustained heat
# plus a little slack on either end.
bin/loadtest.sh smoke --duration 3m --vus 650

# Wait ~10 s for auto-stop, or explicitly stop:
curl -s -u testbot:"${TPASS}" -X POST http://docker1:8080/admin/profiling/jfr/stop \
    -H 'Content-Type: application/json' \
    -d '{"recording_id":"<id-from-start-response>"}'

# Pull the file out of the container and onto your laptop.
ssh docker1 'docker cp repo-wikantik-1:/var/wikantik/profiling/wikantik-<label>.jfr /tmp/'
scp docker1:/tmp/wikantik-<label>.jfr /tmp/jfr/

# Now you can `jfr summary`, `jfr print --events …`, or open it in JMC.
jfr summary /tmp/jfr/wikantik-<label>.jfr
```

JFR is ~5–10 % overhead at the `profile` setting. RPS will be slightly lower
under JFR than not — note that when comparing to a non-JFR baseline.

### What to look for in the JFR

Three event types resolve almost every wikantik bottleneck:

| Event | Question it answers | Command |
|---|---|---|
| `jdk.ExecutionSample` | Where is the CPU going? | `jfr print --events jdk.ExecutionSample --stack-depth 12 …` |
| `jdk.JavaMonitorEnter` | Which synchronized blocks are contended? | `jfr print --events jdk.JavaMonitorEnter --stack-depth 6 …` — top-frame counts identify the lock |
| `jdk.ThreadPark` | Why are threads idle? | Mostly noisy (`Unsafe.park`); use depth-4 to see who called park (DBCP borrow, AQS condition, etc.) |

Other events worth knowing about: `jdk.GCPhasePause` (STW durations),
`jdk.SocketRead` (JDBC + HTTP I/O latency), `jdk.ObjectAllocationSample`
(allocation pressure → GC root cause).

A pattern that worked repeatedly in the optimisation arc: top by `jdk.JavaMonitorEnter`
events to find the contended `synchronized` block, fix it (RWLock, Caffeine,
double-checked locking), capture a fresh JFR, see the contention frame fall
out and the next-biggest one move up.

## Pairing with Prometheus

The Wikantik container publishes `/metrics`, which jakemon's Alloy agent
scrapes into Prometheus at `192.168.0.10:9090`. During a load test you can
query Prometheus directly for time-series data the k6 aggregate doesn't
break down:

```bash
# Host CPU saturation through the test window (UTC times):
curl -s -G --data-urlencode \
    'query=100 - (avg by (host)(rate(node_cpu_seconds_total{host="docker1",mode="idle"}[2m])) * 100)' \
    --data-urlencode 'start=2026-05-21T13:50:00Z' \
    --data-urlencode 'end=2026-05-21T14:01:00Z' \
    --data-urlencode 'step=60s' \
    http://192.168.0.10:9090/api/v1/query_range

# Container CPU split (which containers are running hot):
curl -s -G --data-urlencode \
    'query=topk(5, sum by (id)(rate(container_cpu_usage_seconds_total{job="integrations/cadvisor",host="docker1"}[2m])) * 100)' \
    --data-urlencode 'time=2026-05-21T13:55:00Z' \
    http://192.168.0.10:9090/api/v1/query

# Cache hit-rate per cache (the wikantik scrape job):
curl -s http://docker1:8080/metrics | grep -E 'wikantik_cache_(hits|misses|size)'
```

The CPU partition between `repo-wikantik-1` and `repo-db-1` is the single
most useful number for understanding what's saturating. The optimisation arc
in this codebase ran the docker1 host at a ~50/50 split between Tomcat and
PostgreSQL CPU at peak — once you see that, you know the next move is either
"more cores on this host" or "split DB to its own host".

## Pairing with thread dumps (finding the lock / pool that's blocking)

**The most important rule of thumb:** if latency climbs under load while host
CPU stays *moderate* (and load-average is high), threads are **blocked** on a
shared resource — a DB connection, a lock — not computing. A blocked thread
burns ~0 CPU, so this is the unmistakable signature of a concurrency bottleneck.
JFR execution samples won't show it (they only sample on-CPU threads); thread
dumps will. The 2026-05-22 campaign (ScalingCharacterization.md § 14) found a DB
connection-pool exhaustion and three lock hotspots this way.

Capture a few worker-thread dumps while a load test runs at the breaking point:

```bash
for i in 1 2 3 4 5; do
  ssh docker1 'docker exec repo-wikantik-1 jcmd 1 Thread.print' > dump_$i.txt
  sleep 6
done

# How many of the 400 Tomcat workers are BLOCKED?
awk '/"http-nio-.*-exec-/{getline;print}' dump_3.txt | grep -c BLOCKED

# Pool exhaustion — workers parked acquiring a DB connection:
grep -c 'GenericObjectPool.borrowObject' dump_3.txt

# The most-contended monitor (a lock hotspot) and what holds it:
grep -oE 'waiting to lock <0x[0-9a-f]+>' dump_3.txt | sort | uniq -c | sort -rn | head
# then read a BLOCKED thread's stack to see the call path into the lock.
```

Typical culprits seen here: `borrowObject` (DB pool too small / per-request DB
hits uncached), and shared JDK objects built per request — `Collator.getInstance`,
`TimeZone.getTimeZone`, `SecureRandom`/`UUID.randomUUID`. Note that
`RequestCorrelationFilter` runs *upstream* of the `BackpressureFilter`, so a lock
there is not protected by the admission gate.

## Common patterns

### Sustained run for stability check

A 10-minute constant-VU run reveals degradation that a 3-min run misses:
GC accumulation, connection-pool leaks, cache pathological growth, tail-
latency creep.

```bash
bin/loadtest.sh smoke --duration 10m --vus 650
```

Watch heap, cache sizes, and thread counts at minute 0 vs minute 9. If
they're all stable, the system is at steady state and the throughput is
genuinely sustainable.

### A/B at a fixed config

The cleanest comparison. Same VU count, same duration, **one variable
changed** between runs (a property flip, a cache config, a JVM flag).

```bash
# baseline
bin/loadtest.sh smoke --duration 3m --vus 650

# flip the variable (example: dense backend)
sed -i 's/^WIKANTIK_DENSE_BACKEND=.*/WIKANTIK_DENSE_BACKEND=pgvector/' .env.prod
bin/remote.sh deploy --skip-build

# warmup + heat
bin/loadtest.sh smoke --duration 2m --vus 8
bin/loadtest.sh smoke --duration 3m --vus 650
```

Single 3-min runs have ~10 % run-to-run variance; if your delta is smaller
than that, run 3–5 times at each config and average. Or do a 10-min
sustained run at each — the longer the window, the lower the relative
variance.

### Capacity sweep to find the knee

Stress profile, or successive constant-VU runs at increasing N:

```bash
for vu in 500 600 650 700 800; do
    bin/loadtest.sh smoke --duration 3m --vus $vu
    echo "completed at N=$vu — record RPS, p95, failure rate"
    sleep 30  # short cooldown
done
```

The knee is where RPS stops scaling linearly with VUs while p95 climbs.
Beyond the knee, RPS often *regresses* (more concurrency thrashes the
system) and p95 explodes.

### Warm-up before the heat

Fresh containers have cold JIT, cold OS page cache, and cold application
caches. A short low-VU run primes everything before the measurement run:

```bash
bin/loadtest.sh smoke --duration 2m --vus 8    # warm-up (~600 reqs, JIT seasons, caches fill)
bin/loadtest.sh smoke --duration 3m --vus 650  # measurement run
```

For sustained runs (≥ 10 min), the warm-up itself is part of the run — JIT
fully bakes in the first minute or two.

### Verifying backpressure (`BackpressureFilter`)

The `WIKANTIK_MAX_INFLIGHT_REQUESTS` cap fast-fails over-capacity requests with
`503` + `Retry-After: 1` rather than letting them queue. **The cap must be set
below Tomcat `maxThreads` (400) to fire at all** — the filter holds permits on
worker threads, so in-flight can never exceed `maxThreads`; a cap ≥ 400 (the
former default of 700) is inert. The production default is **390**. To verify the
mechanism + its metric without needing to drive a genuinely overloading VU
count, temporarily set a low cap so a modest load exceeds it:

```bash
# Set a low cap so the overload bites at a normal VU count.
sed -i 's/^WIKANTIK_MAX_INFLIGHT_REQUESTS=.*/WIKANTIK_MAX_INFLIGHT_REQUESTS=100/' .env.prod
bin/remote.sh deploy --skip-build

# Baseline the counter, overload, watch it climb while health stays up.
curl -s http://<host>:8080/metrics | grep wikantik_backpressure_rejected_total
bin/loadtest.sh smoke --duration 3m --vus 650 &
# ...mid-run, confirm the guarantees hold:
curl -s -o /dev/null -w '%{http_code} %{time_total}s\n' http://<host>:8080/api/health   # 200, fast — exempt
curl -s http://<host>:8080/metrics | grep wikantik_backpressure                          # rejected_total climbing, inflight ≈ cap

# Restore the production cap afterward (390 — must stay below maxThreads=400).
sed -i 's/^WIKANTIK_MAX_INFLIGHT_REQUESTS=.*/WIKANTIK_MAX_INFLIGHT_REQUESTS=390/' .env.prod
bin/remote.sh deploy --skip-build
```

What to confirm:
- `wikantik_backpressure_rejected_total` climbs (matches the k6 `http_req_failed`
  delta, minus the usual MCP/tools auth-probe 4xx).
- `wikantik_backpressure_inflight` pins at/near the cap and returns to 0 after
  the run (no permit leak).
- `/api/health` and `/metrics` stay 200 throughout (they're exempt).
- The *admitted* subset's p95 stays low — fast 503s keep the server
  under-subscribed for the requests it does accept.

## Common pitfalls

1. **Comparing JIT-warm vs JIT-cold runs.** The same load can produce a
   10–20 % throughput difference depending on JIT state. Either warm
   explicitly, or run long enough that the warm-up amortises (≥ 5 min).
2. **Comparing across config changes that move multiple variables.** Always
   know exactly what differs between two runs. If pool size AND mmap AND
   cache changed, the delta isn't attributable to any one of them.
3. **Treating a single 3-min run as a precise measurement.** Run-to-run
   variance is real. ~10 % delta on a single comparison is suggestive at
   best.
4. **Loading from a different host than usual.** k6's network path
   contributes ~1 ms to each request. If you usually run k6 on a host with
   sub-ms RTT to the target and switch to one with 10 ms RTT, observed
   latency shifts. Keep the load-gen host consistent.
5. **Hitting `/metrics` from the harness.** `--verify` does this *outside*
   the load period (before/after only) — but if you put `/metrics` in the
   read scenario by mistake, you're load-testing the metrics endpoint, not
   the application. The default scenarios deliberately exclude it.
6. **Ignoring the host CPU number.** If `node_cpu_seconds_total` is at
   99 %, you're at the hardware ceiling. No software fix changes that —
   the levers are "more cores" or "less work per request".

## What does "saturated" look like?

When the host is at 99 % CPU and you're not seeing throughput regressions or
p95 climbs under sustained load:

- **You've found the ceiling on this hardware.** Throughput is bounded by
  physical cores executing useful work.
- **Optimisations from here are diminishing-returns** unless they remove a
  category of work entirely (e.g. caching out a per-request DB query).
- **The next-real-lever is hardware change** — more cores vertically, or
  splitting concerns across hosts (DB to its own machine, app tier behind a
  load balancer).

When the host is at, say, 75 % CPU and throughput plateaus or p95 climbs:
- **Something else is the bottleneck** — locks (synchronized monitor → run
  a JFR), pool exhaustion (DBCP, Tomcat threads — check `pg_stat_activity`
  and `tomcat_threads_busy`), I/O (check `node_disk_*` and Lucene mmap
  patterns), or downstream (an upstream embedder timing out, causing
  cascades).
- **A JFR will name the culprit** within a 3-minute capture.

## How to document a load-test result

After a meaningful run, add a row to the comparison table in
[`docs/ScalingCharacterization.md`](ScalingCharacterization.md). Keep
columns consistent across runs so deltas read cleanly:

| Field | Notes |
|---|---|
| Date / time (UTC) | matches the k6 start banner |
| Configuration | one-line summary; the variable(s) that differ from the prior row |
| VU count | from `--vus` |
| Duration | sustained-load portion (not ramp) |
| RPS | from k6 `http_reqs/sec` |
| p50 / p95 / max | from `http_req_duration` |
| Failure rate | `http_req_failed.rate` |
| Notes | confounders, JFR captured / not, surprises |

This makes the next session's "what changed?" attribution trivial.

## When to flip the dense-retrieval backend

The pgvector backend is the **multi-host scaling lever**. On a single shared
host (the docker1 reference), the in-memory dense backend wins: per-query
latency floor is sub-ms vs ~3 ms with pgvector (one PG round-trip per
search), and DBCP pool serialisation under load is worse than the in-process
SIMD path.

The pgvector path's win materialises when:

- **PG runs on its own host.** Then the dense compute leaves the app tier,
  the app tier becomes stateless w.r.t. the vector index, and the app tier
  horizontally scales behind a load balancer.
- **The corpus grows past in-memory limits.** With ~12 K chunks the
  in-memory float[] is ~50 MB — trivial. At 1 M chunks it's ~4 GB —
  noticeable. pgvector's HNSW index is memory-mapped and disk-backed.

See [`docs/superpowers/specs/2026-05-20-pgvector-hnsw-dense-retrieval-design.md`](superpowers/specs/2026-05-20-pgvector-hnsw-dense-retrieval-design.md)
for the cutover sequence and the rollback contract (it's a one-flag flip
with a `--skip-build` redeploy).

## Further reading

- [`loadtest/README.md`](../loadtest/README.md) — tactical harness reference
- [`docs/ScalingCharacterization.md`](ScalingCharacterization.md) — full
  scaling study, methodology, every run captured to date
- [`docs/DockerDeployment.md` § Performance / search-backend tuning](DockerDeployment.md#performance--search-backend-tuning-optional)
  — the runtime configuration knobs
- [`docs/superpowers/specs/2026-05-20-pgvector-hnsw-dense-retrieval-design.md`](superpowers/specs/2026-05-20-pgvector-hnsw-dense-retrieval-design.md)
  — pgvector design + multi-host crossover analysis
- [`docs/superpowers/specs/2026-05-21-versioning-provider-contention-fix-design.md`](superpowers/specs/2026-05-21-versioning-provider-contention-fix-design.md)
  — the contention-fix design that drove much of the recent throughput win
