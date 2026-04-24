---
title: Agent-Grade Content Design
cluster: wikantik-development
type: design
status: proposed
date: '2026-04-24'
author: claude-opus
summary: Detailed design for shaping wiki content specifically for agent consumption — runbook page type, verification metadata, a token-optimised /for-agent projection endpoint, a scheduled retrieval-quality CI loop using the existing RetrievalExperimentHarness, and an agent-cookbook cluster. Addresses the "narrative-for-humans" problem and the unmeasured-retrieval-quality risk.
tags:
- design
- agent-context
- runbook
- retrieval-quality
- ci
- verification
related:
- StructuralSpineDesign
- HybridRetrieval
- RetrievalExperimentHarness
- GoodMcpDesign
- WikantikDevelopment
---

# Agent-Grade Content Design

## Problem

Wikantik's pages are human-authored articles. They work well for humans and for coarse retrieval. They do not work well for the specific task of **shaping context for AI coding agents** because:

1. **No verification metadata.** An agent reading `AgentMemory.md` cannot tell whether its claims were verified by a human last week or auto-generated eighteen months ago and never revisited. YAML has `auto-generated: true` on some pages, but nothing tells the agent *whether a human has vetted the output since*, nor how confident to be.
2. **No agent-shaped content types.** Every page is a narrative article. There are no runbooks ("when you need X, do Y, cite Z"), no FAQ entries, no decision trees. Agents must extract imperative advice from expository prose — high token cost, lossy extraction.
3. **No token-optimised projection.** An agent calling `get_page` receives the full Markdown body. Most coding questions need the summary, the key facts, the typed relations, and maybe one or two section headings — not the 4000-word essay. There is no endpoint that projects a page into its agent-useful subset.
4. **No retrieval-quality feedback loop.** `RetrievalExperimentHarness.md` describes an offline evaluation harness. It exists. It has not been run on a schedule. Recent refactors (`GraphProjector` retirement, tool consolidation, embedding model churn) proceeded with no regression gate — the team cannot tell whether retrieval quality has drifted.

Point 4 is the deepest of the four: you cannot improve what you do not measure. Without continuous retrieval evaluation, every other improvement in this document is unfalsifiable.

This design addresses all four problems with a small, coherent set of changes.

## Goals

1. Introduce verification metadata so agents can weight sources by confidence and freshness.
2. Introduce `type: runbook` as a first-class, schema-validated page type for agent-consumable procedural content.
3. Expose a token-optimised projection (`GET /api/pages/{id}/for-agent`) and its MCP twin.
4. Run `RetrievalExperimentHarness` on a schedule, publish results, and fail builds on regression.
5. Seed an "Agent Cookbook" cluster of runbooks that answer the actual coding-context questions agents ask this wiki.
6. Upgrade every existing MCP tool description to include example inputs and outputs.

## Non-goals

- Auto-verifying page content (human verification is the ground truth; auto-checks are a separate concern).
- Rewriting existing narrative articles. They remain as-is. Runbooks are added *alongside* narrative content, linked via `derived-from` relations (see [StructuralSpineDesign](StructuralSpineDesign)).
- Building a general-purpose eval platform. The retrieval CI stays targeted at wiki retrieval quality — nDCG on a curated query set.
- Replacing full-page rendering. `/for-agent` is a projection; `/wiki/{slug}?format=md` and the SPA remain.

## Data model

### Frontmatter additions

New fields, all optional (backfill-friendly):

