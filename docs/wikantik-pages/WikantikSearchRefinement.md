---
canonical_id: 01KQ0P44YYYXZBBJ5Q22N8Q0VD
title: Wikantik Search Refinement
cluster: wikantik-development
type: article
tags:
- search
- retrieval
- evaluation
- testing
- development
- tooling
summary: How to iterate on the retrieval evaluation query set, run the standalone search-eval tool against a running wiki, and interpret the report.
related:
- WikantikDevelopment
- LocalRAG
- RagImplementationPatterns
- LlmEvaluationMetrics
---

# Wikantik Search Refinement

Retrieval quality — "did search put the right page in the top 5?" — is measured here by a seed set of `(query, ideal_page)` pairs run through the live wiki via `bin/search-eval`, reported as `recall@5`, `recall@20`, and `MRR`. Improvements (synonym handling, dense embeddings, rerankers) are validated against this set; without it, every future retrieval change is a vibe check.

The tool talks to whatever wiki you point it at — local dev, staging, production. It tests the *deployed* retriever, not an isolated in-process copy, so it picks up ACL filtering, index state, middleware, and any retrieval change that lands without needing code changes to the tool itself.

## The three locations

- **Source of truth — edit here:**
  `eval/retrieval-queries.csv`
  Three columns: `query, ideal_page, notes`. Add, remove, or remap queries in your editor. The `notes` column carries a category label (`synonym-drift`, `indirect`, `multi-word-concept`, `specific`, `general`, `business-process`, `hard`) so the per-category breakdown in the report is meaningful.

- **Rolling output — regenerated each run:**
  Whatever you pass to `--out`, or just stdout. Not committed; throwaway.

- **Frozen checkpoint — commit when it matters:**
  `docs/superpowers/specs/2026-04-17-retrieval-eval-baseline.md`
  A snapshot of the report at a known point in time. Update it when the query set is finalized, a new retriever lands, or you want a before-and-after reference to cite.

## Running the tool

Prerequisite: a wiki running somewhere you can reach. Local dev is easiest:

```bash
tomcat/tomcat-11/bin/startup.sh
```

Then:

```bash
# Default: localhost:8080, test.properties for credentials, all queries
bin/search-eval

# Write the report to a file while also printing it
bin/search-eval --out eval/reports/2026-04-18.txt

# Point at staging, anonymous user
bin/search-eval --base-url https://staging.example.com --anonymous

# JSON output, for diffing runs programmatically
bin/search-eval --json --out run.json

# Quieter — summary + missed-query list only, no full per-query table
bin/search-eval --quiet

# Full help
bin/search-eval --help
```

No Python dependencies — standard library only. Just `python3`.

**Exit codes:** `0` clean run, `1` at least one query produced an HTTP/network error (report enumerates them), `2` configuration error (missing queries file, unreachable endpoint).

## The typical loop

1. Edit `eval/retrieval-queries.csv`. Fix a bad `ideal_page`, add a query your team actually asks, drop one that turned out to have no good answer in the corpus.
2. Run `bin/search-eval --out eval/reports/try-N.txt`.
3. Read the report. Compare the summary line (recall@5, recall@20, MRR) and the missed-query list against the baseline doc.
4. Decide per query:
   - If a query moved from rank 193 to rank 4 — curation win.
   - If recall@5 dropped — is the query actually harder than you thought, is the `ideal_page` wrong, or did retrieval regress?
   - If a query always misses despite clearly having a good page — worth a bug.
5. Commit the CSV when satisfied. Update the baseline doc at natural milestones: end of a curation session, before swapping BM25 for hybrid retrieval, after a reranker lands.

## What the tool does

`bin/search-eval` is a standalone Python 3 script under `bin/`:

1. Loads `eval/retrieval-queries.csv` (or whatever `--queries` points at).
2. Reads credentials from `test.properties` at the repo root, or `--user/--password`, or skips auth with `--anonymous`.
3. For each query, issues `GET /api/search?q=<query>&limit=<N>` against the configured base URL.
4. Finds the 1-based rank of the `ideal_page` in the returned results (or `-1` if missing, `-2` on HTTP error).
5. Computes `recall@5`, `recall@20`, MRR, and per-category breakdowns.
6. Writes a formatted text report (default) or JSON (`--json`) to stdout, plus optionally to a file.

No threshold assertions — the tool is informational. Wire it into CI as a separate nightly or pre-deploy job when you're ready to alert on regressions.

## Query-writing heuristics

The value of the eval set is in query *variety*, not volume. Include:

