---
name: wiki-content
description: Use when creating, publishing, updating, or maintaining wiki content via the Wikantik MCP server — covers single articles, clusters, retrieval/SEO-aware frontmatter, maintenance, and reorganization using the live MCP tools
---

## Overview

Wiki content management via native MCP tools, tuned for **both search retrieval and SEO**. All
interactions route through MCP server tools directly.

> **Portability (MCP-only):** every action here uses MCP server tools — no REST/`/api/*`, curl, or
> bash — so this skill runs identically under Claude Code and Antigravity. The live bundle check is the
> `assemble_bundle` MCP tool, never `GET /api/bundle`. Do not add client-specific mechanisms.

> **Tool surface reconciled 2026-06-20 against the live servers** (admin-mcp = 26 tools, knowledge-mcp
> = 20 tools). The old compound tools (`publish_cluster`, `extend_cluster`, `get_cluster_map`,
> `audit_cluster`, `update_metadata`, `patch_page`, `write_page`, `batch_*`, `query_metadata`,
> `scan_markdown_links`) **no longer exist** — do not call them. The real tools are listed at the
> bottom. Authoring is on the **admin-mcp** server; discovery + retrieval checks are on the
> **knowledge-mcp** server.

## ⚠️ Two traps that silently corrupt pages or mislead you

1. **`update_page` rebuilds the page from its `content` argument.** Its `content` must be the
   **full raw text including the YAML frontmatter** — exactly what `read_page` returns — NOT just the
   body. Passing a body-only `content` **wipes every frontmatter field** (title, type, cluster, tags…),
   leaving only what you pass in `metadata`. Always: `read_page` → edit the full text → `update_page`
   with that full text + the `expectedContentHash`. Use the optional `metadata` object only for
   targeted field overrides *on top of* the frontmatter already in `content`.
2. **Passing `verify_pages` `retrieval_readiness` with zero warnings does NOT mean the page is
   retrievable.** The static lint checks frontmatter *form*; it cannot tell whether the page actually
   surfaces for real queries. A clean lint is necessary, not sufficient — the **live `assemble_bundle`
   check is the real gate** (see Retrieval Verification Loop).

## Core authoring workflows

### Create a page (or a set of pages)

`write_pages` batch-creates new pages (fails any that already exist — use `update_page` for those).
It validates frontmatter inline and returns per-page `frontmatterWarnings`.

```
write_pages(pages: [
  { pageName: "MyArticle",
    content: "# My Article\n\n## Section\n\nbody…",          # body only on create
    metadata: { title: "My Article", type: "article", cluster: "my-cluster",
                tags: ["a","b"], summary: "<50–160 char, vocabulary-rich>", date: "2026-06-20" } }
])
```
`write_pages` does NOT auto-derive `type`/`cluster`/`related` (the retired `publish_cluster` did) — set
every field explicitly. For a cluster, create the hub (`type: hub`) and articles (`type: article`,
same `cluster`) and write the cross-links into each body yourself (hub links to all; each article
links back to the hub in a "See Also" section).

### Update an existing page

```
read_page(slug)                    # → { content (full raw, with frontmatter), contentHash, version }
# edit the full raw text (frontmatter and/or body)
update_page(slug, content=<FULL raw text incl. frontmatter>, expectedContentHash=<hash>,
            metadata={ summary: "…" })   # metadata optional, merges onto content's frontmatter
```
Optimistic locking: a stale `expectedContentHash` returns `{updated:false, error:"hash mismatch"}` —
re-`read_page` and retry. See trap #1: never pass a body-only `content`.

### Rename / reorganize

`rename_page(oldName, newName, updateLinks=true, confirm=true)` moves content and rewrites referrers.
`mark_page_verified(slugs, verifier, confidence?)` stamps `verified_at`/`verified_by`.
`delete_pages(slugs, confirm=true)` removes pages.

### Discovery (knowledge-mcp)

`list_pages` / `list_pages_by_filter` (prefix/metadata filters), `list_clusters`, `list_tags`,
`list_metadata_values` (field names + values in use), `search_knowledge` (hybrid search),
`read_pages` (batched reads). Use these to survey existing content and avoid duplication before authoring.

## Retrieval-Aware Frontmatter (read before writing summaries/headings)

Four author-controlled levers are **prepended verbatim into every chunk's embedding** before it enters
the dense index (`EmbeddingTextBuilder.forDocument`):

​```
Page: {title} | Cluster: {cluster} | Section: {heading > path}
Summary: {summary}

{chunk body}
​```

