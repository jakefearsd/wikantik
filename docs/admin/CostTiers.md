# GenAI Cost Tiers — Operator Reference

This page is for operators who cap or verify Wikantik's model/inference spend (embeddings — Wikantik performs no chat inference). That spend is bounded by a single ceiling property,
**`wikantik.genai.mode`** (`com.wikantik.api.config.GenAiMode`), plus a handful
of independent feature flags it gates. This page gives two named tiers —
**core**, **search** — with the exact `.env` preset for the
Docker/container path and the equivalent `wikantik-custom.properties` lines
for the bare-metal path, followed by how to verify a tier is actually
enforced and what it does *not* cover.

## How the ceiling works

`GenAiMode` (`wikantik-api/src/main/java/com/wikantik/api/config/GenAiMode.java`)
has two values, read from `wikantik.genai.mode`:

| Mode | `allowsEmbeddings()` |
|------|-----------------------|
| `full` (default) | yes |
| `none` | no |

The legacy value `embeddings-only` (from when chat inference still existed) is
still accepted: it parses to `full` and logs one INFO line saying so.

An absent value falls back to `full` silently. A blank or unrecognized value
also falls back to `full` (fail open, never fail closed on a typo) but logs a
warning naming it, so an operator who *wanted* enforcement can see the miss.

The mode is a **ceiling**, not a switch: it never turns a feature *on*. The one
model-using call site reads its own enable flag first, then ANDs it with the
ceiling:

| Call site | Flag | Ceiling check | Source |
|-----------|------|----------------|--------|
| Embedding client | `wikantik.search.hybrid.enabled` | `mode.allowsEmbeddings()` | `EmbeddingConfig.fromProperties` (`wikantik-main/src/main/java/com/wikantik/search/embedding/EmbeddingConfig.java`) |

It logs a `WARN` when an explicitly-enabled feature gets
overridden by the ceiling — see [Verify: warn-log lines](#3-warn-log-lines-when-the-ceiling-suppresses-a-feature) below.

---

## Tier: core — BM25 only, zero inference infrastructure

BM25 search, BM25-chunk context bundle, no embedding client, no sidecar to run or pay for.

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
endpoint.

**`.env` (Docker/container path):**

```bash
WIKANTIK_GENAI_MODE=full
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
wikantik.genai.mode = full
wikantik.search.embedding.base-url = http://<your-embedding-host>:11434
```

`wikantik.search.hybrid.enabled` needs no override here — it defaults to
`true` (`ini/wikantik.properties`) and stays effective under this tier.

---

## Chat inference

Wikantik performs no chat inference. The Knowledge Graph (entity extraction and proposal judging) was
removed in 2.5.13, and the bundle's LLM reranker and query-decomposition planner were removed in 2026-11
after measurement showed neither paid for its latency and cost. There is no tier above **search**.

---

## Verifying a tier is actually enforced

### 1. `/admin/embedding-activity` — proves which subsystems actually called out

`GET /admin/embedding-activity` (admin-only, `AdminAuthFilter`) is a live, in-memory
log of every embedding call — subsystem, backend, model, status, duration. Today it
records one subsystem, `EMBEDDING` (`wikantik-main/src/main/java/com/wikantik/search/embedding/activity/Subsystem.java`).

Filter with `?subsystem=embedding` and `?status=ok|error|in_flight`.

Under the **search** tier, after a few real searches/bundle requests, the log
should show `EMBEDDING` entries. Under **core**, the log should show nothing at all.

Gating: the log itself is controlled by `wikantik.embedding_activity.enabled`
(default `true`, `ini/wikantik.properties`). It is a **process-wide
singleton** (`EmbeddingActivityLogHolder`) — created once, on first use, from
whatever properties were live at that moment. Changing the flag requires a
restart to take effect.

### 2. `GET /api/capabilities` — reflects the effective feature state

Anonymous, public endpoint (`com.wikantik.rest.CapabilitiesResource`) the SPA
uses to gate its own navigation. Returns:

```json
{
  "hybridSearch": true,
  "genaiMode": "full",
  "ontology": true,
  "connectors": true,
  "citations": true
}
```

`genaiMode` is the lowercase `GenAiMode` token (`full` or `none`)
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

The embedding call site logs a `WARN` naming the property and the mode,
the moment an explicitly-enabled feature gets forced off:

| Feature | Log line (approximate) |
|---------|--------------------------|
| Embeddings | `wikantik.genai.mode={} disallows embeddings; forcing embedding client disabled` |

Grep the app log for `disallows embeddings` to
confirm the ceiling actually fired, as opposed to the feature simply never
having been enabled in the first place.