- **Synonym drift.** Words in the query don't literally appear in the title. "Auth config" → a page titled `AuthenticationManager`. If every query is a title keyword hunt, the eval will never surface the problem that dense embeddings fix.
- **Indirect phrasing.** Natural how-to language instead of title-ish language. "How do I get started as a new hire" instead of "onboarding."
- **Multi-word concepts.** Three-to-five-word technical concepts the page discusses without necessarily title-matching.
- **Specific vs. general.** Both "Gemma 4 VRAM budget" and "local LLM hardware" should surface reasonable answers.
- **Business entities.** Named clients, products, internal codes — the queries that motivate the consulting-wiki use case.
- **Hard cases.** Queries your team has seen the LLM fumble. Abbreviations ("k8s"), short common terms ("ai"), or ambiguous phrasings.

Anti-patterns:

- **Title-copy queries.** "WikantikDevelopment" as a query against `WikantikDevelopment` is trivially passing.
- **Multiple equally-right answers.** Forces a single-answer framing; the current tool scores only one `ideal_page` per query.
- **Queries with no good answer in the corpus.** Ungradable; drop them.

## Interpreting the per-category breakdown

The report groups recall by the `notes` label. Categories tell you *where* retrieval is weak, not just how weak overall:

- `specific` near 1.0: keyword BM25 is doing its job on literal terms.
- `multi-word-concept` near 1.0: probably too many title-ish queries — add drift.
- `synonym-drift` and `indirect` low: the expected gap that dense embeddings should close.
- `business-process` low: the wiki hasn't built much terminological structure around these topics yet, or the queries need rewriting.
- `hard` stubbornly low: these are the intentionally-hard cases; don't over-optimize for them at the expense of the easier categories.

Track the categories over time. A retrieval change that lifts `specific` from 0.9 to 1.0 while dragging `synonym-drift` from 0.4 to 0.2 is a regression worth rolling back.

## Next step: hybrid BM25 + dense retrieval

The baseline report makes the gap visible: BM25 is already near-ceiling on `specific` and `multi-word-concept` (recall@5 ≈ 0.80, recall@20 = 1.00) and weak on `indirect` (0.50), `general` (0.20), and `business-process` (0.40). The standard move here is a second retriever that reads meaning rather than surface tokens, fused with BM25 by Reciprocal Rank Fusion (RRF) — no tuning, no threshold, just interleaved ranks. Any query where one retriever nails it carries the fused result; queries where both fail stay failed and surface in the eval as genuine gaps.

Mechanically: an embedding model turns each chunk (and each query) into a vector; pgvector stores the chunk vectors with an HNSW index; retrieval returns BM25 top-K and vector top-K, then RRF combines them. The existing `ChunkInspector` admin tab already shows what a chunk looks like, so the chunking step is not new work.

### Picking an embedding model

Three questions decide the model: **deployment target** (GPU available or CPU-only), **quality floor** (how much recall lift you need), and **latency budget** (query-time embedding adds to every search).

**Quality tiers, open-source, as of 2026:**

| Tier | Model | Params | Dim | Notes |
|------|-------|--------|-----|-------|
| Top | Qwen3-Embedding-8B | 8 B | ≤ 4096 | SOTA open, multilingual + code |
| Strong | Qwen3-Embedding-4B | 4 B | ≤ 4096 | GPU-class; int8 makes it CPU-viable with patience |
| Balanced | bge-m3 | 568 M | 1024 | Dense + sparse + ColBERT in one model |
| Balanced | Qwen3-Embedding-0.6B | 600 M | up to 1024 | Small-but-strong, code-aware |
| Balanced | nomic-embed-text-v1.5 | 137 M | 768 (Matryoshka 64–768) | Fully open, 8K context |
| Light | bge-small-en-v1.5 | 33 M | 384 | Small, fast, solid English |
| Light | mxbai-embed-large-v1 | 335 M | 1024 | Good English baseline |

Configuration points that apply to all of them:

1. **Prompt prefixes matter.** nomic uses `search_document: …` / `search_query: …`. Qwen3 uses instruction prompts per the model card. bge-m3 is optional but benefits from an instruction. Wrong or missing prefixes silently tank recall — verify with two or three known-good queries after any model change.
2. **Normalize vectors and use cosine distance** (`<=>` in pgvector). All the models above are trained with cosine; using `<->` (L2) on un-normalized vectors is a common silent bug.
3. **Index**: `hnsw` over `ivfflat` at this corpus size (~1K pages, few-K chunks). Start `m = 16, ef_construction = 64`; tune `ef_search` per query for the recall/latency tradeoff. Rebuild the index only when the embedding model changes.
4. **Matryoshka truncation** (nomic, Qwen3) lets you store full-dim and query-truncate for a real speedup with small recall cost — apply only after measuring the default.
5. **Quantization**: fp16 is the default; int8 roughly halves memory and doubles CPU throughput for < 0.5 % MTEB loss. Use it on CPU; usually unnecessary on GPU.
6. **Chunk size**: 512 tokens with 64-token overlap is a good default; raise to 1024 for long design docs. The chunker already exists; `ChunkInspector` is the debugging tool for this.

