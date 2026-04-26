# Knowledge-Graph-Aware Search Rerank — Configuration Guide

This guide covers the three subsystems that together let Wikantik answer search
queries with graph-aware reranking: the save-time **entity extractor** that
populates `chunk_entity_mentions` (Phase 2), the unified **Ollama embedding
stack** that chunks and embeds every page (Phase 1), and the **Phase 3 graph
rerank** that boosts hybrid BM25+dense results whose mentioned entities are
close (in the KG) to the query's resolved entities.

If you only want to flip the knobs and move on, jump to
[Quick enable](#quick-enable). If you want to A/B compare tunings, see
[Comparing tunings](#comparing-tunings).

## Table of contents

1. [How it fits together](#how-it-fits-together)
2. [Prerequisites](#prerequisites)
3. [Configuration reference](#configuration-reference)
4. [Quick enable](#quick-enable)
5. [Verification](#verification)
6. [Comparing tunings](#comparing-tunings)
7. [Metrics and observability](#metrics-and-observability)
8. [Troubleshooting](#troubleshooting)
9. [Rollback](#rollback)
10. [Further reading](#further-reading)

---

## How it fits together

```
Page save
  ├─► ChunkProjector ──► kg_content_chunks
  │       ├─► AsyncEmbeddingIndexListener ──► content_chunk_embeddings (Ollama)
  │       └─► AsyncEntityExtractionListener ──► kg_proposals + chunk_entity_mentions
  │
  └─► GraphProjector ──► kg_nodes / kg_edges  (frontmatter links, unchanged)

Query path  GET /api/search?q=…
  ├─► BM25 (Lucene)
  ├─► QueryEmbedder → Ollama vector
  ├─► HybridSearchService.rerankWith(bm25, vec)         → fused page ordering
  └─► GraphRerankStep.rerank(query, fused)              → boosted page ordering
         ├─► QueryEntityResolver          (Caffeine cache, 1-/2-/3-grams)
         ├─► PageMentionsLoader           (bulk kg_content_chunks ⋈ chunk_entity_mentions)
         ├─► GraphProximityScorer         (multi-source BFS, 1/(1+minHops))
         └─► InMemoryGraphNeighborIndex   (kg_edges adjacency snapshot)
```

Each stage fails closed: if any ingredient is absent (no mentions for any
candidate, no query entities resolved, graph index not ready, boost set to 0),
the step returns the input order verbatim and search degrades gracefully to the
hybrid output — and if hybrid itself fails, to BM25 only.

Key invariants:

- **The graph rerank only reorders; it never adds or removes candidates.** So
  the top-`limit` set can only contain pages that BM25 ∪ dense already found.
- **Ties preserve the input order.** A run with zero graph matches is
  bit-identical to the fused hybrid output.
- **All extraction writes go to proposals.** The extractor never touches
  `kg_nodes` / `kg_edges` directly — human review gates every addition.

---

## Prerequisites

The graph rerank is useful only when there is a graph to rerank against. You
need all of the following to be operational:

### 1. PostgreSQL with pgvector + V011

The `chunk_entity_mentions` table (schema `V011`) must exist. It is applied
automatically by `bin/deploy-local.sh` (which calls `bin/db/migrate.sh`). To
check:

```bash
PGPASSWORD=… psql -h localhost -U jspwiki -d wikantik \
  -c "\d chunk_entity_mentions"
```

Expected columns: `chunk_id`, `node_id`, `confidence`, `extractor`,
`extracted_at`.

### 2. Content chunks and embeddings

`ChunkProjector` runs on every page save when `wikantik.chunker.enabled=true`
(default), producing rows in `kg_content_chunks`. `AsyncEmbeddingIndexListener`
then writes to `content_chunk_embeddings` for the active
`wikantik.search.embedding.model`. Verify:

```sql
SELECT count(*) FROM kg_content_chunks;
SELECT count(*) FROM content_chunk_embeddings
  WHERE model_code = 'qwen3-embedding-0.6b';
```

If either is empty, trigger a rebuild through the admin UI
(`/admin/content/rebuild`) or via the REST endpoint — embeddings are
back-filled by `BootstrapEmbeddingIndexer` on startup when the table is empty.

### 3. KG entity mentions

The graph-aware rerank needs at least a few hundred rows in
`chunk_entity_mentions` to make a difference. These come from the Phase 2
extractor, which is **opt-in** (off by default). See
[Configuration reference](#configuration-reference) for
`wikantik.knowledge.extractor.backend`.

```sql
SELECT count(*), count(DISTINCT node_id), count(DISTINCT chunk_id)
  FROM chunk_entity_mentions;
```

### 4. KG edges

`GraphProximityScorer` walks `kg_edges`. These come from frontmatter-declared
relationships (`links_to`, `mentions`, `part_of`, …) via `GraphProjector` on
save, plus any edges promoted from reviewed proposals. A wiki with zero edges
gets zero boost — the step disables itself.

```sql
SELECT count(*) FROM kg_edges;
```

---

## Configuration reference

All properties live in `wikantik.properties` (defaults) and are overridden in
`wikantik-custom.properties` (deployed). Changes require a Tomcat restart
unless otherwise noted.

### Phase 3 — graph-aware rerank

| Property | Default | Range | Meaning |
|---|---|---|---|
| `wikantik.search.graph.boost` | `0.2` | `0.0` – `∞` | Multiplier applied to per-page graph-proximity scores. `0` disables the step without touching code (the rollback lever). Values above `1.0` let the graph signal fully overpower rank 0 of the fused list — useful only on heavily-curated corpora. |
| `wikantik.search.graph.max-hops` | `2` | `1` – `∞` | BFS search radius in KG hops. `1` matches direct neighbors only; `2` is the sweet spot (neighbor + neighbor-of-neighbor); `3+` starts overlapping most of a connected graph and waters down the signal. |
| `wikantik.search.graph.query-entity.cache.ttl-seconds` | `300` | `1` – `∞` | Caffeine TTL for query → resolved-entity mapping. Matches the hybrid `QueryEmbedder` cache so a repeat query skips both lookups. |
| `wikantik.search.graph.query-entity.cache.max-entries` | `1000` | `1` – `∞` | Cache size cap; bounds memory. `~50 bytes per entry` so 1k entries cost ≈ 50 KB. |
| `wikantik.search.graph.neighbor-index.max-edges` | `500000` | `1` – `∞` | Hard cap on edges loaded into the in-memory adjacency index. Above this the index refuses to load (logs a warn) and the rerank step returns empty neighbors → no boost. Raise only after sizing memory: adjacency is `~200 bytes per edge` (UUID pairs + HashSet overhead), so 500k edges ≈ 100 MB. |

#### Score combination

For each fused candidate at 0-based rank `r` among `N`, the rerank step
computes:

```
base     = 1 − r / N                 // rank 0 → 1.0, rank N−1 → 1/N
proximity= max over mentioned entities of 1 / (1 + minHops(Q, e))
final    = base + boost * proximity
```

Pages are then sorted by `final` descending, with ties broken on original rank
ascending (stable). So with `boost=0.2`, a rank-10-of-20 page with proximity
`1.0` (a self-match — one of its mentioned entities *is* a query entity) lands
at `0.5 + 0.2 = 0.7`, above rank-0's `1.0`? No — `1.0 > 0.7`, so the rank-0
page stays on top unless *it also* gets a proximity hit. The boost is designed
to break ties and lift mid-list entries, not to overrule top BM25+dense hits.

### Phase 2 — entity extractor

| Property | Default | Range | Meaning |
|---|---|---|---|
| `wikantik.knowledge.extractor.backend` | `ollama` | `claude` \| `ollama` \| `disabled` | Which extractor runs on page save. `claude` uses Anthropic prompt-cached calls (needs `ANTHROPIC_API_KEY`); `ollama` uses your Ollama endpoint; `disabled` skips extraction. |
| `wikantik.knowledge.extractor.claude.model` | `claude-haiku-4-5` | model id | Anthropic model when `backend=claude`. Flip to `claude-sonnet-4-6` for higher-quality extraction on ambiguous corpora (costs ~10× per page). |
| `wikantik.knowledge.extractor.ollama.model` | `gemma4-assist:latest` | model tag | Ollama tag when `backend=ollama`. Must serve structured JSON reliably. |
| `wikantik.knowledge.extractor.ollama.base_url` | `http://inference.jakefear.com:11434` | URL | Ollama HTTP endpoint. Override to `http://localhost:11434` if running Ollama on the same host as the wiki. |
| `wikantik.knowledge.extractor.timeout_ms` | `120000` | milliseconds | Hard timeout per chunk. Above this the chunk is skipped (logged) and the next one runs. Shipping default is 2 minutes so self-hosted Ollama deployments with multi-KB prompts don't time out on every call; drop to 30000 for low-latency hosted APIs like Anthropic. |
| `wikantik.knowledge.extractor.confidence_threshold` | `0.6` | `0.0` – `1.0` | Proposals below this confidence are dropped, not filed — keeps the review queue tractable. |
| `wikantik.knowledge.extractor.max_existing_nodes` | `200` | `1` – `∞` | Cap on the existing-node dictionary embedded in every extractor prompt. Bounds prompt size (and caching key stability). |
| `wikantik.knowledge.extractor.per_page_min_interval_ms` | `5000` | milliseconds | Rate limit: minimum wall-clock between re-extractions of the same page. `0` disables. |
| `wikantik.knowledge.extractor.concurrency` | `2` | `1` – `4` (clamped) | Max parallel extraction RPCs for the admin batch job only. Save-time extraction stays serial. See [Concurrency and model sizing](#concurrency-and-model-sizing) for why `2` is the shipping default. |

### Concurrency and model sizing

The batch endpoint can fan chunk extractions out over multiple concurrent RPCs
(`wikantik.knowledge.extractor.concurrency`), but the useful range is
model-dependent. Empirical numbers from this wiki's corpus on a single 4060
Ti 16 GB Ollama host, averaged over 10–31 page samples:

| Model | Size | c=1 | c=2 | c=3 | c=4 |
|---|---|---|---|---|---|
| `gemma4-assist:latest` | 7–8B class | 8.5 s/chunk | ~9 s/chunk | — | 10.3 s/chunk (slower) |
| `qwen2.5:7b-instruct-q5_K_M` | 7B | 14.2 s/chunk | 9.4 s/chunk | — | — |
| `gemma4:e2b` | 2B | — | — | **6.7 s/chunk** | — |

7–8B-class models on a single-GPU Ollama host are bandwidth-bound — adding
concurrent requests slows every in-flight RPC roughly linearly, so
`concurrency=2` is where gains plateau for `gemma4-assist`. Pushing to `3` or
`4` yields **negative** scaling on this hardware. The 2B-class `gemma4:e2b`
has enough VRAM / compute headroom to scale to `concurrency=3` with real
throughput gains, but it comes at quality cost (emits single-character
operators and variables as entities, 2× more review noise per chunk).

**The shipping defaults (`gemma4-assist:latest`, `concurrency=2`) are chosen
to maximize mention-resolution quality at acceptable throughput**, because
operator review time, not ingestion wall-clock, is the real bottleneck on
this corpus. Full-corpus projection at these defaults: ~95–100 hours. Switch
to `gemma4:e2b` + `concurrency=3` if you want the fastest local ingestion
(~73 h) and are willing to tolerate higher review-queue noise.

### Phase 1 — chunking and embeddings

These are inherited from the hybrid retrieval pipeline; they are documented in
detail in the in-file comments of `wikantik.properties`. The ones that
interact with graph rerank:

| Property | Default | Notes |
|---|---|---|
| `wikantik.search.hybrid.enabled` | `true` | Master switch for hybrid. Graph rerank only ever runs *after* hybrid, so `enabled=false` implies graph rerank is also effectively off. |
| `wikantik.search.embedding.model` | `qwen3-embedding-0.6b` | Ollama embedding model for chunks. Changing this invalidates every existing embedding row — rebuild required (`/admin/content/rebuild`). |
| `wikantik.chunker.enabled` | `true` | Save-time chunking kill-switch. Disabling stops the mentions pipeline at the source. |
| `wikantik.chunker.max_tokens` | `512` | Hard ceiling on a non-atomic chunk's size. |
| `wikantik.chunker.merge_forward_tokens` | `150` | Effective chunk-size floor — sections below this are rolled forward into the next section. Biggest lever for controlling chunk count. |

#### Tuning the chunker to control extraction cost

`merge_forward_tokens` is the one-knob-fix for extraction throughput. A
corpus of 958 pages / 4 M tokens produces ~39 k chunks at the old default of
`8`, which means 39 k extractor RPCs for a full-corpus rebuild. Raising the
floor coalesces small sibling sections into larger chunks:

| `merge_forward_tokens` | Estimated chunk count | Full-corpus extraction (gemma4-assist c=2) |
|---|---|---|
| 8 (pre-v1.1.7) | ~39,000 | ~95 h |
| **150 (shipping default)** | **~19,000** | **~45–50 h** |
| 200 | ~16,000 | ~38 h |
| 300 | ~12,000 | ~29 h |

The trade-off is dense-retrieval precision: a bigger chunk has a diffuser
embedding, so a query that matches one tight subsection within a merged
chunk ranks lower than it would against a tighter chunk. In practice,
chunks up to ~500 tokens preserve dense-retrieval quality on most corpora;
above that, top-k results start losing specificity.

**Changing this value requires a full content rebuild** — chunk boundaries
move, `content_hash` changes, and every row in `kg_content_chunks`,
`content_chunk_embeddings`, and `chunk_entity_mentions` is invalidated.
Trigger via the admin rebuild endpoint (`/admin/content/rebuild`) and then
re-run the extractor batch (`/admin/knowledge/extract-mentions?force=true`).

---

## Batch extraction for existing corpora

A fresh deploy only fills `chunk_entity_mentions` as pages are *saved* — so an
existing wiki with hundreds of pages picks up coverage slowly until someone
edits them. The admin batch endpoint does a one-shot pass over every chunk in
`kg_content_chunks` and runs the configured extractor against each, honoring
the same confidence / rejection / proposal rules as the save-time path.

**Endpoint (requires admin).**

```bash
# Authentication works with HTTP Basic (the BasicAuthFilter handles login
# against the user DB) or a session cookie from the login form.
source <(grep -v '^#' test.properties | sed 's/^test.user.//;s/=/="/;s/$/"/')
AUTH="-u ${login}:${password}"

# Start a run. Returns 202 Accepted with the initial status, or 409 Conflict
# if a run is already in progress. Add ?force=true to clear each chunk's
# pre-existing mentions before re-extracting.
curl -s -X POST $AUTH "http://localhost:8080/admin/knowledge/extract-mentions"
curl -s -X POST $AUTH "http://localhost:8080/admin/knowledge/extract-mentions?force=true"

# Poll status — state progresses IDLE → RUNNING → COMPLETED (or ERROR).
watch -n 10 "curl -s $AUTH http://localhost:8080/admin/knowledge/extract-mentions | jq ."

# Cancel between pages — the currently-in-flight page's chunks finish but no
# new pages start.
curl -s -X DELETE $AUTH "http://localhost:8080/admin/knowledge/extract-mentions"
```

**Timing log at INFO.** Every completed page emits:

```
Bootstrap extraction: page='<PageName>' chunks=N mentions=M proposals=P elapsedMs=Xxxx
```

Plus a final summary:

```
Bootstrap entity extraction COMPLETED: processedPages=958/958, failedPages=2,
  mentionsWritten=4213, proposalsFiled=6891, totalMs=2153099, meanPerPageMs=2247
```

Watch the `meanPerPageMs` and the tail of the per-page log to decide whether
the current model is viable at production cadence. A run-of-the-mill page
(5 chunks, a few thousand tokens) should finish in **30 – 90 seconds** against
a reasonably provisioned Ollama host. If a corpus-wide run projects to
multiple hours, either pick a faster model (lower-parameter instruct tag) or
disable the batch and rely on save-time catch-up.

**Safety.** Only one batch may run at a time (a second POST returns 409).
Cancellation is between chunks — a timeout-bound chunk can take up to
`wikantik.knowledge.extractor.timeout_ms` to clear after DELETE. Restart
Tomcat for an immediate hard-stop.

**Idempotency.** Mentions use `ON CONFLICT DO UPDATE` on `(chunk_id, node_id)`,
so re-running without `force=true` is safe; it just refreshes confidence /
timestamp on existing rows. `force=true` deletes each chunk's mention rows
first and is only useful when swapping extractor backends.

### Standalone CLI — run the batch without a running Tomcat

For multi-hour / multi-day extractor runs, a fat-jar CLI ships the same
`BootstrapEntityExtractionIndexer` wiring without the servlet container —
so Tomcat can be stopped, rebuilt, or repeatedly redeployed during local
development while extraction continues against the shared PostgreSQL
database.

Build and launch:

```bash
# One-line launch — the wrapper builds the jar on first run, reads JDBC
# credentials from the local Tomcat deploy (tomcat-11/conf/Catalina/localhost/ROOT.xml),
# and tails progress to stdout.
bin/kg-extract.sh

# Force-overwrite every chunk's prior mentions first (swap extractor backends):
bin/kg-extract.sh --force

# Single-in-flight if you want minimal GPU pressure:
bin/kg-extract.sh --concurrency 1

# See every knob the jar accepts:
bin/kg-extract.sh --help
```

Progress line format (logged every `--poll-seconds`, default 30):

```
Extract-CLI progress: state=RUNNING pages=42/957 chunks=1043/23256 (4.5%)
  failedChunks=3 mentions=187 proposals=1204 elapsed=4200s perChunkMs=4027
```

Ctrl-C / SIGTERM requests a graceful cancel — the in-flight chunk finishes
(the Ollama RPC is blocking and not aborted mid-call), then the CLI exits.
Exit codes: `0` for COMPLETED, `1` for ERROR or refused-start, `2` for bad
arguments.

Direct invocation (bypasses the wrapper; useful in CI / systemd):

```bash
java -jar wikantik-extract-cli/target/wikantik-extract-cli.jar \
     --jdbc-url jdbc:postgresql://host/jspwiki \
     --jdbc-user jspwiki \
     --jdbc-password-env PG_PASSWORD \
     --ollama-url http://inference.jakefear.com:11434 \
     --ollama-model gemma4-assist:latest \
     --concurrency 2 \
     --force
```

Coordination with the running server: if `wikantik.knowledge.extractor.backend`
is also enabled on the Tomcat instance, save-time and CLI extraction will
both try to write to `chunk_entity_mentions`. The upsert is idempotent so
there's no corruption risk — both paths just compete for GPU time on the
inference host. For a clean batch run, set `backend=disabled` in
`wikantik-custom.properties` and restart Tomcat, or stop it entirely.

---

## Quick enable

Assuming prerequisites are met (Phase 1 is on by default, V011 is applied):

```properties
# wikantik-custom.properties

# 1. Turn the extractor on (choose one backend)
wikantik.knowledge.extractor.backend                  = ollama
wikantik.knowledge.extractor.ollama.model             = gemma4-assist:latest
wikantik.knowledge.extractor.ollama.base_url          = http://inference.jakefear.com:11434

# 2. Graph rerank is on by default (boost = 0.2). Confirm by setting explicitly:
wikantik.search.graph.boost                           = 0.2
wikantik.search.graph.max-hops                        = 2
```

Then:

```bash
tomcat/tomcat-11/bin/shutdown.sh
bin/deploy-local.sh                 # redeploy; picks up the new config
tomcat/tomcat-11/bin/startup.sh
```

Save a handful of pages (either through the editor or by touching existing
ones) to seed `chunk_entity_mentions`. On the next `/api/search` call the
graph rerank path will engage as soon as at least one mention matches at least
one query-entity hop.

---

## Verification

### 1. Confirm the rerank step is wired

Startup log line (`wikantik.log`):

```
Graph rerank wired (boost=0.2, maxHops=2, indexNodes=N)
```

Where `N` is the number of distinct nodes in the adjacency snapshot.
`indexNodes=0` means `kg_edges` is empty — the step will skip itself on every
query until edges exist.

### 2. Confirm mentions are flowing

```bash
PGPASSWORD=… psql -h localhost -U jspwiki -d wikantik -c "
  SELECT extractor, count(*) AS mentions,
         count(DISTINCT chunk_id) AS chunks,
         count(DISTINCT node_id)  AS nodes,
         max(extracted_at)        AS most_recent
    FROM chunk_entity_mentions
   GROUP BY extractor;
"
```

If `most_recent` is stale (older than your last save), the extractor is
either disabled, failing, or rate-limited. Check the extractor metrics and
the Tomcat log for warnings.

### 3. Sanity-check the rerank on a known query

Pick a query whose expected-top page mentions an entity that is also
adjacent to another common query entity:

```bash
source <(grep -v '^#' test.properties | sed 's/^test.user.//;s/=/="/;s/$/"/')

# Baseline: graph boost off
curl -s "http://localhost:8080/api/search?q=Napoleon+Wellington&limit=10" \
  | jq '.results | map({name, score})' > /tmp/search-no-graph.json

# Flip boost on (or use live config), restart, re-run
curl -s "http://localhost:8080/api/search?q=Napoleon+Wellington&limit=10" \
  | jq '.results | map({name, score})' > /tmp/search-with-graph.json

diff /tmp/search-no-graph.json /tmp/search-with-graph.json
```

If the diff is empty despite non-zero mentions in relevant pages, inspect
`QueryEntityResolver.resolve` behavior — it may not be finding
case-matching `kg_nodes.name` for the query terms.

### 4. Inspect the graph neighborhood a query is using

```sql
-- Which nodes resolved from a query? (run manually for now; the admin UI
-- does not yet expose this surface.)
SELECT id, name, node_type
  FROM kg_nodes
 WHERE LOWER(name) IN ('napoleon', 'wellington', 'napoleon wellington');

-- Which entities are mentioned by each candidate page?
SELECT c.page_name, string_agg(n.name, ', ' ORDER BY n.name) AS entities
  FROM kg_content_chunks c
  JOIN chunk_entity_mentions m ON m.chunk_id = c.id
  JOIN kg_nodes              n ON n.id = m.node_id
 WHERE c.page_name IN ('Napoleon', 'BattleOfWaterloo', 'ArthurWellesley')
 GROUP BY c.page_name;
```

---

## Comparing tunings

Graph rerank is a tuning problem: the "right" `boost` and `max-hops` depend
on your corpus density and the quality of your extractor's mentions. There is
no universally best value — you measure.

### Quick A/B with a fixed eval set

1. **Build a query eval set** (~30 queries is enough to see signal). For each
   query, manually list the 2–5 page names that *should* be in the top-10.
   Store as CSV in `docs/eval/graph-rerank-queries.csv` (not committed — it
   is corpus-specific):

   ```csv
   query,expected_top_pages
   "Napoleon Wellington","Napoleon|BattleOfWaterloo|ArthurWellesley"
   "GraphRAG","KnowledgeGraph|RetrievalAugmentedGeneration"
   ```

2. **Script the sweep.** Run the same eval set under several `boost` values.
   A minimal bash harness — requires `jq` and `curl`:

   ```bash
   # docs/eval/sweep.sh — run against a local wiki
   #!/usr/bin/env bash
   set -euo pipefail

   eval_csv="${1:-docs/eval/graph-rerank-queries.csv}"
   source <(grep -v '^#' test.properties | sed 's/^test.user.//;s/=/="/;s/$/"/')

   hit_rate_at_k() {
     # args: query "expected|pipe|separated" k
     local q="$1" expected="$2" k="$3"
     local results
     results=$(curl -s -u "${login}:${password}" \
       "http://localhost:8080/api/search?q=$(jq -rn --arg q "$q" '$q|@uri')&limit=$k" \
       | jq -r '.results[].name')
     local hits=0 total=0
     while IFS= read -r exp; do
       total=$((total+1))
       grep -Fxq "$exp" <<< "$results" && hits=$((hits+1))
     done < <(echo "$expected" | tr '|' '\n')
     echo "scale=3; $hits / $total" | bc
   }

   tail -n +2 "$eval_csv" | while IFS=, read -r q expected; do
     q=${q%\"}; q=${q#\"}; expected=${expected%\"}; expected=${expected#\"}
     printf '%-40s hit@10=%s\n' "$q" "$(hit_rate_at_k "$q" "$expected" 10)"
   done
   ```

3. **Run the sweep with each config.** For each `boost` value in
   `{0.0, 0.1, 0.2, 0.3, 0.5, 1.0}`, edit `wikantik-custom.properties`,
   redeploy, run the harness, record average `hit@10`:

   ```bash
   for b in 0.0 0.1 0.2 0.3 0.5 1.0; do
     sed -i "s/^wikantik.search.graph.boost.*/wikantik.search.graph.boost = $b/" \
       tomcat/tomcat-11/lib/wikantik-custom.properties
     tomcat/tomcat-11/bin/shutdown.sh && sleep 3 && tomcat/tomcat-11/bin/startup.sh
     sleep 10                                    # let the neighbor index warm
     echo "=== boost=$b ==="
     bash docs/eval/sweep.sh
   done
   ```

4. **Pick the winner.** For most Wikantik-class corpora the plot is
   monotone-then-flat: `hit@10` rises from `boost=0` to `~0.2`, plateaus,
   then falls when the boost starts over-ruling the BM25+dense signal
   (`boost=1.0+`). Ship whichever value ties the plateau at the lowest knob
   value.

### Measuring latency impact

Two approaches:

**a. In-process metric (Prometheus).** The existing `HybridMetricsBridge`
already exports per-query latency. The graph rerank step's additional
cost shows up in the overall `/api/search` latency — watch the
`http_server_requests_seconds{uri="/api/search"}` histogram (exposed by the
observability module).

**b. Ad-hoc with `curl`.** For a quick `p50/p95` read without Grafana:

```bash
for i in $(seq 1 100); do
  curl -s -o /dev/null -w '%{time_total}\n' \
    "http://localhost:8080/api/search?q=Napoleon+Wellington&limit=20"
done | sort -n | awk '
  BEGIN { i=0 }
  { v[i++]=$1 }
  END {
    printf "p50=%.3fs p95=%.3fs\n",
      v[int(NR*0.50)], v[int(NR*0.95)]
  }
'
```

Run with `boost=0.0` and `boost=0.2`. The plan's budget is `p95 Δ ≤ 20 ms`.
If you blow through it, check:

- `kg_edges` row count — above `neighbor-index.max-edges` the step disables
  itself; below but still large (100k+) the BFS can dominate. Reduce
  `max-hops` to `1`.
- `chunk_entity_mentions` for candidate pages — the `PageMentionsLoader` query
  scales with `fusedPageNames.size() * chunks_per_page * mentions_per_chunk`.
  Add an index if needed; the shipping schema already has one.
- Caffeine cache hit rate on `QueryEntityResolver`. A query set that never
  repeats defeats the cache and pays the SQL cost every call.

### Comparing `max-hops`

`max-hops=1` is conservative (only direct neighbors matter), `max-hops=2`
is the default, `max-hops=3` usually over-connects the graph. Quick sanity:

```sql
-- How many distinct nodes are reachable within 1, 2, 3 hops from
-- a sample "hot" node?
WITH RECURSIVE reach AS (
  SELECT id, 0 AS hops FROM kg_nodes WHERE name = 'Napoleon'
  UNION
  SELECT e.target_id, r.hops + 1
    FROM reach r JOIN kg_edges e ON e.source_id = r.id
   WHERE r.hops < 3
  UNION
  SELECT e.source_id, r.hops + 1
    FROM reach r JOIN kg_edges e ON e.target_id = r.id
   WHERE r.hops < 3
)
SELECT hops, count(DISTINCT id) AS reachable
  FROM reach
 GROUP BY hops ORDER BY hops;
```

If `hops=3` reaches most of the graph, drop `max-hops` to `2`. If `hops=2`
reaches only a handful of nodes, bump to `3` and check whether the eval-set
`hit@10` improves.

### Worked numeric example

Given a fused list of 20 pages, with `boost=0.2`:

| Rank | Page | `base = 1 − r/N` | `proximity` | `final` |
|---|---|---|---|---|
| 0 | `BattleOfWaterloo` | `1.00` | `0.0` (no mention) | `1.00` |
| 1 | `Austerlitz` | `0.95` | `0.0` | `0.95` |
| 2 | `Napoleon` | `0.90` | `1.0` (self) | `1.10` ← promoted to top |
| 3 | `Wellington` | `0.85` | `0.5` (1-hop) | `0.95` |

`Napoleon` moves from rank 2 to rank 0 because it is itself a query entity.
`Wellington` ties with `Austerlitz` and keeps its rank (stable sort).
`BattleOfWaterloo` stays at rank 0 relative to `Austerlitz` — the graph
signal did not surface for it, so its ordering is unchanged.

---

## Metrics and observability

The Phase 3 classes do not yet have dedicated Prometheus counters (the
shipping release piggybacks on `/api/search` request-level metrics). Until
that lands, the proxies you have are:

- **`wikantik_kg_extractor_requests_total`** — Phase 2 extractor calls. Low
  or flat means the mentions table is no longer being fed.
- **`wikantik_kg_extractor_failures_total`** — extractor errors. High ratio
  means the backend (Anthropic, Ollama) is flaky; the rerank will degrade
  gracefully but the corpus will stop keeping up.
- **`wikantik_kg_extractor_triples_emitted_total`** — mentions + proposals
  coming out of the extractor. Zero across a wave of saves means the
  extractor ran but produced nothing parseable (check for malformed-JSON
  warnings in the log).
- **`http_server_requests_seconds{uri="/api/search"}`** — query latency
  histogram; compare pre/post enabling the graph rerank step.

Read the extractor metrics via:

```bash
curl -s http://localhost:8080/metrics | grep -E 'wikantik_kg_extractor|search'
```

---

## Troubleshooting

### "Graph rerank disabled (wikantik.search.graph.boost=0)" on startup

`boost` is set to `0` (either explicitly or via a typo that parsed to 0).
Set it to `0.2` or run with the defaults and restart.

### `indexNodes=0` on startup

`kg_edges` has no rows visible to the JNDI-configured datasource. Check
that the datasource is connected to the right database, and that
`GraphProjector` has been running on save
(frontmatter `links_to` / `mentions` lists).

### Log: `InMemoryGraphNeighborIndex: edge count N exceeds cap`

Your graph has more edges than `wikantik.search.graph.neighbor-index.max-edges`.
Raise the cap (sized for your available heap — ≈200 bytes per edge) or
accept the feature being off until the corpus is pruned.

### Search results are identical with and without `boost > 0`

Check, in this order:

1. `SELECT count(*) FROM chunk_entity_mentions` — is the extractor running?
2. `QueryEntityResolver` — is your query hitting any `kg_nodes.name`?
   Names are exact case-insensitive; no fuzzy match. A query like
   "napoleon's battles" resolves `napoleon` and `battles` — if neither
   string is a node name, you get zero query entities and the step becomes
   a no-op.
3. `SELECT count(*) FROM kg_edges` — an empty or tiny edge set gives every
   candidate an identical proximity of 0 or 1, so ordering is unchanged.
4. Every candidate has proximity 0 — intended behavior, not a bug; the
   contract is "never warp a result set without positive signal".

### Search latency spiked after enabling

- Disable momentarily by setting `wikantik.search.graph.boost=0` and
  redeploying — if latency returns, the rerank is the cause.
- Reduce `wikantik.search.graph.max-hops` to `1`.
- Reduce `wikantik.search.graph.neighbor-index.max-edges` to the size that
  lets the index refuse to load — forces the step to be a no-op for now.
- If the `PageMentionsLoader` SQL is the hotspot, confirm
  `idx_chunk_entity_mentions_chunk` exists (`\d chunk_entity_mentions`).

### Extractor produced a proposal storm

`kg_rejections` is consulted before writing a proposal so a rejected triple
won't be re-proposed, but a brand-new corpus with zero rejections yields
plenty of initial noise. Raise
`wikantik.knowledge.extractor.confidence_threshold` to `0.75` until you have
worked through the review queue.

---

## Rollback

Every phase is config-gated. To disable the entire graph-rerank stack without
a redeploy:

```properties
wikantik.search.graph.boost                = 0      # Phase 3 off
wikantik.knowledge.extractor.backend       = disabled  # Phase 2 off
```

A Tomcat restart is required — property reads happen at engine init. To
roll back the Phase 1 migration (`V011`), you would need to drop the table
manually; this is rarely what you want, since the table is idempotent and
adding it is a no-op for systems that don't use it.

---

## Further reading

- [docs/superpowers/plans/2026-04-22-kg-rag-uplift.md](superpowers/plans/2026-04-22-kg-rag-uplift.md)
  — the original three-phase design document, including benefits analysis
  and the non-goals (community detection, global-query mode) deliberately
  left out of this release.
- `bin/db/migrations/V011__chunk_entity_mentions.sql` — authoritative schema.
- `wikantik-main/src/main/java/com/wikantik/search/hybrid/` — the seven
  Phase 3 classes (`GraphRerankStep`, `GraphProximityScorer`,
  `InMemoryGraphNeighborIndex`, `QueryEntityResolver`, `PageMentionsLoader`,
  `GraphNeighborIndex`, `GraphRerankConfig`) plus the Phase 1 hybrid
  retrieval pipeline (`HybridSearchService`, `HybridFuser`, etc.).
- `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/` — the
  Phase 2 extractor pipeline.
