# MCP Audit Tools Design

**Date:** 2026-03-20
**Status:** Draft
**Scope:** New MCP server tools to support autonomous wiki auditing by AI agents
**Related:** `2026-03-20-wiki-audit-skill-design.md` (the audit skill that consumes these tools)

## Problem

The wiki-audit skill currently orchestrates 30+ individual MCP tool calls to perform a full audit. Each call is a potential approval gate in Claude Code, generates dynamic text in the agent's context, and burns tokens on mechanical work (iterating pages, comparing fields, doing date math). The first validation run required constant manual intervention — unacceptable for a skill designed to run unattended on a weekly schedule.

## Design Principle

Move mechanical, deterministic work to the server. Leave judgment, interpretation, and formatting to the agent. The test: **"Would a for-loop do this better than an LLM?"** If yes, it belongs on the server.

With four new compound tools, the agent's audit workflow drops from 30+ calls to roughly N+4 (where N is the number of clusters), and the agent's only real job becomes formatting structured results into a Markdown report.

## New MCP Tools

### 1. `get_cluster_map`

**Purpose:** Replace the 3+ discovery calls (list_pages, query_metadata, list_metadata_values) with a single call that returns a complete picture of how the wiki is organized.

**Input:**
- `cluster` (optional) — scope to a single cluster. If omitted, returns the full wiki map.

**Output:** A structured object containing:
- `totalPages` — total page count in the wiki
- `clusters` — list of clusters, each with:
  - `name` — cluster identifier (kebab-case)
  - `hub` — the hub page name (page with type=hub), or null if missing
  - `pages` — list of page names in the cluster
  - `pageCount` — number of pages
  - `subClusters` — nested list of sub-clusters (same structure), detected via `/` separator in cluster values
- `unclusteredPages` — list of page names not assigned to any cluster
- `metadataConventions` — summary of field names and values in use across the wiki (output of list_metadata_values)
- `pageMetadata` — map of page name to metadata summary (type, tags, summary, status, date, author, cluster, related) for every page — just the frontmatter fields, not body content

**Work contained:** Server internally calls the equivalent of `list_pages`, `query_metadata(field="cluster")`, and `list_metadata_values`. Assembles the cluster hierarchy, detects sub-clusters via `/` separator, identifies hub pages (type=hub) for each cluster, and collects metadata summaries for all pages.

**Testing:**
- Create a test wiki with 3 clusters (one with a sub-cluster) and 2 unclustered pages
- Verify the map structure is correct: clusters grouped, sub-cluster nested under parent, unclustered pages listed separately
- Test edge cases: cluster with no hub page, empty cluster (cluster identifier exists in metadata but no pages match), page with cluster field but cluster value not matching any other page
- Test scoped mode: pass a cluster name and verify only that cluster's data is returned
- Verify pageMetadata contains all 8 fields for every page

---

### 2. `audit_cluster`

**Purpose:** Run all per-cluster audit checks (structural integrity, metadata completeness, SEO readiness, staleness) in a single call. This replaces the agent calling `verify_pages` multiple times, iterating metadata fields, computing staleness dates, and classifying auto-fixable issues.

**Input:**
- `cluster` (required) — cluster name to audit
- `stalenessWindow` (optional, default 90) — days before a page is flagged as stale
- `stalledDraftWindow` (optional, default 30) — days before a draft is flagged as stalled
- `allSummaries` (optional) — list of {page, summary} from other clusters, for cross-wiki duplicate summary detection. If provided, this cluster's summaries are checked against them.