```yaml
---
title: Choosing a Retrieval Mode
type: runbook
cluster: agent-cookbook
canonical_id: 01H8G3Z2E7FD8R1Q4V9X2T0NMP
audience: [agents, humans]
verified_at: 2026-04-23T16:45:00Z
verified_by: jakefear
confidence: authoritative
# runbook-specific (validated when type == runbook)
runbook:
  when_to_use:
    - Agent needs to pick between BM25-only, hybrid, and graph-traversal retrieval for a query
    - Agent is mid-workflow and suspects the default retriever is losing recall
  inputs:
    - Query text
    - Optional cluster or tag filter
    - Optional recency bias
  steps:
    - Start with /knowledge-mcp `search_knowledge` (hybrid default)
    - If top-5 results all share a cluster, call `list_pages_by_filter` with cluster=X to broaden
    - If query is about a named entity, call `query_nodes` on the graph
    - If no mode returns >3 relevant hits, fall back to `/api/search` BM25 with a loosened query
  pitfalls:
    - Do not chain more than 3 retrieval calls — budget it
    - Cite canonical_ids, not slugs, in the final answer (slugs are unstable)
    - `find_similar` uses mention centroids; it is not a semantic substitute for `search_knowledge`
  related_tools:
    - /knowledge-mcp/search_knowledge
    - /knowledge-mcp/find_similar
    - /knowledge-mcp/traverse_relations
    - /api/search
  references:
    - HybridRetrieval
    - KnowledgeGraphRerank
---
```

- **`audience`** — `[humans]`, `[agents]`, or `[humans, agents]`. Defaults to `[humans, agents]`. An agent-only page is one whose value *for a human reader* is low enough it can be omitted from human navigation (e.g. a dense tool-recipe that would read poorly as an article).
- **`verified_at`** — ISO timestamp of last human verification. Empty means unverified.
- **`verified_by`** — author handle of verifier.
- **`confidence`** — `authoritative | provisional | stale`. `authoritative`: verified recently (< 90 days) by a trusted author. `provisional`: AI-generated or unverified. `stale`: verified > 90 days ago or flagged for re-review.
- **`runbook`** — required when `type: runbook`. Contains six sub-keys (schema-enforced): `when_to_use` (list of strings), `inputs` (list), `steps` (ordered list), `pitfalls` (list), `related_tools` (list of endpoint strings or tool names), `references` (list of canonical_ids or page titles).

### Confidence transitions

Confidence is computed nightly from verification data and explicit overrides:

```
confidence(now, verified_at, explicit_override) =
  explicit_override                    if set
  else "stale"                         if verified_at is null or (now - verified_at) > 90d
  else "authoritative"                 if verifier in trusted_authors
  else "provisional"
```

An admin page at `/admin/verification` lists every page sorted by `confidence` + days-since-verification; batch-verify actions update `verified_at` and `verified_by` in frontmatter via a `VerifyPagesTool` call (already exists in `wikantik-admin-mcp`; extend it to also write verification fields).

### Database schema

Verification data is mirrored to the DB for fast admin queries and for the `/for-agent` projection's hot path.

**Migration `V014__verification_and_runbook.sql`** (idempotent):

```sql
CREATE TABLE IF NOT EXISTS page_verification (
    canonical_id     CHAR(26)    PRIMARY KEY REFERENCES page_canonical_ids(canonical_id) ON DELETE CASCADE,
    verified_at      TIMESTAMPTZ,
    verified_by      VARCHAR(64),
    confidence       VARCHAR(16) NOT NULL CHECK (confidence IN ('authoritative','provisional','stale')),
    audience         VARCHAR(32) NOT NULL DEFAULT 'humans-and-agents'
        CHECK (audience IN ('humans','agents','humans-and-agents')),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS ix_page_verification_confidence ON page_verification(confidence);
CREATE INDEX IF NOT EXISTS ix_page_verification_verified_at ON page_verification(verified_at);

GRANT SELECT, INSERT, UPDATE, DELETE ON page_verification TO :app_user;

CREATE TABLE IF NOT EXISTS trusted_authors (
    login_name       VARCHAR(64)  PRIMARY KEY,
    notes            TEXT,
    added_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

GRANT SELECT, INSERT, UPDATE, DELETE ON trusted_authors TO :app_user;
```

