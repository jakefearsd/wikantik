# GenAI Cost Tiers — Operator Reference

This page is for operators who cap or verify Wikantik's LLM/inference spend. That spend is bounded by a single ceiling property,
**`wikantik.genai.mode`** (`com.wikantik.api.config.GenAiMode`), plus a handful
of independent feature flags it gates. This page gives two named tiers —
**core**, **search** — with the exact `.env` preset for the
Docker/container path and the equivalent `wikantik-custom.properties` lines
for the bare-metal path, followed by how to verify a tier is actually
enforced and what it does *not* cover.

## How the ceiling works

`GenAiMode` (`wikantik-api/src/main/java/com/wikantik/api/config/GenAiMode.java`)
has three values, read from `wikantik.genai.mode`:

| Mode | `allowsEmbeddings()` | `allowsChatInference()` |
|------|-----------------------|--------------------------|
| `full` (default) | yes | yes |
| `embeddings-only` | yes | no |
| `none` | no | no |

An absent value falls back to `full` silently. A blank or unrecognized value
also falls back to `full` (fail open, never fail closed on a typo) but logs a
warning naming it, so an operator who *wanted* enforcement can see the miss.

The mode is a **ceiling**, not a switch: it never turns a feature *on*. Every
LLM-using call site reads its own enable flag first, then ANDs it with the
ceiling. The three call sites that do this today:

| Call site | Flag | Ceiling check | Source |
|-----------|------|----------------|--------|
| Embedding client | `wikantik.search.hybrid.enabled` | `mode.allowsEmbeddings()` | `EmbeddingConfig.fromProperties` (`wikantik-main/src/main/java/com/wikantik/search/embedding/EmbeddingConfig.java`) |
| Bundle LLM reranker | `wikantik.bundle.reranker.enabled` (or an `llm` token in `wikantik.bundle.rerank.chain`) | `mode.allowsChatInference()` | `BundleRerankers.rerankerFor`/`buildChain` (`wikantik-main/src/main/java/com/wikantik/knowledge/bundle/BundleRerankers.java`) |
| Bundle query-decomposition planner | `wikantik.bundle.decomposition.enabled` | `mode.allowsChatInference()` | `BundleDecompositionConfig.fromProperties` (`wikantik-main/src/main/java/com/wikantik/knowledge/bundle/BundleDecompositionConfig.java`) |

