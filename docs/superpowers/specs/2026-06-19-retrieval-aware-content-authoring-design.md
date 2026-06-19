# Retrieval-aware content authoring — skill + verification loop

**Date:** 2026-06-19
**Status:** Design approved, pending implementation plan
**Scope guard:** Build + verify on `localhost:8080`. Migrating the skill to the
`../wiki-content/` project and the bulk live-wiki content update are explicit
**follow-on** work, not part of this spec.

## Goal

Significantly enhance the `wiki-content` skill so it authors and updates page
**frontmatter (and body heading structure)** to improve the chunks that feed the
retrieval stack — and add a server-side **verification loop** so authoring quality
can be checked statically and empirically. Where the same lever also improves SEO,
capture that in the same pass.

## Background — what the last week of retrieval work established

The retrieval program (2026-06-13 → 2026-06-19) produced a clear, measured picture:

- **Page recall is effectively solved** (~0.97): retrieval finds the right *page*
  almost always.
- **Section recall is the binding ceiling** — getting the *answering section* into
  the context bundle. It moved **0.42 → ~0.71 @12** over the week and is still the
  constraint.
- **The single biggest lever was contextual document embeddings** (commit
  `f4d2173a20`, 2026-06-14): before embedding each chunk, the indexer prepends the
  page's frontmatter context. Global section recall@12 **0.60 → 0.735** (+0.13) —
  larger than any model change. The format (`EmbeddingTextBuilder.forDocument`,
  sourced in `SearchWiringHelper:135-143`):

  ```
  Page: {title} | Cluster: {cluster} | Section: {heading > path}
  Summary: {summary}

  {chunk body}
  ```

  The fields embedded are exactly **`title`, `cluster`, `summary`** (frontmatter)
  plus the body's **heading path**. The `EmbeddingTextBuilder` Javadoc states it
  outright: *"our frontmatter is exactly the disambiguation missing from raw
  chunks."*
- **Chunker heading fidelity matters** (same commit): early/first sections were
  losing their `heading_path` and becoming unfindable (7/68 gold sections had no
  matching chunk). The **body's heading structure** directly determines chunk
  attribution and citation anchoring.
- **Default stack:** chunk-level **dense + BM25 hybrid** (RRF), global dense-chunk
  source (no page pre-select). BM25 in the mix means **literal vocabulary coverage**
  in body + headings still counts.
- **New signal (2026-06-19, `bc40a42e27`):** `retrieval_query_log` (V041) captures
  real human/agent queries across all four retrieval surfaces — ground-truth traffic
  about what people actually search for. Write side only; no read surface yet.

**Dead ends — measured and rejected; the skill must NOT chase these:** LLM &
cross-encoder rerankers (ordering, not recall — default OFF), bigger embedding model
(4B is a regression; qwen3-0.6b wins), HyDE, doc2query (null/harmful), KG graph
rerank (zero net lift, shelved), lexical injection for code identifiers (base
already 0.88, shelved).

**Takeaway:** the levers a *content author* controls — `title`, `cluster`,
`summary`, heading structure — are the highest-leverage retrieval levers in the
whole stack. The current `wiki-content` skill frames these as **SEO-only**
(`SKILL.md` lines 117-138) and says nothing about their role in embeddings. That is
the gap this work closes.

## SEO synergy

A `summary` that names the page's real concepts in specific, natural language is
simultaneously a good ~160-char `<meta description>` **and** good embedding
disambiguation context. The goals diverge only under keyword-stuffing (kills SEO) or
marketing fluff (kills retrieval). The unifying rule — *describe the actual value +
key vocabulary, specifically* — serves both. The design surfaces these overlaps
explicitly rather than treating retrieval and SEO as competing concerns.

## Non-goals

- No new embedded frontmatter field (this is level **B**, not C — `summary` keeps
  its dual SEO/retrieval duty; we do not add a separate retrieval-only field that
  would require changing `PageContext`).
- No reranker, HyDE, doc2query, KG rerank, or larger embedder work (all rejected).
- No schema migration (the `retrieval_query_log` table already exists at V041).
- No green-field assumption: the **primary** use case is bulk maintenance of
  existing live content, so static lint at scale + query-log miss-finding are
  first-class.
- Skill migration to `../wiki-content/` and the bulk live update are follow-on.

## Design

### Component 1 — Static lint: `retrieval_readiness` check in `verify_pages`

Module: `wikantik-admin-mcp`. `VerifyPagesTool` already composes `PageCheck`
strategies (`SEO_CHECKS` list → `seoIssues`). Add a parallel `RETRIEVAL_CHECKS` list
→ `retrievalIssues` aggregate, a new `checkRetrievalReadiness(...)` method mirroring
`checkSeoReadiness(...)`, and `retrieval_readiness` to the valid `checks` values.

All findings are **advisory warnings** (like `seo_readiness`) — `verify_pages` is a
read-only analytics tool and never blocks a save.

Rubric (each a unit-tested `PageCheck` strategy in `PageChecks`):