`page_verification` is derived from frontmatter on every `PAGE_SAVE` event, identical pattern to the relation sync in [StructuralSpineDesign](StructuralSpineDesign).

### Retrieval-quality tables

**Migration `V015__retrieval_quality.sql`**:

```sql
CREATE TABLE IF NOT EXISTS retrieval_query_sets (
    id               VARCHAR(64)  PRIMARY KEY,
    name             VARCHAR(128) NOT NULL,
    description      TEXT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS retrieval_queries (
    query_set_id     VARCHAR(64)  NOT NULL REFERENCES retrieval_query_sets(id) ON DELETE CASCADE,
    query_id         VARCHAR(64)  NOT NULL,
    query_text       TEXT         NOT NULL,
    expected_ids     CHAR(26)[]   NOT NULL,        -- canonical_ids of relevant pages
    PRIMARY KEY (query_set_id, query_id)
);

CREATE TABLE IF NOT EXISTS retrieval_runs (
    run_id           BIGSERIAL    PRIMARY KEY,
    query_set_id     VARCHAR(64)  NOT NULL REFERENCES retrieval_query_sets(id) ON DELETE CASCADE,
    started_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    finished_at      TIMESTAMPTZ,
    mode             VARCHAR(32)  NOT NULL,        -- bm25 | hybrid | hybrid_graph
    ndcg_at_5        NUMERIC(5,4),
    ndcg_at_10       NUMERIC(5,4),
    recall_at_20     NUMERIC(5,4),
    mrr              NUMERIC(5,4),
    notes            JSONB
);

CREATE INDEX IF NOT EXISTS ix_retrieval_runs_set_start ON retrieval_runs(query_set_id, started_at DESC);

GRANT SELECT, INSERT, UPDATE, DELETE ON retrieval_query_sets, retrieval_queries, retrieval_runs TO :app_user;
```

## `/for-agent` projection

### `GET /api/pages/{canonical_id}/for-agent`

A token-optimised projection of a page. Lives in `wikantik-rest` alongside `PageResource`. Reads from `PageManager`, `FrontmatterParser`, `StructuralIndexService` (relations), and `ReferenceManager` (backlinks).

```json
{
  "data": {
    "id":      "01H8G3Z1K6Q5W7P9X2V4R0T8MN",
    "slug":    "HybridRetrieval",
    "title":   "Hybrid Retrieval",
    "type":    "article",
    "cluster": "wikantik-development",
    "audience": ["humans","agents"],
    "confidence": "authoritative",
    "verified_at": "2026-04-22T11:10:00Z",
    "verified_by": "jakefear",
    "updated":  "2026-04-22T11:10:00Z",

    "summary":  "Operator reference for Wikantik's BM25 + dense hybrid retrieval — how it is wired, how it fails, how to configure it, and which Prometheus metrics it publishes.",

    "key_facts": [
      "Retrieval fuses BM25 and dense embeddings via Reciprocal Rank Fusion (RRF, k=60).",
      "When the embedding service is unavailable, /api/search falls back to BM25 with no user-visible error.",
      "Dense embeddings are generated by Ollama (nomic-embed-v1.5 default) and cached in InMemoryChunkVectorIndex.",
      "Graph-aware rerank boosts pages whose chunks share an entity with the top-k hits."
    ],

    "headings_outline": [
      {"level":2,"text":"Wiring"},
      {"level":2,"text":"Failure modes"},
      {"level":2,"text":"Configuration"},
      {"level":2,"text":"Metrics"}
    ],

    "typed_relations": {
      "outgoing": [
        {"type":"example-of","target_id":"01H8G3Z1PRN5Q3X4T9M2V7K0AB","target_slug":"InformationRetrieval"},
        {"type":"prerequisite-for","target_id":"01H8G3Z2E7FD8R1Q4V9X2T0NMP","target_slug":"RetrievalExperimentHarness"}
      ],
      "incoming": [
        {"type":"part-of","source_id":"01H8G3Z3Q0V2W5X7P8N9M1K4TF","source_slug":"WikantikDevelopment"}
      ]
    },

    "recent_changes": [
      {"version":42,"at":"2026-04-22T11:10:00Z","author":"jakefear","summary":"Add graph-aware rerank section"}
    ],

    "mcp_tool_hints": [
      {"tool":"/knowledge-mcp/search_knowledge","when":"Find pages about hybrid retrieval"},
      {"tool":"/knowledge-mcp/retrieve_context","when":"Assemble answer-context for a retrieval question"}
    ],

    "runbook": null,

    "full_body_url": "/api/pages/HybridRetrieval",
    "raw_markdown_url": "/wiki/HybridRetrieval?format=md"
  }
}
```

