---
name: wiki-content
description: Use when creating, publishing, updating, or maintaining wiki content via the Wikantik MCP server — covers article clusters, single articles, maintenance, and reorganization using native MCP tools
---

## Overview

Wiki content management via native MCP tools. Covers article clusters, single articles, maintenance, and reorganization. All interactions use the Wikantik MCP server tools directly — no bash scripts, curl, or JSON-RPC payloads needed.

## Workflows

### Create Article Cluster

Three phases for new clusters:

#### 1. DISCOVER — Survey existing content
- `get_cluster_map` to see all clusters, metadata conventions, and page organization in one call
- `search_pages` for topic keywords if checking for content overlap
- **Output:** existing content map, metadata conventions to follow

#### 2. PLAN — Design the cluster
- Define hub page + sub-articles, CamelCase page names
- Write Markdown body for each page
- Define tags, summary, date, author for each page
- Assign a `cluster` identifier (kebab-case slug)
- **Critical:** Every sub-article body must link back to the hub (e.g. in a "See Also" section)
- **Output:** page name list, body content, metadata

#### 3. PUBLISH — Single call via `publish_cluster`
```
publish_cluster(
  clusterName: "my-cluster",
  hub: {name: "MyClusterHub", body: "...", metadata: {tags: [...], summary: "...", ...}},
  articles: [{name: "Article1", body: "...", metadata: {...}}, ...],
  author: "claude-code-researcher"
)
```

The server automatically:
- Sets `type` (hub/article), `cluster`, `status` (active), and `related` metadata
- Updates the Main page with a new section listing all pages
- Verifies the result (body links, backlinks, metadata completeness)

**Output:** creation results + verification warnings. Fix any warnings, then document.

#### 4. DOCUMENT — Record what was done
- Append cluster details to `docs/research_history.md`
- Record topic, all pages created, cross-links, lessons learned

### Extend Cluster

Add an article to an existing cluster — single call via `extend_cluster`:

```
extend_cluster(
  clusterName: "my-cluster",
  article: {name: "NewArticle", body: "...", metadata: {tags: [...], summary: "...", ...}},
  author: "claude-code-researcher"
)
```

The server automatically:
- Discovers existing cluster members and hub
- Creates the new article with correct metadata (type, cluster, related, status)
- Patches the hub body to list the new article
- Updates `related` metadata on all siblings
- Updates the Main page
- Verifies the result

For manual control, use the fine-grained tools: `read_page` → `write_page` → `batch_patch_pages` → `batch_update_metadata` → `patch_page` Main → `verify_pages`.

### Single Article

- Use the `create-article` MCP prompt for guided creation
- Or call `write_page` directly for simple pages
- Always include metadata: type, tags, summary, related

### Wiki Maintenance

**Broad health check:** Use the `wiki-audit` skill for periodic or comprehensive audits — it uses compound tools (`get_cluster_map`, `audit_cluster`, `audit_cross_cluster`, `apply_audit_fixes`) that handle all checks in minimal calls.

**Targeted checks after edits:**
- `verify_pages` for specific pages after publish/update
- `get_broken_links` to find broken references
- `get_orphaned_pages` to find disconnected content
- `get_cluster_map` for a quick overview of cluster organization

### Content Reorganization

- `rename_page` with updateLinks=true for renames
- `batch_patch_pages` for restructuring cross-references
- `batch_update_metadata` for cluster reassignment

### Cross-Cluster Linking

When clusters have thematic overlap:
- Add cross-cluster links in hub pages' "See Also" sections using `batch_patch_pages`
- Add cross-cluster entries in `related` metadata using `batch_update_metadata`
- This creates the semantic web — clusters are not silos, they are interconnected knowledge

## Metadata Conventions

Standard frontmatter for cluster articles:

| Field     | Purpose                          | Example                        |
|-----------|----------------------------------|--------------------------------|
| `type`    | Page classification              | `hub` (hub pages), `article` (sub-articles) |
| `tags`    | Topic tags (list)                | `[finance, budgeting]`         |
| `date`    | Publication date (ISO)           | `2026-03-14`                   |
| `related` | Linked CamelCase page names      | `[PersonalFinanceHub, Saving]` |
| `cluster` | Cluster identifier (kebab-case)  | `retirement-planning`          |
| `status`  | Lifecycle state                  | `draft`, `active`, `archived`  |
| `summary` | One-line description             | `Overview of budgeting basics` |

All pages in a cluster must use the same metadata schema for queryability via `query_metadata`.

### SEO and Web Visibility

Frontmatter fields directly drive search engine, social sharing, feed, and news outputs:

| Field     | Web Output |
|-----------|------------|
| `summary` | `<meta description>`, OG/Twitter description, JSON-LD description, Atom `<summary>` |
| `tags`    | `<meta keywords>`, `article:tag` per tag, JSON-LD keywords, Atom `<category>`, News Sitemap `<news:keywords>` |
| `cluster` | JSON-LD `articleSection`, `isPartOf` (non-hub), BreadcrumbList (non-hub), Atom cluster filter |
| `type`    | JSON-LD `@type` — hub=CollectionPage, else Article |
| `date`    | JSON-LD `datePublished` |
| `related` | JSON-LD `hasPart` (hub) or `relatedLink` (non-hub) |

