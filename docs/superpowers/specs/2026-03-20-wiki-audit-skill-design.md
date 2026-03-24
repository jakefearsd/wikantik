# Wiki Audit Skill Design

**Date:** 2026-03-20
**Status:** Approved
**Scope:** Content quality and SEO readiness auditor for the Wikantik wiki

## Overview

A Claude Code skill that audits the wiki's structural integrity, metadata completeness, and SEO readiness using the Wikantik MCP server. It runs on a weekly schedule, auto-fixes trivial issues directly via MCP, and produces a prioritized recommendation report for follow-up execution.

## Design Decisions

- **Cluster-by-cluster audit with wiki-wide rollup** — The wiki is organized around topic clusters, so the audit follows the same structure. Each cluster is audited independently, then a wiki-wide pass catches cross-cutting concerns. This produces reports that map directly to actionable work.
- **Internal signals only** — The audit uses only data available through the Wikantik MCP server. External performance data (Search Console, analytics, traffic logs) is out of scope and reserved for a future wiki-traffic-review skill.
- **Auto-fix trivials, recommend everything else** — The auditor fixes unambiguous, reversible issues directly. Anything requiring editorial judgment goes into the report.

## Available MCP Tools

The audit skill has access to the full Wikantik MCP tool set. The tools it actively uses:

| Tool | Audit Purpose |
|------|---------------|
| `list_pages` | Enumerate all pages in discovery |
| `query_metadata` | Map pages to clusters, find sub-clusters |
| `list_metadata_values` | Learn metadata conventions in use |
| `verify_pages` | Bulk check existence, links, backlinks, metadata, SEO |
| `preview_structured_data` | Inspect JSON-LD/meta tags on flagged pages |
| `get_orphaned_pages` | Find pages with no incoming links |
| `get_broken_links` | Wiki-wide broken link scan |
| `get_wiki_stats` | Recent changes for staleness detection |
| `read_page` | Inspect body text for targeted fixes only |
| `patch_page` | Auto-fix: add backlinks to hub |
| `update_metadata` | Auto-fix: add missing metadata fields |
| `batch_update_metadata` | Auto-fix: batch metadata corrections |

## Audit Workflow

### Phase 1 — Discovery

- `list_pages` to get all pages
- `query_metadata` with `field="cluster"` to map every page to its cluster
- `list_metadata_values` to learn the full set of clusters and metadata conventions in use
- Identify unclustered pages (standalone articles, the Main page, etc.)
- **Sub-cluster detection:** Cluster values containing `/` (e.g., `retirement-planning/eu-retirement`) are sub-clusters. Group them under their parent cluster for Phase 2 but audit them as distinct units with their own hub validation.
- **Output:** cluster map (including sub-cluster hierarchy) and list of unclustered pages

### Phase 2 — Per-Cluster Audit

For each cluster (and each sub-cluster within it):

**Structural integrity:**
- `verify_pages` on all cluster members checking existence, broken links, backlinks
- Hub must have backlinks from all sub-articles
- Every sub-article must link back to hub in body text
- **Sub-cluster validation:** Sub-cluster hub must link back to parent hub in body text. Parent hub must link to sub-cluster hub.

**Metadata completeness:**
- Every page has: type, tags, summary, related, cluster, status, date, author
- Summary is 50-160 characters and unique across the wiki
- Author must be a descriptive name, not the default "MCP"
- **Note:** The wiki-content skill's Quality Standards currently lists only type, tags, summary, related as required. This audit enforces a stricter standard including cluster, status, date, and author. The wiki-content skill should be updated to match.

**SEO readiness:**
- `verify_pages` with `checks=["seo_readiness"]`
- `preview_structured_data` on any page with warnings
- Check JSON-LD, meta tags, feed eligibility
- **Skip for pages with `status: draft`** — drafts are not yet public and SEO checks are premature

**Staleness:**
- Use `get_wiki_stats` (recent changes) to identify page modification dates
- Flag pages not modified within a configurable window (default: 90 days)
- **Skip for pages with `status: archived`** — archived pages are intentionally static
- **Flag drafts that have been in draft status for more than 30 days** as a warning (stalled work)
- Configuration is expressed as a default in the SKILL.md. The operator can override via the invocation prompt (e.g., "Run the audit with a 60-day staleness window").

**Auto-fix trivials:**
- Apply fixes per the auto-fix boundary rules (see Auto-Fix Boundaries section)

### Phase 3 — Wiki-Wide Rollup

- **Orphaned pages** — `get_orphaned_pages` for pages with no incoming links
- **Cross-cluster linking gaps** — Identify clusters with shared tags but no cross-references. A "cross-reference" means either a body link from a page in one cluster to a page in another cluster, or a `related` metadata entry pointing across clusters. Both are expected per wiki-content conventions; flag clusters that have neither.
- **Duplicate summaries** — Compare summaries across all pages for uniqueness
- **Main page completeness** — Verify every cluster's hub appears on Main with a bullet
- **Global broken links** — `get_broken_links` as a catch-all