Design notes:

- **Budget.** Target size: ≤ 4 KB for an article, ≤ 8 KB for a runbook including its steps. Measured in characters; emitted as `wikantik_for_agent_response_bytes` (Prometheus histogram).
- **Key facts.** Computed during page save by the same summariser that populates `summary`. Prefer frontmatter-authored `key_facts` when present; otherwise extract from the first paragraphs by selecting sentences that contain definite statements (simple heuristic: sentences with a verb and a named entity or numeric value). Cache by content hash.
- **MCP tool hints.** Authors can declare `mcp_tool_hints` in frontmatter for runbooks; for non-runbooks the service synthesises zero or more hints from the page's tags and cluster.
- **Runbook section** appears only when `type: runbook`, and contains the full `runbook:` block verbatim (already budget-sized by authoring conventions).
- **Graceful degradation.** If the projection service fails (e.g. `ReferenceManager` timeout), return a stripped response with `{degraded: true, reason: "…"}` — never fail silently. The projection is cheap enough to memoise per (canonical_id, updated_at).

### MCP twin

`/knowledge-mcp` adds a `get_page_for_agent` tool with the same semantics. The tool description in its JSON schema **includes** a worked example payload (the first 1200 bytes of the response above), following the updated tool-description convention documented below.

### Authoring

Authors do not write `key_facts`, `headings_outline`, `recent_changes`, `typed_relations`, or `mcp_tool_hints` — those are projections of existing data. They *do* write `summary`, `audience`, `verified_at`, `verified_by`, `confidence`, and, for runbooks, the `runbook:` block.

## The `runbook` page type

### Validator

`FrontmatterParser` validates that when `type: runbook`:

- `runbook:` is present.
- `runbook.when_to_use` has ≥ 1 entry.
- `runbook.steps` has ≥ 2 entries.
- `runbook.pitfalls` has ≥ 1 entry (may be the explicit string `"(none known)"` — forcing the author to *think* about pitfalls, even if they end up declaring none).
- `runbook.related_tools` entries match `/api/*`, `/knowledge-mcp/*`, `/wikantik-admin-mcp/*`, `/tools/*`, or bare MCP tool names.
- `runbook.references` entries resolve to canonical_ids or existing page titles.

Invalid runbooks are rejected at save time (same behaviour as an invalid frontmatter schema today).

### Body convention

Runbook Markdown body is freeform and typically short. Recommended structure:

```markdown
# <title>

<one-paragraph intent>

## When to use this runbook
<expanded version of frontmatter when_to_use>

## Context
<any background a reader needs>

## Walkthrough
<prose version of steps, with linked references>

## Pitfalls
<elaboration of frontmatter pitfalls>
```

`/for-agent` ignores the body prose and returns only the structured `runbook:` block plus summary/headings — the body prose is for the `/wiki/{slug}` human reader.

## Agent cookbook cluster

A new cluster `agent-cookbook` seeded with ~15 runbooks that target the actual questions coding agents ask this wiki. These are scenario-keyed, not topic-keyed.

Initial cookbook set (hub page: `AgentCookbook.md`):