Each of these logs a `WARN` when an explicitly-enabled feature gets
overridden by the ceiling — see [Verify: warn-log lines](#3-warn-log-lines-when-the-ceiling-suppresses-a-feature) below.

---

## Tier: core — BM25 only, zero inference infrastructure

BM25 search, BM25-chunk context bundle, no embedding client, no chat
inference, no sidecar to run or pay for.

**`.env` (Docker/container path):**

```bash
WIKANTIK_GENAI_MODE=none
```

**`wikantik-custom.properties` (bare-metal path):**

```properties
wikantik.genai.mode = none
wikantik.search.hybrid.enabled = false
```

> **No env passthrough for `wikantik.search.hybrid.enabled`.** `docker/entrypoint.sh`
> renders an env override for `WIKANTIK_GENAI_MODE`
> but has no corresponding block for the hybrid-search flag — it
> isn't in the container env-var surface at all. That's fine for this tier:
> `mode=none` forces the embedding client disabled at the ceiling
> (`EmbeddingConfig`: `enabled = rawEnabled && mode.allowsEmbeddings()`), so dense
> retrieval is inert, and `GET /api/capabilities` reports the **ceiling-adjusted
> effective value** — a core deploy shows `hybridSearch: false` with no extra
> properties override needed. Setting the raw flag explicitly is only necessary
> if you want the warn logs quiet about a suppressed-but-enabled feature.

---

## Tier: search — dense + BM25 hybrid, CPU embedding sidecar only

Dense+BM25 hybrid search, dense-chunk context bundle, a CPU-only embedding
endpoint — no chat inference anywhere (no
reranker, no query decomposition).

**`.env` (Docker/container path):**

```bash
WIKANTIK_GENAI_MODE=embeddings-only
WIKANTIK_EMBEDDING_BASE_URL=http://ollama-embed:11434
```

`ollama-embed` is the cloud overlay's CPU-only Ollama sidecar
(`docker-compose.cloud.yml`, `--profile embeddings`)
— it pulls `WIKANTIK_EMBEDDING_MODEL_TAG` (default `qwen3-embedding:0.6b`) on
start and serves at `http://ollama-embed:11434` inside the compose network.
Point `WIKANTIK_EMBEDDING_BASE_URL` at your own reachable host instead if you
don't want the bundled sidecar.

**`wikantik-custom.properties` (bare-metal path):**

```properties
wikantik.genai.mode = embeddings-only
wikantik.search.embedding.base-url = http://<your-embedding-host>:11434
```

`wikantik.search.hybrid.enabled` needs no override here — it defaults to
`true` (`ini/wikantik.properties`) and stays effective under this tier.

---

## Going beyond search: chat inference

The Knowledge Graph (entity extraction and proposal judging) was removed, so there is no longer a
separate "knowledge" tier and no `ANTHROPIC_API_KEY` to provision for extraction. The only chat-inference
call sites left are the bundle's LLM reranker and query-decomposition planner, both off by default. To
allow them, set `WIKANTIK_GENAI_MODE=full` (bare metal: `wikantik.genai.mode = full`) and enable the flag
you want; see the caveats below.

---

## Verifying a tier is actually enforced

### 1. `/admin/llm-activity` — proves which subsystems actually called out

`GET /admin/llm-activity` (admin-only, `AdminAuthFilter`) is a live, in-memory
log of every LLM call — subsystem, backend, model, status, duration. It
covers the three LLM call sites: `EMBEDDING`,
`SECTION_RERANK` and `QUERY_DECOMPOSITION` (`wikantik-main/src/main/java/com/wikantik/llm/activity/Subsystem.java`).

Filter with `?subsystem=embedding` (or `section_rerank`, `query_decomposition`) and `?status=ok|error|in_flight`.

**Status caveat for `SECTION_RERANK` / `QUERY_DECOMPOSITION`:** these two
delegates (`LlmSectionReranker.rerank`, `LlmQueryPlanner.plan`) catch every
backend failure internally by contract and degrade to dense-order /
single-pass — they never throw. Their activity entries therefore report
`status=ok` even during a real Ollama outage; `?status=error` finds outages
only for `EMBEDDING`, whose client
does throw. For the rerank/decomposition subsystems, treat an unusually short
`duration` or a drop-off in call volume — not `error` status — as the outage
signal, and check the wikantik logs for the delegates' own degradation
warnings.

Under the **search** tier, after a few real searches/bundle requests, the log
should show **only** `EMBEDDING` entries — zero `SECTION_RERANK` or
`QUERY_DECOMPOSITION` calls, since the
ceiling forces the reranker/planner back to identity/passthrough, regardless of their own flags. That makes the ceiling provable from the log rather than from reading source:
an operator who flips `wikantik.bundle.reranker.enabled=true` by mistake under
`embeddings-only` sees no `SECTION_RERANK` entries.

Under **core**, the log should show nothing at all (no calls of any
subsystem). Under `genai.mode=full`, `EMBEDDING` appears; `SECTION_RERANK`/`QUERY_DECOMPOSITION` only
appear if you've separately opted into `wikantik.bundle.reranker.enabled` /
`wikantik.bundle.decomposition.enabled` (both off by default — see
[caveats](#bundle-llm-levers-stay-off-by-default-the-ceiling-guards-against-accidental-re-enable)).

Gating: the log itself is controlled by `wikantik.llm_activity.enabled`
(default `true`, `ini/wikantik.properties`). It is a **process-wide
singleton** (`LlmActivityLogHolder`) — created once, on first use, from
whatever properties were live at that moment. Changing the flag requires a
restart to take effect, and once a call is disabled, no per-call recording
overhead is paid (the wrapper classes are simply never constructed — see
`BundleRerankers.recordReranker`/`recordPlanner`, which return the
undecorated reranker/planner untouched when the log is disabled).

### 2. `GET /api/capabilities` — reflects the effective feature state

Anonymous, public endpoint (`com.wikantik.rest.CapabilitiesResource`) the SPA
uses to gate its own navigation. Returns:

```json
{
  "hybridSearch": true,
  "genaiMode": "embeddings-only",
  "ontology": true,
  "connectors": true,
  "citations": true
}
```

`genaiMode` is the lowercase-hyphenated `GenAiMode` token
(`CapabilitiesResource.toToken`); the booleans are `wikantik.search.hybrid.enabled`, `wikantik.ontology.enabled`,
`wikantik.connectors.enabled`, `wikantik.citations.enabled` — all default `true`.

#### hybridSearch is ceiling-adjusted; the other booleans are raw flags

`hybridSearch` is the **effective** value: the raw
`wikantik.search.hybrid.enabled` property (default `true`) ANDed with
`mode.allowsEmbeddings()`. Under `core` (`genai.mode=none`) the endpoint
reports `hybridSearch: false` even with the property at its default — the
response matches what retrieval actually does. A parity test
(`CapabilitiesResourceTest`) locks this to `EmbeddingConfig.fromProperties`'s
computation so the two cannot silently drift. The remaining booleans
(`ontology`, `connectors`, `citations`) are direct property
reads, since their subsystems have no ceiling interaction.

### 3. Warn-log lines when the ceiling suppresses a feature

Each of the call sites logs a `WARN` naming the property and the mode,
the moment an explicitly-enabled feature gets forced off:

| Feature | Log line (approximate) |
|---------|--------------------------|
| Embeddings | `wikantik.genai.mode={} disallows embeddings; forcing embedding client disabled` |
| Bundle reranker (legacy flag) | `wikantik.genai.mode={} disallows chat inference; ignoring wikantik.bundle.reranker.enabled=true, using identity reranker` |
| Bundle reranker (chain form) | `wikantik.genai.mode={} disallows chat inference; skipping 'llm' rerank stage` |
| Bundle query decomposition | `wikantik.genai.mode={} disallows chat inference; forcing bundle query-decomposition disabled` |

Grep the app log for `disallows chat inference` / `disallows embeddings` to
confirm the ceiling actually fired, as opposed to the feature simply never
having been enabled in the first place.

---

## Caveats

### Bundle LLM levers stay off by default; the ceiling guards against accidental re-enable

`wikantik.bundle.reranker.enabled` and `wikantik.bundle.decomposition.enabled`
both default to `false` (`ini/wikantik.properties`) — the bundle
ships dense-ordered/single-pass by default regardless of tier, because the
2026-06-13 measurement showed the LLM reranker is an ordering lever, not a
recall lever, at real per-request latency/cost. The `wikantik.genai.mode`
ceiling exists as a second line of defense: if an operator (or a future
config default change) flips either flag on under `embeddings-only`/`none`,
the ceiling silences it rather than letting a stray chat call through.

### Recording is observational, not a second enforcement mechanism

The `RecordingSectionReranker`/`RecordingQueryPlanner` decorators (`wikantik-main/src/main/java/com/wikantik/knowledge/bundle/`) only
wrap whatever `BundleServiceWiring.build()` already decided to construct —
they run strictly *after* the ceiling logic in `rerankerFor`/
`BundleDecompositionConfig.fromProperties` has already run, and never
influence that decision. If the ceiling has a bug, the activity log will
faithfully record the resulting (wrong) chat calls rather than catch it —
the log is a **verification** tool for the ceiling, not a substitute for it.
