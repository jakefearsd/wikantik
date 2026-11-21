# Profiling run 2026-11-20: a fresh deploy of main on docker2 with production's content

Question: with the 2.7.x work (rich editor, collab, work management, private clusters, the two GoF passes)
landed, where are the new bottlenecks under production-shaped data and settings?

## Environment

- **Target:** docker2 (Ryzen 7 5800U, 8c/16t, 30 GB; CrafterCMS and jakemon stacks resident but idle).
  Fresh image built from `main` at `0733a5ac39`, stood up with `bin/dr-restore.sh docker2` from the
  2026-11-20 NAS snapshot of production: **1,164 pages, 20,018 chunks, 20,018 embeddings** (1,107 readable by
  anonymous users; 57 answer 403 — private clusters and page ACLs).
- **Settings mirroring docker1:** prod compose (app 3G, db 2G), `lucene-hnsw` dense backend, `ef_search=100`,
  `nio` Lucene directory, versioning cache 5000, max in-flight 390, `genai=full`, collab + work enabled,
  the same private clusters. Embeddings from docker2's own iGPU embedder (`qwen3-embedding:0.6b`, Ollama,
  `OLLAMA_NUM_PARALLEL=4`) — the same shape as docker1's.
- **Deliberate divergences:** no SSO, SMTP, IndexNow or backup secrets on the clone (nothing leaves it);
  the load generator's addresses are exempt from the per-client rate limiter
  (`WIKANTIK_RATELIMIT_EXEMPT_CIDRS`), so capacity is measured rather than the limiter; `pg_stat_statements`
  enabled for the write run.
- **Load generator:** k6 **on docker2** (see Finding 0), the standard harness
  (`bin/profile-iteration.sh`, now with `LOADTEST_REMOTE_HOST`) plus an open-model probe
  (ramping arrival rate, no think time, half the queries novel so the embedding cache cannot absorb them).

## Headline

| run | load | req/s | p95 | failed | CPU samples / 1k req |
|---|---|---:|---:|---:|---:|
| `prodclone-base-0733` | 40 read + 10 admin, 3 min | 55.3 | 21.8 ms | 0% | 175 |
| `prodclone-write-0733` | + 2 write VUs | 57.1 | 21.6 ms | 0% | 189 |
| `prodclone-stress-0733` | ramp to 400 read + 10 admin + 2 write | 157 | 13.9 ms | 0% HTTP | — |
| `prodclone-knee-0733` | open model, 50 → 1,000 req/s | up to 922 | see Finding 1 | 0% | — |

The CPU path is healthy and in line with 2026-11-13 (174 samples/1k req on a smaller corpus). The app never
saturated: at 922 req/s it used about 5 of 16 cores, the backpressure limiter never rejected, Postgres
stayed near idle. **The bottleneck is the query-embedding path, and its failure mode is silent.**

## Finding 0: the workstation → docker2 network path drops packets under load (infrastructure)

The first baseline, driven from the workstation, showed p95 1.71 s, median 425 ms and a 42 s max at 32 req/s,
with the app 98% idle. Latencies came in steps of ~430 / 860 / 1,720 ms (TCP retransmission back-off);
`nstat` showed **18% of segments retransmitted** (1,623 of 8,889) and 1,042 RTO timeouts in 30 s. The LAN
moved to 192.168.5.0/24 since September (docker2 is now 192.168.5.52, not .0.5); ping RTT is 3.5 ms (0.45 ms
in September) and the wired NIC shows 396k RX drops. Ping at low rate loses nothing — the loss appears with
many parallel connections. **Not an application problem**, but any future remote load test from the
workstation will be wrong until the path is fixed. Driving k6 from docker2 itself gave clean numbers.

## Finding 1: query embedding saturates at ~26/s, then the breaker opens and retrieval silently degrades

Open-model probe, 30 s windows (p50/p95 ms):

| t | req/s | search | bundle |
|---:|---:|---|---|
| 0 s | 62 | 71 / 211 | 17 / 187 |
| 60 s | 125 | 12 / **1,725** | 11 / **1,786** |
| 90 s | 175 | 8 / **1,528** | 6 / **1,715** |
| 150 s | 346 | 6 / 15 | 5 / 12 |
| 270 s | 922 | 11 / 39 | 9 / 31 |

Embedder counters at the end: **circuit OPEN** (opened 8 times), 1,573 successful calls, 1,443 timeouts,
**31,312 calls rejected by the open breaker**, mean call latency 1.2 s. Measured directly, the embedder does
one query in ~60 ms and **tops out at ~26 embeds/s** (32 calls in 1.24 s at parallelism 4 or 16).

