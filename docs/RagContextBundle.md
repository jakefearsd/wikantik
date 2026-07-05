# RAG Context Bundle & Session Briefings

Wikantik delivers retrieval to AI agents as **RAG-as-a-Service**: the wiki does
the assembly work a RAG pipeline normally pushes onto the caller — ranking,
de-duplication, section selection, and citation — and returns a ready-to-ground
**context bundle**. It never synthesizes the answer itself. That boundary is a
deliberate architectural decision, recorded in
[ADR-0001](adr/0001-rag-returns-context-bundle-not-synthesized-answer.md): the
wiki assembles context with citations, and the calling model does the reasoning,
so every claim an agent makes traces back to a page and version you can open.

This document is the operator/integrator reference for the two agent-facing
surfaces built on that principle — the **context bundle** and the **session
briefing** — plus the **version-pinned citation** layer that keeps their
groundings honest. For the retrieval quality levers underneath (the chunker,
contextual embeddings, fusion weights), see
[HybridRetrieval.md](wikantik-pages/HybridRetrieval.md).

Both surfaces exist as a REST endpoint (for pipelines) and a mirrored MCP tool
(for agents), returning the same structure.

| Capability | REST | MCP (`/knowledge-mcp`) |
|---|---|---|
| Context bundle | `GET /api/bundle` | `assemble_bundle` |
| Session briefing | `GET /api/briefing` | `get_briefing` |
| Stale-citation curation | `GET /admin/drift/citations` | `list_stale_citations` |

---

## The context bundle

`GET /api/bundle?q=…` (and the `assemble_bundle` MCP tool) returns the
top-ranked, de-duplicated sections across the **whole corpus** — section-level,
not whole pages, and not a list of links to fetch yourself. Candidates are drawn
from a global pool of content chunks, so a highly relevant section is never
dropped just because its page ranked outside the top-N. Each returned section
carries a **version-pinned citation** (source page + the exact version it came
from), and the response carries a **coverage signal**.

### Query parameters (`/api/bundle`)

| Parameter | Required | Default | Meaning |
|---|---|---|---|
| `q` | **yes** | — | The natural-language query. Missing/blank → `400` with `{"error":"q (query) parameter required"}`. |
| `mode` | no | `hybrid` | Retrieval strategy: `hybrid` (BM25 + dense, RRF-fused), `dense` (vector-only), `lexical` (BM25-only). Case-insensitive; an unknown value returns a clear error listing the valid modes. An unavailable mode degrades to the default with a logged warning. |
| `k` | no | config | Override the number of sections returned (top-k). |
| `debug` | no | — | `debug=rankings` includes the per-candidate ranking breakdown for tuning (used by `bin/eval/sweep-bm25-fusion.py`). |

The `assemble_bundle` MCP tool takes the same `query` and `mode`; the no-`mode`
path is fully backward compatible with the original `assemble(query)` contract.

### The coverage signal

Every bundle response carries a `coverage` block so an agent can tell how
well-grounded an answer will be **before** it composes one:

| Field | Meaning |
|---|---|
| `sectionCount` | Number of sections in the bundle. |
| `distinctPageCount` | Number of distinct source pages represented. |
| `topSimilarity` | The best section's **true dense cosine** similarity (not a rank proxy). |
| `confidence` | `strong` / `partial` / `weak`, derived from `topSimilarity` against the thresholds below. |

Confidence is a **routing tool, not just telemetry**. A `strong` bundle means
answer from it; a `partial` or `weak` bundle is the agent's cue to widen the
search, ask a clarifying question, or fall back to a structured query
(`sparql_query`, the ontology, `list_clusters`) — the MCP tool descriptions
route count/enumeration questions there rather than at the prose bundle. Coverage
is **recounted after the ACL view-gate**: if access filtering thins the viewable
result below the strong floor, `strong` is downgraded to `partial`, so the signal
reflects what the caller can actually see.

Default thresholds (both configurable — see the config table): `topSimilarity ≥
0.55` → `strong`, `≥ 0.40` → `partial`, otherwise `weak`. These are provisional
defaults validated against production cosine distributions.

### Retrieval source

By default the bundle sources candidates from a **global chunk-level hybrid**
(dense + BM25, RRF-fused) rather than a page-gated hybrid — it beats page-gating
because it does not drop sections whose page ranks outside the top-N. The bundle
has its own fusion config (`wikantik.bundle.bm25.*`), independent of the main
search fusion. Set `wikantik.bundle.dense.enabled=false` to fall back to the
page-gated retrieval source.

> **Op gotcha:** the `inmemory` dense backend needs a restart after a re-index
> for the dense-chunk bundle to hydrate; `lucene-hnsw`/`pgvector` read from the
> DB and don't.

---

## Session briefings

Retrieval mid-task is reactive — the agent already has to know what to ask. A
**session briefing** front-loads the context an agent should have *before* it
starts: the conventions, runbooks, and design decisions for the area it is about
to work in. A coding agent (Claude Code, or any compatible assistant) requests
one at session start and injects the result as opening context.

`GET /api/briefing` (and the `get_briefing` MCP tool) assembles a budgeted,
de-duplicated, injection-ready briefing from any combination of pinned pages,
clusters, and the task prompt, and returns it as Markdown (default) or structured
JSON.

