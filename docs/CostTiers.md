# GenAI Cost Tiers — Operator Reference

Wikantik's LLM/inference spend is bounded by a single ceiling property,
**`wikantik.genai.mode`** (`com.wikantik.api.config.GenAiMode`), plus a handful
of independent feature flags it gates. This page gives three named tiers —
**core**, **search**, **knowledge** — with the exact `.env` preset for the
Docker/container path and the equivalent `wikantik-custom.properties` lines
for the bare-metal path, followed by how to verify a tier is actually
enforced and what it does *not* cover.

Every property and env var named below was checked against the code that
reads it; none are invented. See the "Verified against" line under each block.

## How the ceiling works

`GenAiMode` (`wikantik-api/src/main/java/com/wikantik/api/config/GenAiMode.java`)
has three values, read from `wikantik.genai.mode`:

| Mode | `allowsEmbeddings()` | `allowsChatInference()` |
|------|-----------------------|--------------------------|
| `full` (default) | yes | yes |
| `embeddings-only` | yes | no |
| `none` | no | no |

An absent, blank, or unrecognized value silently falls back to `full` (fail
open, never fail closed on a typo) — but a bad value still logs a warning
naming it, so an operator who *wanted* enforcement can see the miss.

The mode is a **ceiling**, not a switch: it never turns a feature *on*. Every
LLM-using call site reads its own enable flag first, then ANDs it with the
ceiling. The five call sites that do this today:

| Call site | Flag | Ceiling check | Source |
|-----------|------|----------------|--------|
| Embedding client | `wikantik.search.hybrid.enabled` | `mode.allowsEmbeddings()` | `EmbeddingConfig.fromProperties` (`wikantik-main/src/main/java/com/wikantik/search/embedding/EmbeddingConfig.java:94-101`) |
| KG entity extractor | `wikantik.knowledge.extractor.backend` | `mode.allowsChatInference()` | `EntityExtractorConfig.fromProperties` (`wikantik-main/src/main/java/com/wikantik/knowledge/extraction/EntityExtractorConfig.java:87-95`) |
| KG proposal judge | `wikantik.kg.judge.enabled` | `mode.allowsChatInference()` | `KgJudgeConfig.fromProperties` (`wikantik-main/src/main/java/com/wikantik/knowledge/judge/KgJudgeConfig.java:68-73`) |
| Bundle LLM reranker | `wikantik.bundle.reranker.enabled` (or an `llm` token in `wikantik.bundle.rerank.chain`) | `mode.allowsChatInference()` | `BundleServiceWiring.rerankerFor`/`buildChain` (`wikantik-main/src/main/java/com/wikantik/knowledge/bundle/BundleServiceWiring.java:213-266`) |
| Bundle query-decomposition planner | `wikantik.bundle.decomposition.enabled` | `mode.allowsChatInference()` | `BundleDecompositionConfig.fromProperties` (`wikantik-main/src/main/java/com/wikantik/knowledge/bundle/BundleDecompositionConfig.java:57-78`) |

