# RAG Context Bundle & Session Briefings

Wikantik delivers retrieval to AI agents as **RAG-as-a-Service**: the wiki does
the assembly work a RAG pipeline normally pushes onto the caller — ranking,
de-duplication, section selection, and citation — and returns a ready-to-ground
**context bundle**. It never synthesizes the answer itself. That boundary is a
deliberate architectural decision, recorded in
[ADR-0001](../adr/0001-rag-returns-context-bundle-not-synthesized-answer.md): the
wiki assembles context with citations, and the calling model does the reasoning,
so every claim an agent makes traces back to a page and version you can open.

This document is the operator/integrator reference for the two agent-facing
surfaces built on that principle — the **context bundle** and the **session
briefing** — plus the **version-pinned citation** layer that keeps their
groundings honest. For the retrieval quality levers underneath (the chunker,
contextual embeddings, fusion weights), see
[HybridRetrieval.md](../wikantik-pages/HybridRetrieval.md).

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
| `mode` | no | `hybrid` | Retrieval strategy: `hybrid` (BM25 + dense, RRF-fused), `dense` (vector-only), `lexical` (BM25-only). Case-insensitive; an unknown value returns a clear error listing the valid modes.  |
| `debug` | no | — | `debug=rankings` returns the raw dense + BM25 chunk rankings (id + score) instead of a bundle, for tuning (used by `bin/eval/sweep-bm25-fusion.py`). Requires the chunk-hybrid source (`wikantik.bundle.bm25.enabled`). |
| `k` | no | `500` | Only read with `debug=rankings`: how many ranked chunks to return. It does not change how many sections a normal bundle holds. |

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
| `confidence` | `strong` / `partial` / `weak`, derived from `topSimilarity` against the thresholds below; `unknown` when no dense similarity is available (`topSimilarity` is `-1.0`). |

Confidence is a **routing tool, not just telemetry**. A `strong` bundle means
answer from it; a `partial` or `weak` bundle is the agent's cue to widen the
search, ask a clarifying question, or fall back to a structured query
(`sparql_query`, the ontology, `list_clusters`) — the MCP tool descriptions
route count/enumeration questions there rather than at the prose bundle. Coverage
is **recounted after the ACL view-gate**: if access filtering thins a `strong` bundle to
fewer than 3 viewable sections, it is downgraded to `partial`, so the signal
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

> **Op gotcha:** only the `pgvector` dense backend (`wikantik.search.dense.backend`)
> reads through to the database, so its rows are queryable as soon as they commit.
> `inmemory` and `lucene-hnsw` build a separate index that is refreshed per page
> save and after a *successful* bootstrap embedding run. If a bootstrap ends in
> FAILED, the dense index stays stale and the bundle degrades to BM25-only until
> the next successful run or a restart. A `topSimilarity` of `-1.0` with
> `confidence: unknown` in the coverage block is the sign.

---

## Session briefings

Retrieval mid-task is reactive — the agent already has to know what to ask. A
**session briefing** front-loads the context an agent should have *before* it
starts: the conventions, runbooks, and design decisions for the area it is about
to work in. A coding agent (Claude Code, or any compatible assistant) requests
one at session start and injects the result as opening context.

`GET /api/briefing` (and the `get_briefing` MCP tool) assembles a budgeted,
de-duplicated, injection-ready briefing from any combination of pinned pages,
clusters, and the task prompt. `GET /api/briefing` returns structured JSON by
default and injection-ready Markdown with `format=md`; the `get_briefing` MCP tool
always returns Markdown.

### Query parameters (`/api/briefing`)