## Auto-Fix Boundaries

**Principle:** Auto-fix only when the correct action is unambiguous and the change is safely reversible via the wiki's page history.

### Auto-fix (agent acts directly via MCP):

- Add missing `cluster` field when the page is named with the cluster's CamelCase convention and is already linked from the hub
- Add missing `status` field (default to `active` for published pages)
- Add missing `date` field using the page's last-modified date
- Add missing backlink from sub-article to hub when the hub is known and the sub-article has a See Also section
- Fix broken internal links that are CamelCase typos — **only when exactly one existing page matches the corrected spelling.** If multiple candidates exist, recommend instead of auto-fixing.

### Never auto-fix (always recommend):

- Summary text — too subjective, requires human judgment
- Tags — topic classification is an editorial decision
- Author field — requires knowing the intended attribution
- Adding or removing pages from a cluster
- Cross-cluster links — editorial implications
- Any body content beyond adding a backlink to a known hub
- Anything where two or more "correct" actions exist

## Output Format

### Location and Rotation

- Reports written to `audits/YYYY-MM-DD-wiki-audit.md` at the repository root
- One file per run, accumulating over time
- Maximum 52 reports retained. Before writing a new report, the agent lists files in `audits/` via bash, counts them, and deletes the oldest if the count is at 52.

### Report Structure

**Header:**
- Date, total pages audited, total clusters
- Summary statistics: issues found, auto-fixed, recommendations generated

**Auto-Fix Log:**
- Table of autonomous changes: page name, issue, action taken
- Provides visibility into what the auditor changed without human review

**Per-Cluster Sections (one per cluster):**
- Cluster health summary: page count, broken links, metadata completeness percentage
- Issues categorized by severity:
  - **Critical** — broken links, missing hub backlinks, non-existent pages
  - **Warning** — incomplete metadata, SEO warnings, summary length violations
  - **Suggestion** — staleness, cross-cluster linking opportunities
- Each issue includes: affected page, what's wrong, and a recommended action specific enough for a follow-up agent to execute

**Wiki-Wide Section:**
- Orphaned pages, duplicate summaries, Main page gaps, cross-cluster linking opportunities

**Recommended Task List:**
- Consolidated, prioritized checklist pulling from all sections
- Critical items first, then warnings, then suggestions
- Each task written to map to a wiki-content skill workflow (Extend Cluster, Wiki Maintenance, etc.)

## Invocation Modes

**Scheduled (primary):**
- Kicked off by a cron hook or external scheduler that starts a Claude Code session
- Runs the full three-phase workflow across the entire wiki

**On-demand:**
- Invoked manually, optionally scoped to a specific cluster
- When scoped: run `query_metadata` with `field="cluster"` and `value` set to the target cluster to obtain membership, replacing the full discovery phase. Run Phase 2 on the target cluster, then a lighter Phase 3 checking only cross-references involving that cluster.

## Context Management

The skill must stay disciplined about what it pulls into the agent's context window:

- Discovery phase uses list/query tools returning compact results (page names, metadata fields) — not full page content
- `verify_pages` is the workhorse — checks multiple pages per call with structured results
- `read_page` only called when inspecting body text for a specific fix (e.g., checking if a See Also section exists before patching)
- `preview_structured_data` only called on pages flagged by `verify_pages` with SEO warnings
- Report built incrementally — each cluster section written as its audit completes

## Error Handling

The audit must be resilient to partial failures since it runs as an unattended scheduled job:

- If an MCP tool call fails during a per-cluster audit, log the failure, skip that cluster, and continue with the remaining clusters
- Include a **Failures** section in the report listing any clusters or checks that could not be completed, with the error details
- The audit should always produce a report, even if partial — a report with "cluster X failed" is more useful than no report at all
- Do not retry failed tool calls — if the MCP server is having issues, the next weekly run will catch it

## Skill File Location

`.claude/skills/wiki-audit/SKILL.md`

No helper scripts or external dependencies. The skill definition teaches the agent the complete audit workflow using MCP tools directly.

## Relationship to Other Skills

### wiki-audit to wiki-content pipeline

The audit report's recommended task list is written so that the wiki-content skill can execute it directly. Each recommendation maps to a wiki-content workflow:
- "Create article X in cluster Y" maps to Extend Cluster
- "Fix broken links on page Z" maps to Wiki Maintenance
- "Add cross-cluster links between A and B" maps to Cross-Cluster Linking

A follow-up agent invocation loads the latest audit report, picks up the task list, and uses wiki-content to execute.

### Future skills anticipated but not implemented

- **wiki-content-gap** — Deeper analysis combining internal signals with web research to identify topics the wiki should cover
- **wiki-traffic-review** — Daily/weekly traffic analysis consuming external data (Search Console, analytics, logs), feeding performance signals back into audit prioritization

### What wiki-audit does NOT do

- Create new content or plan new clusters
- Access external data sources
- Make editorial judgments about content quality or accuracy — only structural and metadata quality