Each of these logs a `WARN` when an explicitly-enabled feature gets
overridden by the ceiling — see [Verify: warn-log lines](#3-warn-log-lines-when-the-ceiling-suppresses-a-feature) below.

---

## Tier: core — BM25 only, zero inference infrastructure

BM25 search, BM25-chunk context bundle, no embedding client, no chat
inference, no sidecar to run or pay for.

**`.env` (Docker/container path):**

```bash
WIKANTIK_GENAI_MODE=none
WIKANTIK_KNOWLEDGE_ENABLED=false
```

**`wikantik-custom.properties` (bare-metal path):**

```properties
wikantik.genai.mode = none
wikantik.knowledge.enabled = false
wikantik.search.hybrid.enabled = false
```

> **No env passthrough for `wikantik.search.hybrid.enabled`.** `docker/entrypoint.sh`
> renders an env override for `WIKANTIK_GENAI_MODE` and `WIKANTIK_KNOWLEDGE_ENABLED`
> (lines 236–255) but has no corresponding block for the hybrid-search flag — it
> isn't in the container env-var surface at all. `mode=none` already forces the
> embedding client disabled at the ceiling (`EmbeddingConfig` line 98:
> `enabled = rawEnabled && mode.allowsEmbeddings()`), so dense retrieval is inert
> either way. But `GET /api/capabilities` reports the **raw** property, not the
> ceiling-adjusted value (see [caveat below](#capabilities-reports-raw-flags-not-ceiling-adjusted-effective-state)) —
> so a container-only core deploy will still show `hybridSearch: true` unless you
> also mount a bare-metal-style properties override setting
> `wikantik.search.hybrid.enabled = false` explicitly.

Verified against: `docker/entrypoint.sh:236-255`, `wikantik-main/src/main/java/com/wikantik/search/embedding/EmbeddingConfig.java:57,94-101`, `wikantik-main/src/main/resources/ini/wikantik.properties:1216` (default `true`).

---

## Tier: search — dense + BM25 hybrid, CPU embedding sidecar only

Dense+BM25 hybrid search, dense-chunk context bundle, a CPU-only embedding
endpoint — no chat inference anywhere (no KG extraction, no judge, no
reranker, no query decomposition).

**`.env` (Docker/container path):**

```bash
WIKANTIK_GENAI_MODE=embeddings-only
WIKANTIK_KNOWLEDGE_ENABLED=false
WIKANTIK_EMBEDDING_BASE_URL=http://ollama-embed:11434
```

Set `WIKANTIK_KNOWLEDGE_ENABLED=true` instead if you still want the Knowledge
Graph admin UI available for **manual curation only** (no automatic
extraction — `mode=embeddings-only` still blocks chat inference, so the
extractor/judge stay off regardless of this flag; only hand-authored KG
nodes/edges via `/admin/knowledge-graph/*` or `propose_knowledge` work).

`ollama-embed` is the cloud overlay's CPU-only Ollama sidecar
(`docker-compose.cloud.yml`, `--profile embeddings`, service block at line 292)
— it pulls `WIKANTIK_EMBEDDING_MODEL_TAG` (default `qwen3-embedding:0.6b`) on
start and serves at `http://ollama-embed:11434` inside the compose network.
Point `WIKANTIK_EMBEDDING_BASE_URL` at your own reachable host instead if you
don't want the bundled sidecar.

**`wikantik-custom.properties` (bare-metal path):**

```properties
wikantik.genai.mode = embeddings-only
wikantik.knowledge.enabled = false
wikantik.search.embedding.base-url = http://<your-embedding-host>:11434
```

`wikantik.search.hybrid.enabled` needs no override here — it defaults to
`true` (`ini/wikantik.properties:1216`) and stays effective under this tier.

Verified against: `docker-compose.cloud.yml:31-39,89-98,292,312`, `.env.example:117-130,215-223`, `docker/entrypoint.sh:65-76,236-266`, `wikantik-main/src/main/resources/ini/wikantik.properties:1222,1227` (`wikantik.search.embedding.backend`/`base-url` defaults).

---

## Tier: knowledge — full KG extraction + judge

Full chat inference: KG entity extraction, proposal judging, and (if you
opt in — both stay off-by-default) the bundle's LLM reranker/query-decomposition.

**`.env` (Docker/container path), Anthropic-hosted extraction:**

```bash
WIKANTIK_GENAI_MODE=full
WIKANTIK_KNOWLEDGE_ENABLED=true
WIKANTIK_EXTRACTOR_BACKEND=claude
ANTHROPIC_API_KEY=<key>
WIKANTIK_EMBEDDING_BASE_URL=http://ollama-embed:11434
```

**`.env`, BYO Ollama extraction instead:**

```bash
WIKANTIK_GENAI_MODE=full
WIKANTIK_KNOWLEDGE_ENABLED=true
WIKANTIK_EXTRACTOR_BACKEND=ollama
WIKANTIK_EMBEDDING_BASE_URL=http://<your-ollama-host>:11434
```

With `WIKANTIK_EXTRACTOR_BACKEND=ollama`, the extractor's own base URL
(`wikantik.knowledge.extractor.ollama.base_url`) has no dedicated env
passthrough — set it via a bare-metal-style properties override if it needs
to differ from the embedding host.

**`wikantik-custom.properties` (bare-metal path), Anthropic-hosted:**

```properties
wikantik.genai.mode = full
wikantik.knowledge.enabled = true
wikantik.knowledge.extractor.backend = claude
wikantik.knowledge.extractor.claude.model = claude-haiku-4-5
```

(`ANTHROPIC_API_KEY` is read directly from the process environment by
`EntityExtractorFactory`/`ClaudeEntityExtractor` — it is never a properties
file entry on either path.)

**`wikantik-custom.properties`, BYO Ollama:**

```properties
wikantik.genai.mode = full
wikantik.knowledge.enabled = true
wikantik.knowledge.extractor.backend = ollama
wikantik.knowledge.extractor.ollama.base_url = http://<your-ollama-host>:11434
```

Verified against: `docker-compose.cloud.yml:40-48`, `.env.example:117-140`, `docker/entrypoint.sh:73-80,268-278`, `wikantik-main/src/main/resources/ini/wikantik.properties:1293-1303` (extractor backend/model/base_url defaults), `wikantik-main/src/main/java/com/wikantik/knowledge/extraction/EntityExtractorConfig.java:87-95`.

---

## Verifying a tier is actually enforced

### 1. `/admin/llm-activity` — proves which subsystems actually called out

`GET /admin/llm-activity` (admin-only, `AdminAuthFilter`) is a live, in-memory
log of every LLM call — subsystem, backend, model, status, duration. As of
this change it covers **all five** LLM call sites: `EMBEDDING`,
`ENTITY_EXTRACTION`, `PROPOSAL_JUDGE`, and (new — Task 1.7) `SECTION_RERANK`
and `QUERY_DECOMPOSITION` (`wikantik-main/src/main/java/com/wikantik/llm/activity/Subsystem.java`).

Filter with `?subsystem=embedding` (or `entity_extraction`, `proposal_judge`,
`section_rerank`, `query_decomposition`) and `?status=ok|error|in_flight`.

**Status caveat for `SECTION_RERANK` / `QUERY_DECOMPOSITION`:** these two
delegates (`LlmSectionReranker.rerank`, `LlmQueryPlanner.plan`) catch every
backend failure internally by contract and degrade to dense-order /
single-pass — they never throw. Their activity entries therefore report
`status=ok` even during a real Ollama outage; `?status=error` finds outages
only for `EMBEDDING` / `ENTITY_EXTRACTION` / `PROPOSAL_JUDGE`, whose clients
do throw. For the rerank/decomposition subsystems, treat an unusually short
`duration` or a drop-off in call volume — not `error` status — as the outage
signal, and check the wikantik logs for the delegates' own degradation
warnings.

Under the **search** tier, after a few real searches/bundle requests, the log
should show **only** `EMBEDDING` entries — zero `ENTITY_EXTRACTION`,
`PROPOSAL_JUDGE`, `SECTION_RERANK`, or `QUERY_DECOMPOSITION` calls, since the
ceiling forces the reranker/planner back to identity/passthrough and the KG
extractor/judge off, regardless of their own flags. Before this change, the
reranker and decomposition planner made real chat calls with **no** visible
trace in this log — an operator flipping `wikantik.bundle.reranker.enabled=true`
by mistake under `embeddings-only` would see nothing wrong here even though
(pre-ceiling-fix) it would have been a real cost leak. The ceiling itself
already prevented the leak; this change makes that provable from the log
rather than from reading source.

Under **core**, the log should show nothing at all (no calls of any
subsystem). Under **knowledge**, `EMBEDDING`, `ENTITY_EXTRACTION`, and
`PROPOSAL_JUDGE` all appear; `SECTION_RERANK`/`QUERY_DECOMPOSITION` only
appear if you've separately opted into `wikantik.bundle.reranker.enabled` /
`wikantik.bundle.decomposition.enabled` (both off by default — see
[caveats](#bundle-llm-levers-stay-off-by-default-the-ceiling-guards-against-accidental-re-enable)).

Gating: the log itself is controlled by `wikantik.llm_activity.enabled`
(default `true`, `ini/wikantik.properties:1429`). It is a **process-wide
singleton** (`LlmActivityLogHolder`) — created once, on first use, from
whatever properties were live at that moment. Changing the flag requires a
restart to take effect, and once a call is disabled, no per-call recording
overhead is paid (the wrapper classes are simply never constructed — see
`BundleServiceWiring.recordReranker`/`recordPlanner`, which return the
undecorated reranker/planner untouched when the log is disabled).

### 2. `GET /api/capabilities` — reflects the raw flags

Anonymous, public endpoint (`com.wikantik.rest.CapabilitiesResource`) the SPA
uses to gate its own navigation. Returns:

```json
{
  "knowledgeGraph": true,
  "hybridSearch": true,
  "genaiMode": "embeddings-only",
  "ontology": true,
  "connectors": true,
  "citations": true
}
```

`genaiMode` is the lowercase-hyphenated `GenAiMode` token
(`CapabilitiesResource.toToken`); the booleans are `wikantik.knowledge.enabled`,
`wikantik.search.hybrid.enabled`, `wikantik.ontology.enabled`,
`wikantik.connectors.enabled`, `wikantik.citations.enabled` — all default `true`.

#### Capabilities reports raw flags, not ceiling-adjusted effective state

`hybridSearch` is `TextUtil.getBooleanProperty(props, PROP_HYBRID_SEARCH_ENABLED, true)`
— a direct read of the property, **not** ANDed with `mode.allowsEmbeddings()`.
So under `core` (`genai.mode=none`) with the property left at its default,
`/api/capabilities` reports `hybridSearch: true` even though the embedding
client is actually ceiling-disabled and dense retrieval is fully inert. Set
`wikantik.search.hybrid.enabled = false` explicitly (bare-metal properties
override; no env passthrough exists) if you want the capabilities response to
match reality.

### 3. Warn-log lines when the ceiling suppresses a feature

Each of the five call sites logs a `WARN` naming the property and the mode,
the moment an explicitly-enabled feature gets forced off:

| Feature | Log line (approximate) |
|---------|--------------------------|
| Embeddings | `wikantik.genai.mode={} disallows embeddings; forcing embedding client disabled` |
| Entity extractor | `wikantik.genai.mode={} disallows chat inference; forcing entity-extractor backend '{}' to 'disabled'` |
| KG proposal judge | `wikantik.genai.mode={} disallows chat inference; forcing KG judge disabled` |
| Bundle reranker (legacy flag) | `wikantik.genai.mode={} disallows chat inference; ignoring wikantik.bundle.reranker.enabled=true, using identity reranker` |
| Bundle reranker (chain form) | `wikantik.genai.mode={} disallows chat inference; skipping 'llm' rerank stage` |
| Bundle query decomposition | `wikantik.genai.mode={} disallows chat inference; forcing bundle query-decomposition disabled` |

Grep the app log for `disallows chat inference` / `disallows embeddings` to
confirm the ceiling actually fired, as opposed to the feature simply never
having been enabled in the first place.

---

## Caveats

### `wikantik-extract-cli` bypasses the ceiling by design

The standalone `wikantik-extract-cli` module (`BootstrapExtractionCli` and
friends) is an operator-invoked, args-driven batch tool — it has no
`wikantik.genai.mode` read anywhere in its source. Running it against a
`core`- or `search`-tier deployment still performs real chat-inference calls
against whatever backend its own CLI args/config point at. This is
deliberate: it's an explicit, offline, human-invoked action, not something
that runs as part of normal save-time or query-time traffic — the ceiling
protects the always-on request path, not one-off operator tooling.

### Bundle LLM levers stay off by default; the ceiling guards against accidental re-enable

`wikantik.bundle.reranker.enabled` and `wikantik.bundle.decomposition.enabled`
both default to `false` (`ini/wikantik.properties:1445,1525`) — the bundle
ships dense-ordered/single-pass by default regardless of tier, because the
2026-06-13 measurement showed the LLM reranker is an ordering lever, not a
recall lever, at real per-request latency/cost. The `wikantik.genai.mode`
ceiling exists as a second line of defense: if an operator (or a future
config default change) flips either flag on under `embeddings-only`/`none`,
the ceiling silences it rather than letting a stray chat call through.

### Recording is observational, not a second enforcement mechanism

The `RecordingSectionReranker`/`RecordingQueryPlanner` decorators added in
Task 1.7 (`wikantik-main/src/main/java/com/wikantik/knowledge/bundle/`) only
wrap whatever `BundleServiceWiring.build()` already decided to construct —
they run strictly *after* the ceiling logic in `rerankerFor`/
`BundleDecompositionConfig.fromProperties` has already run, and never
influence that decision. If the ceiling has a bug, the activity log will
faithfully record the resulting (wrong) chat calls rather than catch it —
the log is a **verification** tool for the ceiling, not a substitute for it.