### Deployment target A: GPU box (reference: RTX 4060 Ti 16 GB)

Fits comfortably: Qwen3-Embedding-0.6B, bge-m3, nomic, any light-tier model. Fits tightly: Qwen3-Embedding-4B (fp16 ≈ 8 GB, q4 ≈ 4 GB). Does not fit with any serious local LLM also resident: Qwen3-Embedding-8B or NV-Embed-v2.

Recommended default for a dev-wiki use case: **Qwen3-Embedding-0.6B, fp16, normalized, cosine, HNSW**. Code-aware, strong benchmarks, plenty of VRAM headroom if an LLM lands on the same card later. Move up to bge-m3 if you want the built-in sparse head, which could eventually replace Lucene BM25 and collapse the hybrid stack into a single model. Move up to Qwen3-4B only if the eval shows a persistent quality ceiling on `indirect` / `business-process`.

Expected query-embed latency (single query, fp16):

| Model | VRAM | Query latency |
|-------|------|---------------|
| nomic | ~0.3 GB | 3–5 ms |
| bge-m3 | ~1.2 GB | 10–20 ms |
| Qwen3-0.6B | ~1.5 GB | 15–30 ms |
| Qwen3-4B | ~8 GB (fp16) | 60–120 ms |

### Deployment target B: CPU-only box

This is the interesting case — customer-site deployments, ops boxes without a GPU, or a dedicated mini-PC. The reference we're planning against:

**NiPoGi AM06 PRO** — AMD Ryzen 7 7730U (Zen 3, 8C/16T, AVX2 + FMA3, **no AVX-512**, **no AMD NPU**), 32 GB RAM, 512 GB M.2 SSD, integrated Vega 8 iGPU (not useful for ML — ROCm on Barcelo is a dead end), dual GbE, configurable cTDP 10–25 W. Roughly 60–70 % of an Intel AVX-512/VNNI box of similar core count on int8 embedding workloads — slower than a discrete GPU by a factor of ~10–30×, but fast enough for a ~1K-page wiki.

Top picks for CPU-only, ordered by the usual tradeoff:

| Model | Params | Dim | CPU latency/query (Zen 3, int8) | Notes |
|-------|--------|-----|--------------------------------|-------|
| bge-small-en-v1.5 | 33 M | 384 | 10–20 ms | Tiny, fast, English-strong |
| nomic-embed-text-v1.5 | 137 M | 768 | 30–80 ms | 8K context, Matryoshka, fully open |
| bge-m3 | 568 M | 1024 | 50–90 ms | Unified dense + sparse + ColBERT |
| Qwen3-Embedding-0.6B | 600 M | up to 1024 | 80–200 ms | Upper-bound CPU quality |

Recommended default for this box: **bge-m3, int8 ONNX, served by Hugging Face `text-embeddings-inference` (TEI)**. Multi-functional dense+sparse in one model, production-grade HTTP server with batching and concurrent request handling, built-in Prometheus metrics, OpenAI-compatible `/v1/embeddings` endpoint, stable Docker image.

Setup sketch (Ubuntu Server 24.04, Docker, bge-m3 int8):

```bash
# BIOS: set cTDP to 25 W if thermals hold, 20 W otherwise.
# Verify with `stress-ng --cpu 16 --timeout 600s` while watching `sensors`.

mkdir -p ~/tei/data
cat > ~/tei/docker-compose.yml <<'YAML'
services:
  tei:
    image: ghcr.io/huggingface/text-embeddings-inference:cpu-1.6
    restart: unless-stopped
    ports: ["8001:80"]
    volumes: ["./data:/data"]
    environment:
      OMP_NUM_THREADS: "8"          # physical cores, not logical
      RUST_LOG: "info"
    command: >
      --model-id BAAI/bge-m3
      --dtype float16
      --max-batch-tokens 16384
      --max-concurrent-requests 64
      --pooling cls
YAML
docker compose -f ~/tei/docker-compose.yml up -d

curl -s localhost:8001/embed \
  -H 'content-type: application/json' \
  -d '{"inputs": ["hello wikantik"]}' | jq '.[0] | length'
# Expect 1024.
```

Pre-export int8 once if the image build doesn't auto-quantize:

