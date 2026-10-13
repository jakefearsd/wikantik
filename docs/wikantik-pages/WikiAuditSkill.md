---
status: active
type: article
tags:
- wiki-audit
- broken-links
- linting
- wikantik
- quality-assurance
- content-curation
canonical_id: 01KQ0P44YZAEVHTS0BB2KS9ZQX
related:
- McpAuditTools
- WikantikDevelopment
kg_include: true
date: '2026-03-20'
depends-on:
- McpIntegration
audience:
- humans
cluster: wikantik-development
title: 'Wiki Audit Skill: On-Demand Link, Metadata, SEO and Staleness Audits'
summary: 'The wiki-audit agent skill: on-demand wiki health checks (links, orphans,
  metadata, SEO), demand and freshness sweeps, and the report it writes.'
---
# Wiki Audit Skill: On-Demand Link, Metadata, SEO and Staleness Audits

The wiki-audit skill is an agent skill (`.claude/skills/wiki-audit/SKILL.md` in the Wikantik repository) that tells a coding agent how to audit the wiki: structural integrity (broken links, orphans), metadata completeness, SEO and retrieval readiness, staleness, and under-served queries. The agent applies a short list of safe fixes and writes a prioritized Markdown report for follow-up through the `wiki-content` skill. An operator or agent runs it on demand. The Wikantik server has no scheduled audit job for it, and the skill opens no pull requests.

> **Status (2026-10-13):** The skill was written against four compound MCP tools — `get_cluster_map`, `audit_cluster`, `audit_cross_cluster` and `apply_audit_fixes` — that were retired in the 2026-06-20 MCP tool reconciliation. They no longer exist on `/wikantik-admin-mcp`, and the skill file itself carries a "stale pipeline" warning. The **demand and freshness checks** (D1 and D2 below) are current. The structural audit is run with the live tools in the next section.

## The audit with live tools

| Audit step | Live tools |
|---|---|
| Map the wiki | `list_clusters`, `list_pages_by_filter` (knowledge-mcp) |
| Per-cluster lint | `verify_pages` (admin-mcp) over each cluster's pages |
| Wiki-wide rollup | `get_broken_links`, `get_orphaned_pages` (admin-mcp) |
| Fixes | `update_page`, one page at a time, within the auto-fix policy below |
| Demand sweep (D1) | `list_retrieval_queries`, then `assemble_bundle` |
| Staleness sweep (D2) | `list_pages_by_filter` per practice cluster, then `mark_page_verified` after re-checking |

`verify_pages` takes a `checks` list. When it is omitted the tool runs `existence`, `broken_links`, `backlinks`, `outbound_links` and `metadata_completeness` (are `type`, `tags` and `summary` present). Two more checks must be requested explicitly:

- `seo_readiness` — summary missing or outside 50–160 characters, no tags, a hub without `related`, no `date`, a `cluster` without a `type`.
- `retrieval_readiness` — summary missing, restating the title or too thin; no headings or generic headings such as "Overview"; cluster missing or not kebab-case; title missing or equal to the slug.

A clean `verify_pages` result is necessary but not sufficient: it checks the form of the frontmatter, not whether the page actually surfaces for real queries. The live `assemble_bundle` check is the retrievability gate (see [Content Program Policy](ContentProgramPolicy)).

## Demand and freshness checks

- **D1, under-served real queries.** `list_retrieval_queries(max_avg_results=1, since_days=N)` lists real queries the corpus answers poorly. Confirm each miss with `assemble_bundle`. A miss whose owning page exists is a summary or heading fix; a miss with no owning page is a content gap to queue.
- **D2, tool-practice staleness.** For the practice clusters (`operations-research`, `data-science-practice`, `software-engineering-practices`) flag pages whose `verified_at` is absent or older than 12 months. Re-verify by checking the fast-decaying claims against the outside world, fix them, then `mark_page_verified`. Never re-verify by rereading the page alone.

The full runbook is [Wiki Audit Demand and Freshness Loop](WikiAuditDemandLoop).

## Auto-fix policy

The skill separates fixes an agent may apply from fixes that need editorial judgment.

- **Apply without asking:** a missing `status` (default `active`); a missing `date` (the last-modified date); a missing `cluster` when the page is already linked from its hub; a backlink to the hub added to an existing See Also section; a typo fix in a link when exactly one candidate page exists.
- **Never auto-fix:** summary text, tags, author or `related`; adding or removing pages from clusters; cross-cluster links; body content beyond a hub backlink; anything ambiguous.

## The report

The skill writes `docs/audits/YYYY-MM-DD-wiki-audit.md` in the repository (it prunes the oldest when more than 52 exist). The report has an auto-fix log, one section per cluster split into Critical, Warning and Suggestion, a wiki-wide section (orphans, cross-cluster gaps, duplicate summaries, hubs missing from Main, global broken links) and a prioritized task list that maps to `wiki-content` workflows. If a call fails, the audit logs the failure, carries on with the remaining clusters and still produces a report with a Failures section. Failed calls are not retried; the next run catches them.

## What the audit does not cover

- **Math rendering.** LaTeX in the page body is validated at save time by the math filter (`wikantik.math.enforcement.enabled`, default true). The audit does not render formulas.
- **Frontmatter drift, ontology and Knowledge Graph consistency.** The `/admin/drift` dashboard runs the frontmatter validator over every page and, when the ontology is enabled, the SHACL shapes over the materialised graph. It sweeps after each ontology rebuild (every 24 hours by default) and on demand. `GET /admin/agent-grade-audit` lists pages with weak agent-grade signals (no cluster, no inbound cluster links, a generic hub summary, no `verified_at`, stale verification).

## See Also

- [Wiki Audit Demand and Freshness Loop](WikiAuditDemandLoop) — the D1 and D2 runbook
- [Content Program Policy](ContentProgramPolicy) — the policy the audit enforces
- [Frontmatter Conventions](FrontmatterConventions) — the schema the metadata checks mirror