**Output:** A structured result containing:
- `cluster` — the cluster name audited
- `pageCount` — number of pages in the cluster
- `structural` — list of structural issues:
  - Missing hub backlinks (which sub-articles don't link back to hub in body text)
  - Missing hub-to-sub links (hub doesn't link to which sub-articles in body text)
  - Sub-cluster hub not linking to parent hub (or vice versa)
  - Broken internal links within the cluster
  - Non-existent pages referenced by cluster members
- `metadata` — list of metadata issues, per page:
  - Which of the 8 required fields (type, tags, summary, related, cluster, status, date, author) are missing or empty
  - Summary length violations (under 50 or over 160 characters)
  - Author set to default "MCP"
  - Cluster value mismatch (doesn't match the cluster's identifier)
- `seo` — list of SEO issues, per page:
  - SEO warnings from verify_pages(seo_readiness)
  - Structured data issues from preview_structured_data (only checked on pages with warnings)
  - JSON-LD @type mismatches (hub should be CollectionPage, article should be Article)
  - Pages with status=draft are excluded from SEO checks
- `staleness` — list of stale pages:
  - Pages not modified within the staleness window (excluding status=archived)
  - Drafts older than the stalled draft window
  - Each entry includes: page name, last modified date, days since modification
- `autoFixable` — list of issues that meet auto-fix criteria, each with:
  - `page` — affected page name
  - `action` — one of: `set_metadata`, `add_hub_backlink`, `fix_typo_link`
  - `field` — metadata field name (for set_metadata)
  - `currentValue` — current value (may be null/empty)
  - `proposedValue` — what the fix would set it to
  - `reason` — why this qualifies for auto-fix
- `summaries` — list of {page, summary} for all pages in this cluster (for passing to subsequent audit_cluster calls or audit_cross_cluster for duplicate detection)
- `summary` — aggregate counts:
  - `critical` — count of critical issues (broken links, missing backlinks, non-existent pages)
  - `warning` — count of warnings (metadata gaps, SEO issues, summary violations)
  - `suggestion` — count of suggestions (staleness, stalled drafts)
  - `autoFixable` — count of auto-fixable issues

**Work contained:** Server internally runs:
1. `verify_pages` on all cluster members (structural + metadata checks)
2. `verify_pages` with `checks=["seo_readiness"]` on non-draft members
3. `preview_structured_data` on any page with SEO warnings
4. Reads body text of hub and sub-articles to check for bidirectional body links (hub↔sub, sub-cluster hub↔parent hub)
5. Computes staleness from page modification dates against the staleness window
6. Classifies each issue as auto-fixable or recommend-only using the boundary rules:
   - Auto-fixable: missing cluster/status/date fields (when inferrable), missing hub backlink (when See Also section exists), CamelCase typo links (when exactly one correction candidate exists)
   - Not auto-fixable: summary text, tags, author, cluster membership changes, cross-cluster links, body content beyond backlinks, anything ambiguous

**Testing:**
- Create a test cluster with deliberate issues:
  - Sub-article missing hub backlink in body text
  - Page with no summary field
  - Page with summary of 42 characters (under minimum)
  - Page with author set to "MCP"
  - Page not modified in 120 days
  - Draft page older than 45 days
  - Broken internal link that is a CamelCase typo with exactly one correction candidate
  - Broken internal link with two possible corrections (should NOT be auto-fixable)
  - Page with status=draft (should be excluded from SEO checks)
  - Page with status=archived and old modification date (should NOT be flagged as stale)
- Verify each issue appears in the correct category (structural, metadata, seo, staleness)
- Verify auto-fixable classification is correct for each issue
- Verify summary counts match the detail lists
- Verify the summaries list is populated for duplicate checking

---

### 3. `audit_cross_cluster`

**Purpose:** Run wiki-wide checks that span across clusters: orphaned pages, cross-cluster linking gaps, duplicate summaries, Main page completeness, and global broken links. This replaces the agent calling multiple tools and doing pairwise comparison logic.

**Input:**
- `clusters` (optional) — list of cluster names to scope the check. If omitted, checks the entire wiki.
- `allSummaries` (optional) — list of {page, summary} collected from audit_cluster calls. If provided, used for duplicate detection instead of re-fetching.
- `perClusterBrokenLinks` (optional) — list of broken links already reported by audit_cluster calls. If provided, these are excluded from the global broken links results to avoid duplicates.

**Output:** A structured result containing:
- `orphanedPages` — list of pages with no incoming links (excluding Main page)
- `crossClusterGaps` — list of cluster pairs that share tags but have no cross-references, each with:
  - `clusterA` — first cluster name
  - `clusterB` — second cluster name
  - `sharedTags` — tags they have in common
  - `recommendation` — suggested action (e.g., "Add cross-cluster links in hub See Also sections")
- `duplicateSummaries` — list of page pairs sharing identical or near-identical summaries, each with:
  - `pageA` — first page name
  - `pageB` — second page name
  - `summary` — the shared summary text
- `mainPageGaps` — list of cluster hub pages not listed on the Main page
- `globalBrokenLinks` — broken links not already reported in per-cluster audits, each with:
  - `sourcePage` — page containing the broken link
  - `targetPage` — the broken link target
  - `suggestion` — closest matching page name, if any

**Work contained:** Server internally:
1. Calls `get_orphaned_pages`, filters out Main
2. For cross-cluster gaps: collects tags per cluster, finds pairs with shared tags, checks whether any page in cluster A has a body link to or `related` metadata entry pointing to any page in cluster B
3. Compares all summary strings for duplicates (exact match and near-identical using string similarity)
4. Reads Main page content, parses it for CamelCase page links, checks whether every cluster hub appears
5. Calls `get_broken_links`, filters out any already reported in perClusterBrokenLinks

**Testing:**
- Create two clusters that share a tag (e.g., both have "finance") but have no links between them
- Create a cluster whose hub page is not mentioned on Main
- Create two pages with identical summaries
- Create an orphaned page (no incoming links from anywhere)
- Create a broken link on an unclustered page (should appear in globalBrokenLinks but not in per-cluster results)
- Test scoped mode: pass a single cluster name and verify only cross-references involving that cluster are checked
- Test deduplication: pass perClusterBrokenLinks and verify those links don't appear again in globalBrokenLinks

---

### 4. `apply_audit_fixes`

**Purpose:** Apply trivial, unambiguous fixes identified by `audit_cluster`. This replaces the agent making N individual `update_metadata` and `patch_page` calls.

**Input:**
- `fixes` (required) — list of fix actions, each containing:
  - `page` — page name
  - `action` — one of: `set_metadata`, `add_hub_backlink`, `fix_typo_link`
  - For `set_metadata`: `field` and `value`
  - For `add_hub_backlink`: `hubPage` (the hub to link to)
  - For `fix_typo_link`: `brokenLink` and `correctedLink`

**Output:** A list of results, each containing:
- `page` — page name
- `action` — the action that was attempted
- `success` — boolean
- `detail` — what was changed (if success) or why it failed
- `previousValue` — the value before the fix (for metadata changes), enabling the audit log to show what changed

**Work contained:** Server applies each fix individually using the appropriate internal mechanism:
- `set_metadata` → update_metadata equivalent
- `add_hub_backlink` → reads the page body, finds the See Also section, appends a link to the hub page, writes the updated body via patch_page equivalent
- `fix_typo_link` → reads the page body, replaces the broken link text with the corrected text, writes via patch_page equivalent

Each fix is applied independently so partial failures don't block the rest. The previousValue is captured before the fix is applied.

**Testing:**
- Create pages with known fixable issues:
  - Page missing `status` field → fix with set_metadata(status, "active")
  - Page missing `date` field → fix with set_metadata(date, last-modified date)
  - Sub-article with a See Also section but no hub link → fix with add_hub_backlink
  - Page with a CamelCase typo link → fix with fix_typo_link
- Verify each fix was applied correctly by re-reading the pages
- Test partial failure: include a fix for a non-existent page, verify the other fixes still succeed and the failed one reports success=false with a meaningful error detail
- Test idempotency: apply the same fixes twice, verify the second run either succeeds harmlessly or reports "already fixed"
- Verify previousValue is populated correctly for metadata changes

---

## Agent Workflow After Implementation

With these four tools, the agent's full audit becomes:

```
1. get_cluster_map()                          → 1 call
2. audit_cluster(cluster) × N clusters        → N calls
3. audit_cross_cluster(allSummaries, perClusterBrokenLinks) → 1 call
4. apply_audit_fixes(collected autoFixable)    → 1 call
5. Write report to audits/YYYY-MM-DD.md       → 1 file write
6. Rotate old reports if > 52                  → 1 bash command
7. Commit                                      → 1 bash command
```

Total: N + 6 operations, where N is the number of clusters. For a wiki with 13 clusters, that's 19 operations instead of 30+.

The agent's remaining job is:
- Interpreting the structured results
- Formatting them into the human-readable Markdown report
- Deciding whether to apply fixes (or just report them)
- Writing and committing the report file

## Migration Path

The existing fine-grained tools (`verify_pages`, `query_metadata`, `list_pages`, `get_orphaned_pages`, `get_broken_links`, `read_page`, `patch_page`, `update_metadata`, etc.) remain available. The new audit tools are additive — they don't replace or remove anything. The wiki-content skill continues to use the fine-grained tools for content creation and maintenance. The wiki-audit skill switches to the new compound tools for auditing.

## Implementation Location

These tools belong in the `wikantik-mcp` module of the jspwiki repository, alongside the existing MCP tool implementations. Each tool is a Java class following the existing tool patterns (e.g., `VerifyPagesTool`, `GetBrokenLinksTool`).
