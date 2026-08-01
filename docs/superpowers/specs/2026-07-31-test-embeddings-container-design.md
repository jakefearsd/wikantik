# Containerised embeddings for local dev and the test suite

**Date:** 2026-07-31
**Status:** implemented 2026-07-31. Sections below describe what shipped; where the
as-built differs from the original design the text has been corrected in place and the
difference called out.

## Problem

`inference.jakefear.com` — the embedding host the wiki has always pointed at — is
physically decommissioned (packed for a move). It resolves to `192.168.0.10` on the LAN
and answers nothing.

The consequences are not evenly distributed:

- **Production is fine.** It overrides `WIKANTIK_EMBEDDING_BASE_URL` in `.env` and runs a
  CPU-only `ollama` sidecar (`docker-compose.prod.yml`), so dense retrieval works.
- **Local dev and the IT suite are not.** Both fall through to the shipped default, fail
  every embedding call, and degrade to BM25. A single IT module logged 34
  `UnresolvedAddressException`s and 146 `ConnectException`s in one run.

The real cost is not the log noise. It is that **the dense/bundle path has no end-to-end
coverage at all.** No IT asserts `GET /api/bundle`, `assemble_bundle`, or dense retrieval;
the closest is a tool-listing assertion in the knowledge-disabled suite. Eighteen unit
classes exercise the dense components against fakes. The RAG path Wikantik actually ships
is verified nowhere against a live embedder.

## Goals

1. Give the IT suite a real embedding provider, so dense retrieval and the context bundle
   are covered end to end rather than silently falling back to BM25.
2. Give local development the same, so manual testing matches production behaviour.
3. Keep the added wall-clock small — the canonical gate is ~4:30 and the full `--all` run
   ~8:49, and that is worth protecting.

## Non-goals

- **Changing the shipped `ini/wikantik.properties` default.** It still points at
  `inference.jakefear.com`. This is a deliberate deferral: the situation is temporary and
  the host is expected back after the move. Local dev overrides it via `.env`; the new IT
  module overrides it explicitly. Non-dense IT modules continue to emit DNS-failure noise,
  which is accepted for now.
- Turning embeddings on across all IT modules. Coverage goes where it is asserted.
- Replacing the production sidecar or altering prod configuration in any way.

## Design

### 1. The container

A single service definition in a new `docker/docker-compose.embeddings.yml`, mirroring the
`ollama-embed` sidecar that already exists in `docker-compose.cloud.yml` rather than
introducing a second way to run Ollama:

- `ollama/ollama:latest`, CPU-only (no GPU reservation).
- `ollama-models` named volume, so the ~600 MB model pull happens once and survives
  restarts.
- Entrypoint serves and then runs `ollama pull "${WIKANTIK_EMBEDDING_MODEL_TAG:-qwen3-embedding:0.6b}"`.
  `ollama pull` checks the local blob store first, so re-running it on every start is cheap
  and idempotent.
- Healthcheck: **model presence**, not daemon liveness. As built it is
  `ollama list | grep -q "^${WIKANTIK_EMBEDDING_MODEL_TAG}[[:space:]]"`. The original
  design said plain `ollama list`, which was wrong: `ollama serve` answers within seconds,
  long before the ~600 MB pull finishes, so a bare `ollama list` reports healthy while the
  model is still downloading and races every consumer that gates on health. The same
  distinction is enforced on the client side by `embeddings_model_ready()`
  (`bin/lib/embeddings.sh`), which greps `/api/tags` for the tag rather than accepting
  HTTP 200 — Ollama answers 200 with an empty model list mid-pull.

**Model:** `qwen3-embedding:0.6b` (Ollama tag) ↔ `qwen3-embedding-0.6b` (internal code),
**1024 dimensions**. The dimension is not a free choice — it must match what the index was
built with.

**Ports.** Two instances on distinct published ports:

| Consumer | Port | Why |
|---|---|---|
| Local dev | `11434` | Conventional Ollama port. |
| Test suite | `11435` | So a test run can never silently borrow the developer's dev container, and both can run at once. |

### 2. Local development

`bin/deploy-local.sh` brings the container up if the model is not already being served and
polls `embeddings_model_ready()` until it is (up to 360s) — the same treatment it already
gives the Tomcat download. It polls the endpoint rather than reading Docker health state, so
the same code path works whether the endpoint is the container it just started or one the
operator runs themselves.

It only starts a container when the base URL names *this* machine. A remote
`WIKANTIK_EMBEDDING_BASE_URL` is used as-is: starting a local container that publishes the
remote's port number and then polling the remote would leave a stray container behind and
report a misleading failure.

`wikantik.search.embedding.base-url` becomes a `@@…@@` placeholder in
`wikantik-war/src/main/config/tomcat/wikantik-custom-postgresql.properties.template`,
rendered live from `.env` at the key's existing documented location in the embedding block
(**exactly one** occurrence in the rendered file — `java.util.Properties` is last-wins, so a
second copy would silently override anything an operator edits) from
`WIKANTIK_EMBEDDING_BASE_URL`, documented in `.env.example`. The script already sources
`.env`, so this follows the existing `@@POSTGRES_*@@` mechanism.

`WIKANTIK_LOCAL_EMBEDDINGS=false` opts out, for BM25-only work or a machine without the
disk to spare. When set, `deploy-local.sh` skips the container entirely and renders the
line commented out, so the install falls back to the ini default rather than baking in a
URL nothing is serving.

### 3. Test suite

`bin/run-tests.sh` starts one shared embedder before the IT phase, polls until the model is
actually being served (900s budget — the long pole is the cold-machine ~600 MB pull, not
startup), and tears it down afterwards. One model load serves the whole run regardless of
`--parallel N`.