| Parameter | Meaning |
|---|---|
| `pins` | CSV of page names to pin into the briefing verbatim. |
| `clusters` | CSV of clusters to draw representative context from. Also scopes the briefing: see [Project scope](#project-scope-members-cited-elsewhere). |
| `prompt` | The task prompt — used to retrieve additionally-relevant sections. |
| `budget` | Token budget for the assembled briefing (the assembler dedupes and trims to fit). A non-integer value returns `400`. Default 6000 (`wikantik.briefing.default_budget`), capped at 24000 (`wikantik.briefing.max_budget`). |
| `scope_mode` | `prefer` (default: task-relevant hits outside the requested clusters become one-line pointers under "Elsewhere in the wiki", not body text) or `strict` (drop them). Has no effect without `clusters`. Any other value returns `400`. |
| `format` | `json` (default — structured) or `md` (injection-ready Markdown, `text/markdown`). Any other value returns `400`. Session hooks pass `format=md`. |

The `get_briefing` MCP tool takes `pins`, `clusters`, `prompt`, `budget` and `scope_mode` (no `format`) and returns Markdown.

At least one of `pins`, `clusters` or `prompt` is required, otherwise the
endpoint returns `400`. Input is capped at 25 pins and 10 clusters (200 members
per cluster); excess is truncated with a logged warning.

### Project scope: members, cited, elsewhere

A briefing for a cluster is built from tiers computed per request from the Page Graph
(structural-index membership plus every link the reference manager holds, body
wikilinks and frontmatter `related:` alike; no separate index or cache). The working
rule: **a citation from a project page to a shared page is that project's adoption of
it** ([ADR-0011](../adr/0011-project-context-is-derived-from-citations.md)).

| Tier | Contents | Order within the tier | Briefing origin |
|---|---|---|---|
| Pins | Pages named in `pins` | Request order | `pin` |
| 1 — members | Pages in a requested cluster (sub-cluster members included) | Hub first, then in-degree (how many other members link to the page), then pages with a summary, then `updated` newest first | `cluster` |
| 2 — cited | Pages *outside* the requested clusters that at least one member links to | Citers (how many members link to it), then reach (how many distinct clusters link to it), then `updated` newest first; at most `wikantik.briefing.scope.cited.max` pages | `cited` |
| Elsewhere | Task-relevant retrieval hits on pages in neither tier (`prefer` mode only) | Retrieval rank, one pointer per page, at most `wikantik.briefing.elsewhere.max` | `elsewhere` |

A project therefore receives the shared contracts it has linked to without any page
declaring it should: a Roller briefing carries `SimpleAgilityTelemetryContract` because
Roller pages cite it. The converse holds as well. A shared page that no page in the
project links to does not reach that project's briefing as a tier-2 item, however
important it is; adopt a contract by linking to it. With no `clusters`, or without the
structural index, there are no tiers and the briefing is selected as before (with the
existing "structural index unavailable" warning when clusters were requested). When link
data is unavailable the cited tier is empty and a warning says so;
`wikantik.briefing.scope.cited.enabled=false` switches the tier off.

**Task-relevant sections** come from the bundle's candidate pool
(`wikantik.briefing.candidate_pool`, default 36, requested through
`BundleAssemblyService.assemble(query, BundleOptions)`; `/api/bundle` and
`assemble_bundle` keep their own limits). The pool is split by tier, members then cited
pages, each in rank order; a page keeps at most `wikantik.briefing.sections_per_page`
(default 4) of its sections, in rank order, and the result is truncated to the token
budget. In `strict` mode sections outside tiers 1 and 2 are dropped; in `prefer` mode
they become `elsewhere` pointers rather than body text. The per-page cap applies to every
briefing, including one with no clusters.

A requested scope that matches no page at all (every cluster unknown) is not allowed to
cost the agent its content: under `prefer` the briefing is selected as if no clusters had
been requested (the `unknown cluster: <name>` warning remains), while `strict` keeps dropping
everything outside the scope. Likewise a failure while deriving the scope degrades to an
unscoped briefing with a `scope computation failed; clusters skipped` warning rather than
an error. A member the per-cluster cap leaves out of the listed members is still a member:
it is never reported as a cited page and its sections are not demoted to `elsewhere`.

**Pointers.** A member or cited page whose body does not fit the remaining budget becomes
a pointer (title and summary only). Cluster-member pointers are capped at
`wikantik.briefing.pointers.max` (default 30) and are emitted in rank order, so low
in-degree pages fall off first. Hitting the cap adds a `pointer list capped at <n>` warning.
Cited pointers do not count against it: that tier is already bounded by
`wikantik.briefing.scope.cited.max`, and counting them would let a large cluster spend the
whole cap before the first cited page. Pinned pages are never capped, and `elsewhere`
pointers spend no budget and have their own cap.

### Rendered markdown

`format=md` (and `get_briefing`) emit a `# Wiki context briefing` title and coverage line,
then these headings in this order, each omitted when it would be empty (so a briefing
without clusters has the same headings as before).

1. `## Task-relevant sections`
2. `## Standing context` (pins and tier-1 member bodies)
3. `## Contracts this project cites` (tier-2 bodies, then tier-2 items trimmed to pointer lines)
4. `## Elsewhere in the wiki` (out-of-scope pointers, grouped by cluster, as
   `slug › heading path (canonical_id)`)
5. `## Available on request` (budget-trimmed pin and cluster pointers)
6. A closing `> Briefing warnings: …` line when any assembly warning was raised

When retrieval hits were turned into `elsewhere` pointers, the coverage line gains a second
sentence, `<n> further sections from other projects are listed under "Elsewhere in the wiki".`
(`1 further section from another project is listed under …` for a single hit; `n` counts the
`elsewhere` items the caller can see), so a briefing whose delivered coverage is `weak` or
`unknown` because every hit lay outside the project is not read as "the wiki has nothing on this".

### JSON shape additions

`format=json` is unchanged except for additive fields on `items[]`:

- `origin` takes two new values alongside `pin` and `cluster`: `cited` (tier 2) and
  `elsewhere` (an out-of-scope hit, always `included: false`; its `summary` holds the
  heading path of the matching section).
- `cluster` is the page's primary cluster for `cited` and `elsewhere` items so a reader can
  tell whose page it is; it is `null` for pins and members.
- `retrievalCoverage` (top level, beside `coverage`) is the retrieval bundle's coverage *before* the
  cluster-scope partition. The retrieval backend does not ACL-filter, so the ACL gate recounts it over the
  sections the caller can view, exactly as it does `coverage`: it never reflects restricted pages. For a result
  in which every retrieved section was delivered and viewable it equals retrieval's pre-partition counts; where
  the scope moved hits to `elsewhere` (or the budget dropped them) the response reports only the viewable
  delivered sections, and the pre-partition truth lives in the query log (see [Telemetry](#telemetry)).

`sections[]` carry no tier field; they are ordered by tier, members then cited pages.

### Unknown arguments

Both `GET /api/briefing` and the `get_briefing` tool add one `unknown argument: <name>`
warning, in sorted order after the other warnings, for each request parameter they do not
recognise (the name is truncated to 64 characters). The request still runs with defaults;
the warning exists so that `scope=strict` (the parameter is `scope_mode`) is no longer
silently treated as `prefer`. `assemble_bundle` and `/api/bundle` do not warn: the bundle
response has no warnings channel.

### Telemetry

Each assembled briefing is recorded in the `briefing_log` table (migration
`V044`; toggle with `wikantik.briefing.log.enabled`, default `true`) for telemetry — what was requested, how large the result was, whether the
budget bound. Since `V064` the row also carries `cited_count`, `elsewhere_count` and the effective `scope_mode` (`prefer` or `strict`), and
`pointer_count` counts only budget-trimmed pin and cluster items.

A briefing with a `prompt` is also written to the query log (surfaces `api_briefing` and
`mcp_get_briefing`) so Content Intelligence can see agent demand. That row records what
*retrieval* found, read from the briefing *before* the ACL gate so it does not depend on who
asked: the pre-gate `retrievalCoverage.confidence` and a result count of delivered sections plus
`elsewhere` pointers. It deliberately does not record the delivered `coverage`, which the cluster
scope can take to zero for a prompt whose strong hits all lie in other projects; that would read as
a content gap that does not exist. The `briefing_log` row, by contrast, is built from the gated
briefing as served.

Portable client shims that wire the briefing into an agent runtime
(Claude Code, Antigravity) live under [`clients/`](../../clients/).

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
in [ADR-0005](../adr/0005-persisted-citation-edges-and-stale-citation-curation.md).
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
| `wikantik.bundle.bm25.bm25_weight` / `.dense_weight` | `0.5` / `1.5` | Fusion weights for the bundle's BM25 ↔ dense RRF (independent of main search). Deliberately dense-tilted 3:1 — tuned 2026-06-18 as half the page-level BM25 weight, which kept the similarity gain without the boundary@5 dip the reused page weights (bm25=1.0) caused. |
| `wikantik.bundle.bm25.rrf_k` | `20` | RRF constant for the bundle fusion. |
| `wikantik.bundle.coverage.strong_similarity` | `0.55` | `topSimilarity` at/above which coverage is `strong`. |
| `wikantik.bundle.coverage.partial_similarity` | `0.40` | `topSimilarity` at/above which coverage is `partial` (else `weak`). |
| `wikantik.bundle.reranker.enabled` | `false` | LLM listwise reranker. **Default off** — measured to reorder without improving recall (a bad relevance judge under shuffled input). |
| `wikantik.briefing.enabled` | `true` | Kill switch for the briefing service; when `false` it is not wired. |
| `wikantik.briefing.default_budget` / `.max_budget` | `6000` / `24000` | Default and maximum token budget for `/api/briefing` and `get_briefing`. |
| `wikantik.briefing.sections_per_page` | `4` | Most task-relevant sections one page may contribute to a briefing. |
| `wikantik.briefing.candidate_pool` | `36` | Sections requested from the bundle before the in-scope/out-of-scope partition and per-page cap. |
| `wikantik.briefing.scope.cited.enabled` / `.max` | `true` / `12` | Cited tier: pages outside the requested clusters that cluster members link to; kill switch and per-request cap. |
| `wikantik.briefing.elsewhere.max` | `8` | Most out-of-scope "elsewhere" pointers per briefing. |
| `wikantik.briefing.pointers.max` | `30` | Most cluster-member pointers per briefing; cited pointers are bounded by `scope.cited.max`, and pinned pages are never capped. |
| `wikantik.briefing.log.enabled` | `true` | Record briefing requests to `briefing_log`. |
| `wikantik.citations.enabled` | `true` | Gate the `cite://` citation-edge subsystem (unrelated to document ingestion). |

The public-surface rate-limit knobs (`WIKANTIK_RATELIMIT_*`) that guard
`/api/bundle` are documented in
[DockerDeployment.md § Performance / search-backend tuning](DockerDeployment.md#performance--search-backend-tuning-optional)
and [WikantikOperations.md § 1.5](WikantikOperations.md).

## See also

- [ADR-0001](../adr/0001-rag-returns-context-bundle-not-synthesized-answer.md) — why the bundle returns context, never a synthesized answer
- [ADR-0002](../adr/0002-knowledge-graph-is-first-class-knowledge-base.md) — the Knowledge Graph as a first-class knowledge base and retrieval signal
- [ADR-0003](../adr/0003-rag-in-process-module-human-machine-parity.md) — RAG as an in-process module with human/machine parity
- [ADR-0005](../adr/0005-persisted-citation-edges-and-stale-citation-curation.md) — persisted citation edges + stale-citation curation
- [HybridRetrieval.md](../wikantik-pages/HybridRetrieval.md) — the retrieval-quality levers underneath the bundle
- [Frontmatter.md](../user/Frontmatter.md) — the metadata that feeds contextual embeddings and agent hints