1. **ChoosingARetrievalMode** — BM25 vs hybrid vs graph traversal.
2. **AnsweringRestApiQuestions** — how to find the right endpoint, method, and permission model.
3. **CitingAWikiPage** — use canonical_id not slug; link format.
4. **ExploringAModulesApiSurface** — grep patterns + MCP tools for a new module.
5. **FindingTheRightMcpTool** — decision tree across 28 tools.
6. **PlanningAMigrationChange** — what tables, files, and tests to touch for a schema change.
7. **DebuggingFailingIntegrationTests** — port conflicts, Cargo, pgvector.
8. **VerifyingAnAgentGeneratedPage** — the verification workflow end-to-end.
9. **WritingANewMcpTool** — scaffolding, registration, description convention, examples.
10. **InterpretingHybridRetrievalMetrics** — what the Prometheus gauges mean.
11. **HandlingEmbeddingServiceOutages** — the fail-closed path and how to check it.
12. **ProposingKnowledgeGraphEdges** — ProposeKnowledgeTool workflow.
13. **RunningTheRetrievalQualityHarness** — how to invoke, read results, and calibrate thresholds.
14. **BuildingAndDeployingLocally** — one-screen canonical version.
15. **WhatToDoWhenANonExistentFunctionIsCited** — memory-vs-reality verification pattern.

Authoring these is the gating work for this design — a runbook type without seed content is a schema without a corpus.

## Retrieval-quality CI

### Scheduler

A new `RetrievalQualityRunner` in `wikantik-knowledge/src/main/java/com/wikantik/knowledge/eval/`. Uses the existing Java `ScheduledExecutorService` pattern (the `BootstrapEmbeddingIndexer` already scheduled in `KnowledgeMcpInitializer` is the reference implementation). No Quartz dependency — one scheduled thread is enough.

```java
package com.wikantik.knowledge.eval;

public class RetrievalQualityRunner {
    // Runs nightly at 03:00 UTC by default; configurable via wikantik.properties.
    public void scheduleNightly();

    // One-shot trigger — for admin UI + tests.
    public RetrievalRunResult runNow(String querySetId, RetrievalMode mode);

    // Fires for every (query_set × mode) cross-product.
    // Writes to retrieval_runs, publishes Prometheus gauges.
}
```

Modes evaluated on every run: `bm25`, `hybrid` (default), `hybrid_graph` (when `KnowledgeGraphService` is configured). This catches both absolute-quality regressions and mode-relative regressions.

### Query sets

Query sets live as data, seeded via migration + an admin UI at `/admin/retrieval-quality/query-sets`. First query set — `core-agent-queries` — is hand-curated from real agent transcripts and covers:

- **Factual lookup:** *"how does hybrid retrieval fail?"*, *"where are MCP tools registered?"*
- **Runbook lookup:** *"how do I verify a page?"*, *"which tool enumerates tags?"*
- **Cross-cluster:** *"what does this wiki say about agent memory?"* (should surface both `AgentMemory` and `AgentGradeContentDesign`).
- **Negative:** *"cold fusion"* (should return no high-confidence hits; a flood of hits = over-eager retrieval).

Each query names its `expected_ids` (canonical_ids of the pages a perfect retriever would return in the top-k). Authors grow the set; regressions that require adding a new query are a red flag (drift between retriever quality and authoring reality).

### Metrics and thresholds

Per run, emit:

| Metric | Type | Alert threshold |
|--------|------|-----------------|
| `wikantik_retrieval_ndcg_at_5{set,mode}` | gauge | < 0.75 absolute, or > 5 % relative drop week-over-week |
| `wikantik_retrieval_ndcg_at_10{set,mode}` | gauge | < 0.80 |
| `wikantik_retrieval_recall_at_20{set,mode}` | gauge | < 0.85 |
| `wikantik_retrieval_mrr{set,mode}` | gauge | < 0.60 |
| `wikantik_retrieval_run_duration_seconds` | histogram | info-only |
| `wikantik_retrieval_run_failed_total` | counter | alert on any increase |