It manages its own container rather than reusing whatever happens to be listening.
Depending on ambient machine state is the precise failure mode that made
`PreviewClickHoldsStillIT` hang for 120s on an unattended desktop; that lesson is fresh
enough not to repeat.

### 4. New module: `wikantik-it-test-dense`

Its own Cargo Tomcat and pgvector Postgres with build-helper-reserved ports, matching the
existing IT modules. Embeddings are enabled **only here**, pointed at the shared embedder,
with `wikantik.search.dense.backend=lucene-hnsw` to match the production default.

It **joins the default gate**: `bin/run-tests.sh` gains a fifth deterministic IT module, so
`--parallel 4` becomes a five-module reactor and `bin/run-tests.sh --module dense` runs it
alone. It is not opt-in like the Authentik full-loop — coverage nobody runs is coverage
that rots, which is what happened to the SCIM loop until 2026-07-30.

It asserts the currently-uncovered path end to end:

1. A saved page is chunked and embedded.
2. `GET /api/bundle?q=…` returns a section from that page.
3. The returned section carries a version-pinned citation.

**Non-vacuity.** With the embedder stopped the suite fails through three independent
mechanisms:

1. `@BeforeAll` asserts `/api/tags` answers 200 **and** lists `qwen3-embedding:0.6b` — not
   just that something is listening.
2. The rank-1 poll times out after the 90s index budget, because an empty vector index means
   the bundle returns no sections at all.
3. `denseRankerContributesCandidates` asserts the `dense` array from `?debug=rankings` is
   non-empty, which requires both a successful query embedding and a populated HNSW index.

The zero-lexical-overlap query is the fourth guard, and it is load-bearing: the BM25 chunk
index holds the seeded pages, so the lexical ranker is a genuine competitor and a shared word
would let it satisfy the rank-1 assertion with no embedding involved.

*This was not true as originally built,* and the correction is recorded because the reasoning
matters. `LuceneBm25ChunkIndex.fromDataSource` used to snapshot `kg_content_chunks` **once**,
at wiring time; an IT deployment wires against a freshly-migrated, empty database, so the index
held zero documents for the life of the process (`bm25 chunks=0`) regardless of what the tests
later saved. There was no lexical ranker to defeat, and the guard was documented honestly as an
invariant held in advance. That turned out to be a **production** defect rather than a test
artifact — every page written since the last restart was invisible to lexical chunk retrieval —
and it was fixed (issue #48): the index is now maintained incrementally on the chunk-projection
seam. `lexicalRankerSeesPagesSavedAfterStartup` is the regression cover, and the zero-overlap
guard became real the same day.

The module fails loudly when the embedder is unreachable. That is its purpose; degrading
quietly would defeat the entire exercise.

### 5. Failure policy

| Situation | Behaviour |
|---|---|
| Embedder unreachable, dense module | Fail, naming the endpoint. |
| Embedder unreachable, other IT modules | Unchanged from today. `/api/search` degrades to the live Lucene page index, and — since issue #48 — the **bundle**'s BM25 chunk half is genuinely populated too, so it degrades to real lexical results rather than an empty index. Only the dense module exercises the bundle at all. |
| Model not yet pulled | The container comes up immediately — the entrypoint backgrounds `ollama serve` and pulls alongside it, so start does **not** block. Readiness is gated entirely by the model-presence healthcheck and by the callers' own `embeddings_model_ready()` poll. Anything that treats "container running" as "ready" will hit a live daemon with no model. |
| No Docker | Same as the existing suites, which already require it (Testcontainers, Authentik). |

## Risks

- **First run downloads ~600 MB.** Mitigated by the named volume; only a cold machine pays
  it. Worth calling out in the runbook so it is not mistaken for a hang.

  Note the volume is **per-instance**, not shared: Compose prefixes volume names with the
  project name, so the two instances own `wikantik-embed-dev_ollama-models` and
  `wikantik-embed-test_ollama-models` separately and a cold machine pays the download once
  per instance. Left that way deliberately — a single shared blob store would have two
  `ollama` daemons writing it concurrently, and one duplicated download beats debugging a
  corrupted model cache.
- **CPU embedding is slower than the retired GPU host.** Contained by scoping embeddings to
  one module. **Measured 2026-07-31**, same machine, same session, warm `~/.m2`, warm
  `ollama-models` volume:

  | Run | Wall clock |
  |---|---|
  | IT phase `-T 4`, the four pre-existing modules (before) | **2m 16s** |
  | IT phase `-T 4`, with `wikantik-it-test-dense` (after) | **2m 43s** |
  | `wikantik-it-test-dense` alone, inside that reactor | 1m 56s |
  | Full canonical gate `bin/run-tests.sh --parallel 4`, green | **5m 21s** (Phase 1 2m 36s + IT 2m 43s) |

  **The new module costs ~27s of gate wall clock** — it is not the critical-path module
  (`custom-jdbc` at 2m 18s is), so under `-T 4` it only pays for the fifth slot serialising
  behind the first module to finish. Embedding itself is negligible: the suite seeds five
  short pages and the whole `DenseBundleIT` class runs in under 4s once Tomcat is up.

  The full gate now measures 5m 21s against the ~4m 30s baseline recorded earlier, but only
  ~27s of that gap is this module: the *unchanged* four-module IT phase measured 2m 16s on
  the same day, well above the ~1m 45s that baseline implies, so the remainder is ambient
  machine drift, not a cost of this change.
- **A second Ollama instance on a dev machine costs RAM** (~1 GiB resident with
  `OLLAMA_KEEP_ALIVE`). The separate test port makes the two instances explicit rather than
  accidental.

## Open question deferred

When `inference.jakefear.com` returns after the move, decide whether the shipped default
should point at it again, at `localhost`, or be removed in favour of requiring an explicit
override. Not decided here.