**Summary quality rules:**
- Keep between 50-160 characters — Google truncates at ~155
- Describe the page's value proposition, not just the topic
- Each page needs a unique summary — no duplicates across the wiki

**News Sitemap eligibility:**
- Pages modified within the last 2 days that have frontmatter `tags` appear in the Google News Sitemap
- Pages without tags never appear, regardless of recency

**SEO verification workflow:**
1. `verify_pages` with `checks=["seo_readiness"]` — find issues
2. `preview_structured_data` on pages with warnings — see real impact
3. Fix with `update_metadata` / `batch_update_metadata`
4. Re-verify until `seoIssues` is empty

### Sub-Clusters

Sub-clusters use a `/` separator in the `cluster` field:

```
cluster: retirement-planning              # top-level cluster
cluster: retirement-planning/eu-retirement  # sub-cluster
```

**Rules:**
- Sub-cluster has its own hub page (`type: hub`) linking to its articles and back to parent hub
- Parent hub links to sub-cluster hub
- `query_metadata` with the full `parent/sub` identifier returns only sub-cluster pages

## Quality Standards

- All pages must have: type, tags, summary, related
- Hub pages link to all sub-articles in body text; sub-articles link back to hub in body text
- `related` metadata and body links serve different purposes: `related` enables metadata queries, body links create the backlink graph. **Both are required.**
- Every cluster page uses the same cluster identifier
- Author set to descriptive name, not default "MCP"
- `verify_pages` after every publish/extend — iterate until:
  - `allExist` = true
  - `totalBrokenLinks` = 0
  - `pagesWithNoBacklinks` = [] (especially check the hub)
  - `metadataIssues` = []

## Available MCP Tools

### Compound content tools (preferred)
| Tool | Purpose | When to use |
|------|---------|-------------|
| `publish_cluster` | Create hub + articles + metadata + Main update + verify | New clusters (replaces 5+ calls) |
| `extend_cluster` | Add article + patch hub + update metadata + Main + verify | Adding to existing clusters (replaces 7 calls) |

### Fine-grained content tools (for custom operations)
| Tool | Purpose | When to use |
|------|---------|-------------|
| `write_page` | Create or fully replace a page | Single pages, full rewrites |
| `patch_page` | Surgical edits (insert, append, replace sections) | Adding links, extending content |
| `batch_write_pages` | Create multiple pages in one call | When publish_cluster doesn't fit |
| `batch_patch_pages` | Patch multiple pages in one call | Cross-reference updates |
| `update_metadata` | Modify frontmatter without touching body | Single-page metadata changes |
| `batch_update_metadata` | Modify frontmatter on multiple pages | Cross-references across cluster |

### Discovery tools
| Tool | Purpose |
|------|---------|
| `read_page` | Read page content and metadata |
| `search_pages` | Full-text search |
| `list_pages` | List page names with optional prefix filter |
| `query_metadata` | Find pages by frontmatter fields |
| `list_metadata_values` | Discover field names and values in use |

### Verification tools
| Tool | Purpose |
|------|---------|
| `verify_pages` | Compound check: existence, links, backlinks, metadata, SEO readiness for multiple pages |
| `preview_structured_data` | Preview meta tags, JSON-LD, feed entries, News Sitemap eligibility for a page |
| `get_wiki_stats` | Total pages, broken links, orphans, recent changes |
| `get_broken_links` | All broken links across the wiki |
| `get_orphaned_pages` | Pages with no incoming links |

### Audit tools (for broad maintenance — see wiki-audit skill)
| Tool | Purpose |
|------|---------|
| `get_cluster_map` | Full wiki organization: clusters, hubs, pages, metadata conventions |
| `audit_cluster` | Per-cluster structural, metadata, SEO, and staleness checks |
| `audit_cross_cluster` | Wiki-wide orphans, cross-cluster gaps, duplicate summaries |
| `apply_audit_fixes` | Batch-apply trivial metadata/link fixes |

### Link graph tools
| Tool | Purpose |
|------|---------|
| `get_outbound_links` | Pages linked from a given page |
| `get_backlinks` | Pages linking to a given page |
| `scan_markdown_links` | Classify links as local/external/anchor |

## Quick Reference

| Phase    | MCP Tools                                          | Calls |
|----------|----------------------------------------------------|----|
| DISCOVER | `get_cluster_map` (or `search_pages` for topic overlap) | 1 |
| PLAN     | (design work, no MCP calls)                        | 0 |
| PUBLISH  | `publish_cluster` (creates all pages + metadata + Main + verify) | 1 |
| DOCUMENT | (append to research_history.md)                    | 0 |
| EXTEND   | `extend_cluster` (creates article + patches hub + metadata + Main + verify) | 1 |
| AUDIT    | `wiki-audit` skill: `get_cluster_map` → `audit_cluster` × N → `audit_cross_cluster` → `apply_audit_fixes` | N+3 |