Thresholds are initial guesses. Tune after two weeks of baseline runs; lock via config.

### CI integration

Nightly runs write to `retrieval_runs`. The admin UI at `/admin/retrieval-quality` shows per-set, per-mode sparklines over the last 30 days, with threshold-crossing runs highlighted. A GitHub Action (or whatever pre-merge gate exists) runs `mvn -pl wikantik-knowledge -Dtest=RetrievalQualitySmokeTest test` — a fast subset that runs the `hybrid` mode against a 5-query seed set and fails the build if nDCG@5 drops below threshold. The full nightly run is for production observability, not pre-merge.

### Failure modes

- **Embedding service down at run time.** `hybrid` and `hybrid_graph` modes fail-closed to BM25 (existing `HybridSearchService.rerank()` behaviour). The runner records the mode as degraded, does not alert on a nDCG drop for the degraded mode, does alert on the outage itself via a separate metric.
- **Query set references a deleted `canonical_id`.** Validator runs at schedule-create time and nightly. Offending query is skipped with a warning; alert after 7 days of skipping to force cleanup.

## Tool-description upgrade

Every MCP tool in `/wikantik-admin-mcp` and `/knowledge-mcp` gets:

1. **A one-sentence description** — already present, already good, keep as-is.
2. **A JSON schema with typed inputs and outputs** — already present via MCP spec.
3. **At least one worked example in the schema's `examples` field** — new. Example for `search_knowledge`:

```json
{
  "name": "search_knowledge",
  "description": "Full-text search across node names and properties. Bridges the gap between arbitrary queries and the graph's node IDs.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "query": {"type":"string","description":"Natural-language or keyword query"},
      "limit": {"type":"integer","default":10}
    },
    "required": ["query"],
    "examples": [
      {"query": "hybrid retrieval", "limit": 5}
    ]
  },
  "outputSchema": {
    "type": "object",
    "properties": {
      "results": {
        "type": "array",
        "items": {
          "type": "object",
          "properties": {
            "canonical_id": {"type":"string"},
            "slug": {"type":"string"},
            "title": {"type":"string"},
            "score": {"type":"number"}
          }
        }
      }
    },
    "examples": [
      {
        "results": [
          {"canonical_id":"01H8G3Z1K6Q5W7P9X2V4R0T8MN","slug":"HybridRetrieval","title":"Hybrid Retrieval","score":0.87},
          {"canonical_id":"01H8G3Z2E7FD8R1Q4V9X2T0NMP","slug":"RetrievalExperimentHarness","title":"Retrieval Experiment Harness","score":0.71}
        ]
      }
    ]
  }
}
```

This is a ~20-minute edit per tool × 28 tools = one afternoon of work. High ROI: agents seeing concrete example payloads call tools correctly on the first try dramatically more often than agents reasoning from type schemas alone.

## Admin UI

`/admin/verification`:

- Table of all pages: canonical_id, title, type, confidence, verified_at, verified_by, days-since-verification.
- Filters: by cluster, by confidence, by days-stale.
- Batch action: select N pages → click "Mark verified" → backend writes `verified_at = NOW()`, `verified_by = <current user>` to frontmatter of each selected page (uses the existing `VerifyPagesTool`, extended).
- Export: CSV of stale pages for offline review.

`/admin/retrieval-quality`:

- Per query-set, per mode: last 30-day sparklines of nDCG@5, nDCG@10, Recall@20, MRR.
- Threshold indicators (green / yellow / red) per metric.
- Drill-in: run detail showing per-query scores, click a query to see returned pages vs expected pages diffed.
- "Run now" button to trigger an on-demand run.

Both UIs are React components in `wikantik-frontend/src/admin/`.

## Migration path

### Phase 1 — Verification metadata (half sprint)