```bash
optimum-cli export onnx --model BAAI/bge-m3 \
    --task feature-extraction --device cpu ./data/bge-m3-onnx
optimum-cli onnxruntime quantize --onnx_model ./data/bge-m3-onnx \
    --avx2 -o ./data/bge-m3-onnx-int8
# Then change --model-id to /data/bge-m3-onnx-int8.
```

Expected throughput on this box:

- **Full reindex** (~1K pages × ~5 chunks = ~5K chunks): ~40–60 s, cold, one-shot. Rare event.
- **Incremental on page save** (~5 chunks): ~300 ms — synchronous on save is fine.
- **Query embed**: ~60–90 ms + pgvector HNSW ~5 ms + transport → end-to-end retrieval stays under ~150 ms.

### Wikantik integration shape

The wiki and PostgreSQL stay where they live today. Only the embedding transform moves to the GPU or mini-PC box. Concretely:

1. **Schema migration** adds an `embeddings` table keyed on `(page, chunk_id)` with a `vector(dim)` column and an `hnsw` index. Empty table, reversible.
2. **`EmbeddingClient`** in `wikantik-main` — small HTTP client that `POST`s to TEI's `/embed` (or local Ollama / whatever backend), with a connection pool, timeout, and retry. No heavyweight SDK needed.
3. **Indexer hook** on the page-save pipeline: chunk the page, embed each chunk, upsert into `embeddings`. One-shot backfill script for the existing corpus.
4. **Retrieval path**: `SearchResource` grows a hybrid branch — BM25 top-K (existing Lucene path) + vector top-K (pgvector) fused by RRF.
5. **Feature flag** `wikantik.search.hybrid.enabled`. When off or the embedding service is unreachable, fall back to pure BM25. The flag decides whether the vector path runs; BM25 is always wired up as the safety net.

### Security and ops for the embedding service

- **Auth**: pass `--api-key` to TEI, put the shared secret in `wikantik-custom.properties`, send `Authorization: Bearer …` from `EmbeddingClient`.
- **Network**: WireGuard or Tailscale tunnel between the wiki host and the embedding box; don't expose TEI on the LAN without auth.
- **Metrics**: TEI exposes `/metrics` in Prometheus format — scrape it from the `wikantik-observability` stack. Alert on p95 request latency and container restarts.
- **Updates**: pin the TEI image tag in `docker-compose.yml`; `docker compose pull && up -d` for upgrades.
- **Thermal sanity**: small mini-PCs throttle under sustained load. Verify with `stress-ng` for 10+ minutes and watch `sensors` before committing a cTDP setting.

### Gating the change on the eval

Any dense / hybrid rollout commits to re-running `bin/search-eval` and diffing against the baseline in `docs/superpowers/specs/2026-04-17-retrieval-eval-baseline.md`. The merge criterion is **`indirect` and `general` recall@5 lift without regression on `specific` or `multi-word-concept`**. If `specific` drops more than ~0.05 while `indirect` lifts, the fusion weighting is wrong (or BM25 is being overridden) and the change isn't ready.

## Why a standalone tool (not a JUnit test)

Previous versions of this harness lived inside `wikantik-main` as a `@Disabled` JUnit test that built its own `TestEngine` and indexed `docs/wikantik-pages/` into a scratch directory. That worked but wasn't the shape an eval tool wants:

- Reindexed from scratch on every run (~30–90 s of overhead).
- Tested the test-path retriever, not whatever's actually deployed.
- Enabling it required `-DincludeDisabled=true -Dtest=RetrievalEvalTest` — a magic invocation rather than a direct command.
- Tied to the JVM and Maven.

`bin/search-eval` replaces that with an HTTP-based tool that:

- Runs in under a few seconds against whatever base URL you provide.
- Sees the real deployed retriever (indexed state, ACLs, any middleware).
- Is a direct command, not a testing gymnastics invocation.
- Requires no Maven, no JVM — just `python3`.

The `eval/retrieval-queries.csv` file is the same format as before; moving from test-classpath resources to a top-level `eval/` directory was purely a home-finding exercise. The committed baseline in `docs/superpowers/specs/2026-04-17-retrieval-eval-baseline.md` is still valid as a reference point — the numbers it reports came from the same underlying Lucene BM25, produced by the JUnit harness at the time.

## See also

- The baseline numbers and missed-query list:
  `docs/superpowers/specs/2026-04-17-retrieval-eval-baseline.md`
- The tool:
  `bin/search-eval`
- Seed query set:
  `eval/retrieval-queries.csv`
- The search endpoint it calls:
  `wikantik-rest/src/main/java/com/wikantik/rest/SearchResource.java` → `GET /api/search?q=…&limit=…`