This contextual-embedding lever lifted section recall@12 from ~0.60 to ~0.74 — the single biggest
retrieval gain in the stack. So `title`, `cluster`, `summary`, and the body's **heading structure** are
the primary retrieval levers, not just SEO metadata. Because retrieval is **dense + BM25 hybrid**,
literal vocabulary in the summary and headings also counts.

Because `summary` is prepended to **every** chunk on the page, enriching it is the highest-leverage,
lowest-risk edit — it lifts the whole page. Headings only affect their own section's `Section:` prefix,
so the gold section's heading is the lever for *within-page* (section) ranking.

**Write the discriminating vocabulary.** In a crowded topic cluster (e.g. canary vs blue-green vs
dark-launch deployment pages), a relevant page is out-competed by siblings unless its summary/title/
headings carry the terms that distinguish *this* page and match how people phrase the query. Name the
concrete concepts and likely query words explicitly.

**The dual-purpose overlap (retrieval + SEO in one field):**

| Field | Retrieval role (embedded) | SEO role | Authoring rule |
|-------|---------------------------|----------|----------------|
| `summary` | Page-level disambiguation on every chunk | `<meta description>` | Describe the value + name key concepts/likely query vocabulary, specifically. 50–160 chars. Not a title restatement, not fluff. |
| `cluster` | Domain prefix on every chunk | JSON-LD `articleSection` | Always set; kebab-case (`hybrid-retrieval`, sub-clusters `parent/child`). |
| `title` | Topic signal on every chunk | `<title>`, JSON-LD | Natural-language, specific — never just the slug. |
| headings | Each chunk's `Section:` path | (page structure) | Self-contained and specific. **Avoid vague structural headings** — `Overview`, `Introduction`, `Walkthrough`, `Steps`, `The Stages`, `The Basics`, `Usage`, `Getting Started`, `How It Works`, `Details`, `Notes`, `Background`, `Summary` — they give the chunk no topical context. Put the section's actual subject (and likely query words) in the heading. |

**Do NOT chase rejected levers** (measured dead ends): rerankers, bigger embedding models, HyDE,
doc2query, KG graph rerank, lexical injection. The frontmatter levers above are what move recall.

## Retrieval Verification Loop

Run when authoring or updating a page. **The live check (step 2) is the real gate — step 1 alone is not.**

1. **Static lint** — `verify_pages(slugs, checks=["retrieval_readiness","seo_readiness"])`. Fix every
   entry in `retrievalIssues` (thin/title-restating summaries, vague/missing headings, missing/non-kebab
   cluster, slug-echo titles) and `seoIssues`. Cheap pre-filter; both faces in one call.
2. **Live check** — `assemble_bundle(query="<a real question this page should answer>")` on the
   **knowledge-mcp** server. Confirm the page's answering **section** appears in the returned `sections`
   (each has `slug`, `canonicalId`, `headingPath`, `score`). If it's missing or low:
   - Enrich the `summary` with the query's discriminating vocabulary (lifts the whole page), and
   - make the answering section's **heading** carry the query's terms (lifts that section vs siblings),
   then re-index and re-check. For a brand-new page with no traffic, invent 3–5 expected queries.
3. **Maintenance sweep** — `list_retrieval_queries(max_avg_results=1)` (admin-mcp) lists **real**
   queries the corpus answers poorly. For each, find the page that *should* answer it, `assemble_bundle`
   to confirm the miss, then apply steps 1–2. (On a fresh/local instance with no traffic this returns
   nothing — it pays off against production logs.)

> **Re-index note:** changing frontmatter re-embeds the page asynchronously. With the in-memory dense
> backend the bundle won't reflect the change until the index reloads (a restart); `pgvector`/
> `lucene-hnsw` backends read from the DB and update without a restart.

## SEO and Web Visibility

Frontmatter drives search-engine, social, feed, and news outputs:

| Field | Web output |
|-------|------------|
| `summary` | `<meta description>`, OG/Twitter description, JSON-LD description, Atom `<summary>` |
| `tags` | `<meta keywords>`, `article:tag`, JSON-LD keywords, Atom `<category>`, News Sitemap `<news:keywords>` |
| `cluster` | JSON-LD `articleSection`, `isPartOf`/BreadcrumbList (non-hub) |
| `type` | JSON-LD `@type` — hub=CollectionPage, runbook=HowTo, design=TechArticle, else Article |
| `date` | JSON-LD `datePublished` |
| `related` | JSON-LD `hasPart` (hub) / `relatedLink` (non-hub) |