1. Frontmatter validator accepts `verified_at`, `verified_by`, `confidence`, `audience` (optional initially).
2. `V014__verification_and_runbook.sql`.
3. Nightly job computes `confidence` from `verified_at` + trusted-authors table; writes to DB.
4. Extend `VerifyPagesTool` in `wikantik-admin-mcp` to write verification fields in frontmatter and DB simultaneously.
5. Admin UI at `/admin/verification`.

### Phase 2 — `/for-agent` projection (half sprint)

1. `ForAgentProjectionService` in `wikantik-rest`.
2. `GET /api/pages/{canonical_id}/for-agent`.
3. `get_page_for_agent` MCP tool in `/knowledge-mcp`.
4. Memoise by `(canonical_id, updated_at)` in `wikantik-cache`.

### Phase 3 — Runbook type (half sprint)

1. Frontmatter `runbook:` block schema + validator.
2. `type: runbook` accepted in `page_canonical_ids.type`.
3. Runbook body renders with a custom template in the SPA (collapsed "Steps" / "Pitfalls" / "Related tools" blocks).
4. `/for-agent` projection inlines the runbook block.

### Phase 4 — Agent cookbook authoring (ongoing, parallel)

Fifteen runbooks, batched by authoring capacity. Kick-off: three runbooks covering the highest-traffic agent questions (**ChoosingARetrievalMode**, **FindingTheRightMcpTool**, **WritingANewMcpTool**). The cookbook cluster grows on demand; new runbooks are triggered by observed agent thrashing (tracked via MCP call-sequence logs).

### Phase 5 — Retrieval-quality CI (one sprint)

1. `V015__retrieval_quality.sql`.
2. Seed query set `core-agent-queries` (~30 queries) via a migration + curation session.
3. `RetrievalQualityRunner` + nightly schedule.
4. Prometheus metrics + admin dashboard.
5. Pre-merge smoke test in `wikantik-knowledge`.

### Phase 6 — Tool-description examples (quarter sprint)

Add one worked example per MCP tool. Pull-request-reviewed per server so the tone is consistent.

**Total effort:** ~3 dev-sprints of focused work, plus ongoing authoring for the cookbook cluster.

## Failure modes

| Failure | Detection | Response |
|---------|-----------|----------|
| `/for-agent` projection computation errors | Per-field try/catch | Return `{degraded: true, missing_fields: [...]}` with whatever fields did compute. Never fail silently. |
| Runbook validator rejects an in-progress page | Save time | Clear error with exact invalid field, link to runbook schema docs. |
| Trusted-authors table empty | Bootstrap | All verified pages computed as `provisional`. Admin warning banner. |
| Retrieval runner skips because embedding service is down | Runner logs + metric | Emit degraded-run metric; do not alert on nDCG drop for that mode; do alert on the outage. |
| Query-set stale (references deleted canonical_ids) | Nightly validator | Skip invalid queries with warning; alert after 7 days. |
| `/for-agent` projection exceeds budget (>8 KB) | Response histogram | Log `wikantik_for_agent_over_budget_total`; truncate `key_facts` or `headings_outline` first. |

Overarching principle, identical to [StructuralSpineDesign](StructuralSpineDesign) and [HybridRetrieval](HybridRetrieval): **degrade, never lie.** An agent reading `confidence: stale` or `{degraded: true}` knows exactly how much weight to put on the response.

## Observability

New Prometheus metrics:

- `wikantik_page_verification_confidence_total{confidence}` — distribution of confidence states across the page set
- `wikantik_page_verification_days_since_last_verification` — summary quantiles
- `wikantik_for_agent_response_bytes` — histogram of projection sizes
- `wikantik_for_agent_degraded_total{reason}` — counter
- `wikantik_retrieval_ndcg_at_{5,10}{set,mode}` / `wikantik_retrieval_recall_at_20{set,mode}` / `wikantik_retrieval_mrr{set,mode}` — gauges
- `wikantik_retrieval_run_duration_seconds` — histogram
- `wikantik_retrieval_run_failed_total` — counter