What happens: once novel queries arrive faster than ~26/s, calls queue in Ollama, requests wait out the
~2 s embed timeout (the 1.5–1.9 s p95 tail), failures trip the breaker, and hybrid retrieval falls back to
BM25 for every caller until the breaker half-opens. Latency then *recovers* while load is still rising —
because dense retrieval has quietly switched off. The fallback is the designed fail-closed behaviour; the
problems are (a) callers pay the full timeout while the queue builds, and (b) one burst — an agent fan-out,
a crawler sending varied `q=` — degrades retrieval quality for the whole site. docker1 has the same shape
(one iGPU, query embeds compete with save-time re-embedding).

Candidate fixes, roughly in order of payoff:
1. **Bound in-flight query embeds** (a semaphore sized to the embedder's parallelism, ~4–8) and fall back to
   BM25 *immediately* when it is full, instead of queueing to the timeout. Removes the 2 s tail and keeps one
   burst from opening the breaker for everyone.
2. **Give interactive query embeds priority over background re-embedding** (or a separate, smaller lane), so
   a bulk save/ingest does not starve search.
3. A **short interactive timeout** (hundreds of ms) distinct from the batch `wikantik.search.embedding.timeout-ms`
   (30 s).
4. Surface "dense off" per response (bundle already reports `topSimilarity: -1`) on `/api/search` too.

## Finding 2: per-request work that scales with corpus size (CPU, small today)

From the baseline JFR (caller chains, samples of 1,757):

- **Cluster tag distribution on every page read** — `PageReadHandler.handleGet` → `ClusterStatusResolver` →
  `StructuralProjection.getCluster` → `TagAggregation.distribution` (26 samples), and far more from the admin
  agent-grade audit (81). The projection is an immutable snapshot, so `getCluster` can be memoised per
  projection (the same fix as `similarTo` on 2026-11-13), or the resolver can ask for hub + visible count
  without building the tag distribution it never reads.
- **Frontmatter parsed again for read-audit policy** — `AuditReadPolicy.shouldAudit` reloads the page text and
  parses YAML on every page GET (21 samples) two lines before `handleGet` parses the same frontmatter; pass
  the parsed metadata in.
- `/api/changes` and page counts go through `CachingProvider.getAllPages` and filter or count the full list
  (32 samples).
- New since 2026-11-13: `PrivateClusterGate.isPrivate` in search result filtering (production turns private
  clusters on; the local run did not) — small.

None of these hurts at today's load (page reads p50 ≈ 1 ms at 922 req/s), but each grows with page or
cluster size.

## Finding 3: write amplification and logging

- **Every Basic-auth request writes a `login.ok` audit row** — 9,960 in ~40 minutes from 10 admin VUs. Each is
  a hash-chained insert under a transaction-wide advisory lock (`SELECT seq,row_hash … ORDER BY seq DESC`
  2,450×, `pg_advisory_xact_lock` 2,450×): a serialisation point for any script or agent using Basic auth.
  API-key and browser-session traffic does not do this (sessions audit one login).
- **Embedding-race WARNs carry the whole SQL batch** — a save followed quickly by an edit or delete makes the
  async embed job insert for chunks already gone (FK failure, rolled back, harmless: one page per batch). The
  WARN text includes the 1,024-float vector as hex (~8 KB per line); 419 such lines in one stress run.
  `onPageDeleted: cannot resolve canonical_id …` also WARNs on every quick delete (752).
- `DefaultLuceneSearcher` logs every search at INFO (`Search: query=… results=… elapsedMs=…`) — hundreds of
  lines a second under load.
- Startup WARN: `snooze_opportunity` and `respond_to_comment_thread` are registered but missing from the admin
  MCP instructions text.

## Finding 4: the admin index-status aggregate dominates database time

`ContentChunkRepository.stats()` — `COUNT(DISTINCT page_name), AVG/MIN/MAX(token_count_estimate)` over the
whole chunk table — ran 514 times in the write run at 16.5 ms: **62% of all database time** (8.5 s of
13.6 s). It backs the admin index-status/overview/embeddings pages, so production cost is tiny; caching it
for a few seconds would remove it entirely.

## Harness notes

- MCP and tools 429s under stress are the configured per-client limits (50/s per key, all VUs on one key);
  the following 400s are VUs whose `initialize` was refused and who then call without a session.
- `bin/profile-iteration.sh` now takes `SLUGS_FILE` and `LOADTEST_REMOTE_HOST` (run k6 on another host that
  has `~/perf/{k6,bin/loadtest.sh,loadtest/,loadtest.env}`).
- Raw outputs: `prodclone-{base,write,stress,knee}-0733/` (JFRs and k6 JSON not versioned).