- `summary`: 50–160 chars, unique per page, value-describing (and — per above — query-vocabulary-rich).
- News Sitemap: pages modified within 2 days **that have `tags`** appear; no tags → never.
- SEO workflow: `verify_pages(checks=["seo_readiness"])` → `preview_structured_data(slug)` to see real
  meta/JSON-LD/feed output → fix via `update_page` → re-verify until `seoIssues` is empty. Optionally
  `ping_search_engines` after a batch to nudge IndexNow.

## Metadata conventions

| Field | Purpose | Example |
|-------|---------|---------|
| `title` | Natural-language page title (embedded + `<title>`) | `Canary Deployments` |
| `type` | `hub` \| `article` \| `runbook` \| `design` | `article` |
| `cluster` | Cluster slug, kebab-case (sub: `parent/child`) | `devops-sre` |
| `tags` | Topic tags (list) | `[devops, deployment, canary]` |
| `summary` | 50–160 char, vocabulary-rich description | `Canary releases: traffic splitting, automated analysis, rollback…` |
| `date` | Publication date (ISO) | `2026-06-20` |
| `related` | Related CamelCase page names (list) | `[BlueGreenDeployments, FeatureFlags]` |
| `status` | `draft` \| `active` \| `archived` | `active` |

## Quality standards

- Every content page: `title`, `type`, `cluster`, `tags`, `summary`. Hub/articles cross-link in body
  **and** list each other in `related` (body links build the backlink graph; `related` enables metadata
  queries — both needed).
- After authoring, run `verify_pages` and iterate until `allExist=true`, `totalBrokenLinks=0`,
  `pagesWithNoBacklinks=[]`, `metadataIssues=[]`, `retrievalIssues=[]`, `seoIssues=[]`.
- Then run the live `assemble_bundle` check (the static lint passing is not proof of retrievability).

## Available MCP tools (live, reconciled 2026-06-20)

### Authoring — admin-mcp
| Tool | Purpose |
|------|---------|
| `write_pages` | Batch-create new pages (fails existing); inline frontmatter validation |
| `update_page` | Edit an existing page (full-content + optimistic `expectedContentHash`; `metadata` merges) |
| `rename_page` | Rename + optionally rewrite referrers (`confirm=true`) |
| `mark_page_verified` | Stamp `verified_at`/`verified_by` |
| `delete_pages` | Delete pages (`confirm=true`) |

### Inspect / verify — admin-mcp
| Tool | Purpose |
|------|---------|
| `read_page` | Full raw text + `contentHash` + version (read before every `update_page`) |
| `verify_pages` | Existence, links, backlinks, metadata, **`seo_readiness`**, **`retrieval_readiness`** |
| `preview_structured_data` | Real meta tags / JSON-LD / feed / News-Sitemap output for a page |
| `list_retrieval_queries` | Real query-log traffic (deduped, ranked); `max_avg_results` → under-served queries |
| `get_backlinks` / `get_outbound_links` | Link graph for a page |
| `get_broken_links` / `get_orphaned_pages` | Wiki-wide link/orphan issues |
| `get_wiki_stats` / `get_page_history` / `diff_page` | Stats, history, version diff |
| `ping_search_engines` | IndexNow nudge after a batch |

### Discovery + retrieval check — knowledge-mcp
| Tool | Purpose |
|------|---------|
| `assemble_bundle` | **The live retrieval check** — ranked, cited sections for a query (`{query}`) |
| `list_pages` / `list_pages_by_filter` | Enumerate pages (prefix / metadata filters) |
| `list_clusters` / `list_tags` / `list_metadata_values` | Survey clusters, tags, field values in use |
| `search_knowledge` | Hybrid search over the corpus |
| `read_pages` / `get_page` / `get_page_for_agent` | Batched / projected page reads |

### KG curation — admin-mcp
`propose_knowledge`, `curate_edges`, `curate_nodes`, `list_proposals`, `inspect_proposals`,
`review_proposals`, `query_nodes`, `search_knowledge`, `list_orphaned_kg_nodes` (only on KG-included pages).

## Quick reference

| Phase | Tools (server) |
|-------|----------------|
| DISCOVER | `list_pages`/`search_knowledge`/`list_clusters` (knowledge) |
| CREATE | `write_pages` (admin) → `verify_pages` (admin) |
| UPDATE | `read_page` → edit full text → `update_page` (admin) |
| LINT | `verify_pages` `retrieval_readiness`+`seo_readiness` (admin) |
| LIVE CHECK | `assemble_bundle` (knowledge) — the real gate |
| SWEEP | `list_retrieval_queries max_avg_results=1` (admin) → `assemble_bundle` |
| SEO | `verify_pages seo_readiness` → `preview_structured_data` → `update_page` |