Grafana panel additions: "Content quality" dashboard with verification-state pie, staleness histogram, per-cluster confidence heatmap. "Retrieval quality" dashboard with per-mode sparklines and threshold overlays.

## Testing strategy

### Unit

- `FrontmatterRunbookValidatorTest` — runbook schema enforcement.
- `ConfidenceComputerTest` — all transition edges (verified_at null, >90d, trusted author, etc).
- `ForAgentProjectionServiceTest` — budget, graceful degradation per field.
- `RetrievalQualityRunnerTest` — mock search results, compute nDCG / MRR / Recall, write to runs table.

### Integration

- **REST `/for-agent`:** seed a page, `GET /api/pages/{id}/for-agent`, assert every field populated and under budget.
- **MCP `get_page_for_agent`:** seed page, call tool via Cargo-Tomcat, assert response shape matches tool schema's `outputSchema.examples[0]`.
- **Runbook save/load round-trip:** create runbook with all six sub-fields, re-read, verify preservation.
- **Retrieval CI end-to-end:** seed query set, run, assert rows in `retrieval_runs`, assert metrics emitted.

### Dogfooding

- Convert three existing agent-adjacent pages (`AgentMemory`, `AgentLoops`, `AgentPromptEngineering`) into *companion* runbooks (via `derived-from` relations) as a calibration exercise. Measure: do agents with access to `get_page_for_agent` on the runbook outperform agents reading raw markdown?
- Run the retrieval-quality harness weekly for six weeks before locking thresholds. Look for the smallest stable variance band across modes and sets.

## Interaction with StructuralSpineDesign

This design explicitly depends on [StructuralSpineDesign](StructuralSpineDesign) for:

- `canonical_id` (primary key for `page_verification`, referenced by retrieval query sets).
- `typed_relations` (populated in `/for-agent`).
- `list_pages_by_filter` (used by the admin verification UI and by agents discovering cookbook runbooks).
- Generated Main.md (the agent cookbook cluster needs to be visible in the generated cluster index).

If the structural spine ships first, this design plugs into it. If this design ships first (acceptable — the two are loosely coupled), the `canonical_id` requirement can be provisional: use page slug as a stand-in, and migrate to canonical_id once the structural spine lands. The DB schemas are designed so slug-based rows can be re-keyed without data loss.

## Open questions

1. **Trusted-authors governance.** Who curates the `trusted_authors` table? Proposal: self-managed by the primary maintainer (this is a single-developer repo); open it up if contributor count grows.
2. **`stale` confidence auto-downgrade.** 90-day threshold is a guess. Recommend tightening to 60 days for runbooks (high-action content), loosening to 180 days for reference articles. Per-type thresholds land as a follow-up once we have verification data.
3. **`/for-agent` for runbooks: do we include the body or just the structured block?** Proposal: structured block only. Authors who want prose rendered should link to `/api/pages/{id}` for the raw body. Revisit if agents report losing context.
4. **Retrieval CI: do we block merges on regression?** Proposal: pre-merge smoke test (5 queries, fast) blocks; nightly comprehensive run alerts only. Stricter pre-merge gates can come after threshold calibration.
5. **Agent cookbook discoverability.** Should the cookbook cluster be boosted in hybrid search (e.g. recency + confidence bonus)? Proposal: not initially — measure with the retrieval harness whether cookbook pages are findable; add a rerank boost only if they underperform.

## Related designs

- [StructuralSpineDesign](StructuralSpineDesign) — provides canonical IDs, typed relations, and listing APIs consumed by this design.
- [HybridRetrieval](HybridRetrieval) — the retrieval pipeline this design measures.
- [RetrievalExperimentHarness](RetrievalExperimentHarness) — the existing offline harness, promoted from occasional to scheduled in this design.
- [GoodMcpDesign](GoodMcpDesign) — the design principles new MCP tools in this plan must follow.