### Query parameters (`/api/briefing`)

| Parameter | Meaning |
|---|---|
| `pins` | CSV of page names to pin into the briefing verbatim. |
| `clusters` | CSV of clusters to draw representative context from. |
| `prompt` | The task prompt — used to retrieve additionally-relevant sections. |
| `budget` | Token budget for the assembled briefing (the assembler dedupes and trims to fit). |
| `scope_mode` | How pins/clusters/prompt are combined into the candidate scope. |
| `format` | `md` (default — injection-ready Markdown) or `json` (structured). |

Each assembled briefing is recorded in the `briefing_log` table (migration
`V044`) for telemetry — what was requested, how large the result was, whether the
budget bound. Portable client shims that wire the briefing into an agent runtime
(Claude Code, Antigravity) live under [`clients/`](../clients/).

---

## Version-pinned citations & self-healing

Grounding is only worth something if it stays true. An author (or a curator
agent) marks a claim's source inline in the page body with `cite://` markup; at
save time that becomes a first-class **citation edge** in the `citations` table —
**version-pinned and span-hashed** to the exact passage it grounds. These are the
same version pins that make a bundle section citable.

When a cited page is later edited, Wikantik does not blanket-invalidate everything
pointing at it. It **grades staleness at the span level** — did the cited passage
actually change, or just something elsewhere on the page? — and surfaces what
genuinely drifted:

- **`list_stale_citations`** (`/knowledge-mcp`) — for-agent stale-citation feed.
- **`GET /admin/drift/citations`** — the operator's bidirectional stale-citation
  dashboard (which pages cite a changed page, and which of a page's own citations
  have gone stale).

This is the self-healing half of RAG-as-a-Service: the corpus tells you when a
grounding has gone out of date instead of letting it rot silently. Full rationale
in [ADR-0005](adr/0005-persisted-citation-edges-and-stale-citation-curation.md).
`wikantik.citations.enabled` gates the citation subsystem (document *ingestion*
is unrelated and always on).

---

## Access control

Bundles and briefings are assembled from the same retrieval candidates as the
rest of the platform, so the **same page-level ACLs apply** — a section from a
restricted page never appears in a bundle or briefing for a caller whose session
cannot view it (and, as above, coverage is recounted after that gate). The REST
endpoints enforce the view ACL through the candidate retrieval, the same posture
as `/api/pages`. Both `/api/bundle` and `/api/search` sit in the **expensive
tier** of the public-surface rate limiter (see
[WikantikOperations.md § 1.5](WikantikOperations.md) — default 3 req/s per client
+ 10 req/s global).

---

## Configuration reference

All keys live in `ini/wikantik.properties`; override in
`wikantik-custom.properties` (bare-metal) or via the container's property
injection. Defaults shown.

| Key | Default | Purpose |
|---|---|---|
| `wikantik.bundle.dense.enabled` | `true` | Use the global dense-chunk source (vs. the page-gated fallback). |
| `wikantik.bundle.dense.top_k` | `300` | Chunk candidate pool size pulled before fusion/selection. |
| `wikantik.bundle.sections_per_page` | `20` | Max sections contributed per source page. |
| `wikantik.bundle.bm25.enabled` | `true` | Enable the chunk-level BM25 arm of the bundle's own hybrid fusion. |
| `wikantik.bundle.bm25.bm25_weight` / `.dense_weight` | `0.5` / — | Fusion weights for the bundle's BM25 ↔ dense RRF (independent of main search). |
| `wikantik.bundle.bm25.rrf_k` | — | RRF constant for the bundle fusion. |
| `wikantik.bundle.coverage.strong_similarity` | `0.55` | `topSimilarity` at/above which coverage is `strong`. |
| `wikantik.bundle.coverage.partial_similarity` | `0.40` | `topSimilarity` at/above which coverage is `partial` (else `weak`). |
| `wikantik.bundle.reranker.enabled` | `false` | LLM listwise reranker. **Default off** — measured to reorder without improving recall (a bad relevance judge under shuffled input). |
| `wikantik.citations.enabled` | — | Gate the `cite://` citation-edge subsystem (unrelated to document ingestion). |

The public-surface rate-limit knobs (`WIKANTIK_RATELIMIT_*`) that guard
`/api/bundle` are documented in
[DockerDeployment.md § Performance / search-backend tuning](DockerDeployment.md#performance--search-backend-tuning-optional)
and [WikantikOperations.md § 1.5](WikantikOperations.md).

## See also

- [ADR-0001](adr/0001-rag-returns-context-bundle-not-synthesized-answer.md) — why the bundle returns context, never a synthesized answer
- [ADR-0002](adr/0002-knowledge-graph-is-first-class-knowledge-base.md) — the Knowledge Graph as a first-class knowledge base and retrieval signal
- [ADR-0003](adr/0003-rag-in-process-module-human-machine-parity.md) — RAG as an in-process module with human/machine parity
- [ADR-0005](adr/0005-persisted-citation-edges-and-stale-citation-curation.md) — persisted citation edges + stale-citation curation
- [HybridRetrieval.md](wikantik-pages/HybridRetrieval.md) — the retrieval-quality levers underneath the bundle
- [Frontmatter.md](Frontmatter.md) — the metadata that feeds contextual embeddings and agent hints