| Check | Rule | Mechanism served | SEO overlap |
|-------|------|------------------|-------------|
| `SummarySpecificityCheck` | `summary` present, ~80-160 chars, not a restatement of the title, names concrete concepts/vocabulary | Prepended to every chunk embedding — page-level disambiguation | ✅ shares `<meta description>` |
| `HeadingQualityCheck` | Body headings self-contained & specific; flags generic ones (`Overview`, `Introduction`, `Details`, `Notes`, `Misc`, `Summary`, `Background`) | Each chunk carries its `heading_path` into the embedding + citation anchor | ➖ retrieval-only |
| `ClusterPresentCheck` | `cluster` set & kebab-case | Prepended to chunk embedding (domain disambiguation) | ✅ JSON-LD `articleSection` |
| `TitleSpecificityCheck` | `title` present, not the bare slug, descriptive | Prepended to chunk embedding | ✅ `<title>` / JSON-LD |

`HeadingQualityCheck` reads the page body (already available to `VerifyPagesTool` for
link checks). Output shape identical to `seoIssues` so existing skill verify-loops
extend with no structural change.

### Component 2 — Query-log read tool: `list_retrieval_queries` (the new code)

Module: `wikantik-admin-mcp`. A read-only MCP tool over `retrieval_query_log`.

- Add a **read method** to the query-log service (currently write-only
  `JdbcQueryLogService` / `QueryLogService`).
- **Filters:** `since` (time window), `actor` (`human`/`agent`/`unknown`),
  `surface`, `max_result_count` (find misses), `min_frequency`.
- **Returns:** deduped query text, frequency, actor/surface breakdown, average +
  last `result_count`, ranked by frequency. **Low/zero-result queries are the
  under-served signal**, served straight from the column.
- **Labor split (decided):** the *tool* returns raw/aggregated queries; the *agent*
  runs interesting ones through `/api/bundle` and judges whether the right section
  surfaces. The "is this a real miss" judgment stays in the skill (portable,
  flexible), not hard-coded server-side. A server-side "queries page X should
  answer" join is deferred — the log records no intended-target mapping.

This is server-side, so behavior is identical under Claude Code and Antigravity.

### Component 3 — Live check: skill workflow, no new server code

Uses existing `/api/bundle` / `assemble_bundle`. Per page: run **author-supplied
expected queries** (new pages, no traffic yet) or **query-log real queries**
(existing pages), confirm the page's section lands in the bundle, iterate frontmatter
/ headings until it does. Documented workflow, not code.

### Component 4 — Skill rewrite (`.claude/skills/wiki-content/SKILL.md`)

- New section **"Retrieval-aware frontmatter"**: the embedding mechanism, the
  rubric, the dual-purpose SEO overlap table.
- New workflow **"Retrieval verification loop"**: `verify_pages`
  `retrieval_readiness` (static) → `/api/bundle` spot-check with expected queries
  (live) → `list_retrieval_queries` maintenance sweep (real misses).
- Rewrite the metadata-conventions section to frame fields as **retrieval + SEO**,
  not SEO-only.
- **Portability constraint baked in:** everything routes through MCP tools / REST
  endpoints both Claude Code and Antigravity can call — no Claude-Code-specific
  mechanisms in the skill prose, so the later migration to `../wiki-content/` is a
  clean copy.

## Data flow

**Authoring / update:** write content → `verify_pages` `retrieval_readiness` (static
lint) → fix frontmatter/headings → `/api/bundle` with expected queries (live check)
→ iterate until the answering section lands.

**Maintenance sweep:** `list_retrieval_queries` (under-served / low-result) →
identify the page that *should* answer → `retrieval_readiness` lint + fix → re-verify
via `/api/bundle`.

## Testing

- **Unit:** each new `PageCheck` strategy (mirror `PageChecksTest`); the query-log
  read method on H2 **and** Postgres (mirror `JdbcQueryLogServiceTest` /
  `JdbcQueryLogServicePostgresTest`); the new tool (`VerifyPagesToolTest` +
  `ListRetrievalQueriesToolTest` patterns).
- **Integration:** wire-level **Cargo IT** for `list_retrieval_queries` (standing
  rule: MCP surface changes ship with a wire-level IT).
- Full IT reactor green before commit.

## Rollout

- No new migration (V041 table already present).
- Build → `bin/deploy-local.sh` → exercise `list_retrieval_queries` and
  `verify_pages` `retrieval_readiness` live against `localhost:8080` with the
  `testbot` admin credentials.
- Doc updates: admin-mcp tool count **25 → 26**; `verify_pages` gains a check;
  CLAUDE.md / README / CHANGELOG.

## References

- `wikantik-main/.../search/embedding/EmbeddingTextBuilder.java` — `forDocument(PageContext, headingPath, body)`
- `wikantik-main/.../search/subsystem/SearchWiringHelper.java:126-147` — frontmatter → `PageContext` resolver
- `wikantik-admin-mcp/.../mcp/tools/VerifyPagesTool.java` — `SEO_CHECKS` strategy pattern
- `wikantik-main/.../knowledge/querylog/JdbcQueryLogService.java`, `wikantik-api/.../querylog/QueryLogService.java` — query log (write side, V041)
- `eval/bundle-corpus/baseline-notes.md` — recall-lever findings
- `docs/wikantik-pages/HybridRetrieval.md`, `docs/superpowers/specs/2026-06-13-rag-as-a-service-and-knowledge-base-design.md`
