---
name: wiki-audit
description: Use when auditing wiki health, running maintenance checks, or generating audit reports — periodic or on-demand structural integrity, metadata completeness, SEO readiness, and staleness checks across the Wikantik wiki
---

## Overview

Audit the wiki's structural integrity, metadata completeness, and SEO readiness using four compound MCP tools. Auto-fix trivial issues, produce a prioritized Markdown report for follow-up via the wiki-content skill.

## Pipeline

```
get_cluster_map  -->  audit_cluster x N  -->  audit_cross_cluster  -->  apply_audit_fixes  -->  write report
     (1 call)          (N calls)                  (1 call)                 (1 call)             (file write)
```

### Step 1 — Map the wiki

```
clusterMap = get_cluster_map()
```

Gives you: `clusters` (with hubs, pages, sub-clusters), `unclusteredPages`, `metadataConventions`, `pageMetadata`.

For **scoped mode** (single cluster): `get_cluster_map(cluster="the-cluster")`.

### Step 2 — Audit each cluster

Iterate clusters. Accumulate summaries and broken links across iterations.

```
allSummaries = []
allBrokenLinks = []
clusterResults = {}

for each cluster in clusterMap.clusters:
    result = audit_cluster(
        cluster = cluster.name,
        allSummaries = allSummaries
    )
    clusterResults[cluster.name] = result
    allSummaries += result.summaries
    allBrokenLinks += [s.detail for s in result.structural if s.issue == "broken_link"]
```

Each result contains: `structural`, `metadata`, `seo`, `staleness`, `autoFixable`, `summaries`, `summary` (counts).

If `audit_cluster` fails for a cluster, log the error and continue — never abort the entire audit.

### Step 3 — Wiki-wide rollup

```
crossResult = audit_cross_cluster(
    allSummaries = allSummaries,
    perClusterBrokenLinks = allBrokenLinks
)
```

Returns: `orphanedPages`, `crossClusterGaps`, `duplicateSummaries`, `mainPageGaps`, `globalBrokenLinks`.

### Step 4 — Apply auto-fixes

Collect all `autoFixable` items from every cluster result. Review against the policy below, then apply in a single call:

```
fixes = [collect autoFixable items that pass review]
fixResults = apply_audit_fixes(fixes=fixes, author="wiki-audit", changeNote="Weekly audit auto-fix")
```

### Step 5 — Write report

Write to `audits/YYYY-MM-DD-wiki-audit.md`. Rotate: if >52 files in `audits/`, delete the oldest.

## Auto-Fix Policy

**Always apply:**
- `set_metadata` for missing `status` (default "active")
- `set_metadata` for missing `date` (uses last-modified date)
- `set_metadata` for missing `cluster` (page already linked from hub)
- `add_hub_backlink` (adds link to known hub in existing See Also section)
- `fix_typo_link` (tool only proposes this when exactly 1 candidate exists)

**Never auto-fix:**
- Summary text, tags, author, related — require editorial judgment
- Adding/removing pages from clusters
- Cross-cluster links
- Body content beyond hub backlinks
- Anything ambiguous (tool already filters these out)

## Report Format

```markdown
# Wiki Audit — YYYY-MM-DD

**Pages:** {totalPages} | **Clusters:** {clusterCount} | **Issues:** {totalIssues} | **Auto-fixed:** {fixCount}

## Auto-Fix Log

| Page | Action | Detail | Previous |
|------|--------|--------|----------|
(one row per applied fix from apply_audit_fixes results)

## Cluster: {cluster name}

**Pages:** {pageCount} | Critical: {critical} | Warning: {warning} | Suggestion: {suggestion}

### Critical
(structural issues: missing backlinks, broken links, non-existent pages)

### Warning
(metadata gaps, SEO warnings, summary length violations, author=MCP)

### Suggestion
(stale pages, stalled drafts)

(repeat for each cluster)

## Wiki-Wide

### Orphaned Pages
(pages with no incoming links)

### Cross-Cluster Gaps
(cluster pairs sharing tags but no cross-references)

### Duplicate Summaries
(page pairs with identical summaries)

### Main Page Gaps
(cluster hubs not linked from Main)

### Global Broken Links
(broken links not already reported per-cluster)

## Recommended Tasks

(consolidated, prioritized checklist — critical first, then warnings, then suggestions)
(each task maps to a wiki-content workflow: Extend Cluster, Wiki Maintenance, Cross-Cluster Linking)
```

## Scoped Mode

When auditing a single cluster:
1. `get_cluster_map(cluster="X")` — scoped discovery
2. `audit_cluster(cluster="X")` — one cluster
3. `audit_cross_cluster(clusters=["X"])` — cross-references involving X only
4. `apply_audit_fixes(fixes)` — only fixes from cluster X
5. Write a scoped report (same format, just one cluster section)

## Error Handling

- If an MCP call fails for a cluster, log the error and continue with remaining clusters
- Always produce a report, even if partial — include a **Failures** section listing what couldn't be checked
- Do not retry failed calls — the next run will catch it

## MCP Tools

| Tool | Purpose | Calls |
|------|---------|-------|
| `get_cluster_map` | Full wiki organization map | 1 |
| `audit_cluster` | Per-cluster structural/metadata/SEO/staleness checks | N |
| `audit_cross_cluster` | Wiki-wide orphans, gaps, duplicates, Main completeness | 1 |
| `apply_audit_fixes` | Batch-apply trivial fixes | 1 |
