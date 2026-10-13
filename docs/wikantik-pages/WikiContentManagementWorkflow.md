---
status: active
date: '2026-04-26'
title: Wiki Content Management Workflow
related:
- WikiAnalyticsAndEngagement
- WikiPageTemplates
- WikiPermissionModelGuide
canonical_id: 01KQ0P44Z0MP877C2FD1NT8HNK
cluster: wikantik-development
type: article
tags:
- wiki
- editorial
- workflow
- content-management
- wikantik-development
summary: How to run editorial workflows on a wiki — review processes, draft states,
  ownership, and the patterns that maintain quality without making...
---
# Wiki Content Management Workflow

A wiki without editorial process drifts: inconsistent voice, stale content, contradictory pages. A wiki with too much process becomes painful: contributions slow; people give up.

The right balance varies by wiki type. This page covers the patterns.

## Wiki types and their workflows

### Open wiki (Wikipedia-style)

Anyone edits. Quality from peer review and active maintainers. No formal review process.

Works at scale (lots of editors). Doesn't work for small wikis (no critical mass).

### Curated wiki

Editors propose; curators review/merge. Quality controlled.

Common for: technical documentation, policy wikis, regulated content.

### Tightly-controlled wiki

Limited authors; formal review; publishing workflows.

For: corporate official content, customer-facing docs, legal/medical info.

### Personal wiki

One author. No workflow needed.

## Workflow components

### Drafts

Pages in development, not yet ready for general consumption.

Mechanisms:
- Separate page namespace ("Drafts/")
- Status field in frontmatter ("status: draft")
- Branch in git for git-based wikis

Drafts visible to authors and editors; not in main navigation/search.

### Reviews

Before a page is published, someone else reviews. Catches errors; ensures consistency.

Patterns:
- Pull-request review (git-based wikis)
- In-wiki review workflows
- Out-of-band Slack or email review

For technical content, reviews catch real errors. For general content, the cost-benefit varies.

### Publishing

The transition from draft to published. Formal in some workflows; implicit in others.

For "edit on save" wikis, publishing is just saving.
For curated wikis, publishing is a deliberate step.

### Update cycles

Pages need refreshing. Old content misleads.

Patterns:
- Periodic page review (annual?)
- "Last reviewed" date in frontmatter
- Triggered by external changes (API change forces doc update)

### Deprecation

Pages that are obsolete. Don't always delete (history matters); mark.

Mechanisms:
- Deprecation banner
- Status: deprecated
- Move to archive namespace

## Ownership

### Per-page owner

Each page has a designated owner. Updates flow through them.

For technical wikis with subject-matter experts, this works well.

### Per-section owner

A section of the wiki has an owner. Pages within move through them.

For larger wikis, section ownership scales better than per-page.

### Editor council

A small group reviews proposed changes. Group ownership rather than individual.

For high-stakes wikis where consistency is critical.

### No formal ownership

Anyone updates anything. Quality from community norms.

For small wikis with engaged contributors, this works. For large or stakes content, it doesn't.

## Patterns

### Frontmatter status field

```yaml
status: draft | published | needs-review | deprecated | archived
```

Clear signal about page state. Different from "is this page good" — about lifecycle.

### Last-reviewed metadata

```yaml
last_reviewed: 2026-04-26
reviewed_by: alice
```

Indicates currency. Pages reviewed recently are more trusted.

### Page templates

Standard structures for common page types. See [WikiPageTemplates](WikiPageTemplates). Reduces variance; speeds creation.

### Stale page detection

Automated detection of old pages. Flags pages not edited or reviewed recently. Periodic review queue.

### Bulk edits

For systematic changes (rename, link update, format change), bulk tooling. Avoid manual edits across hundreds of pages.

## Review depth

### Light review

Quick check: does this make sense; spelling correct; obvious errors.

For low-stakes content; fast turnaround.

### Technical review

Subject-matter expert reads carefully. Verifies facts; suggests improvements.

For technical accuracy; takes longer.

### Editorial review

Style; tone; consistency with other pages.

For voice and craft; orthogonal to technical accuracy.

For mature wikis: combination of all three at different stages.

## Tools

### Wiki-native workflow

Some wikis have built-in workflows: draft, review, publish states.

For wikis without: external tools or processes.

### Git-based wikis

Pull request review is the workflow. Standard developer tooling.

### Wiki + external review

Page edits flow through external review system (Notion + Slack; Confluence + Jira).

### Editorial calendar

For content with periodic publishing (newsletter, blog), explicit calendar of what's coming when.

## Common failure patterns

- **No review = quality erodes.** Especially for fact-based content.
- **Heavy review = contributions die.** Friction kills participation.
- **Single owner = bottleneck.** Owner busy or gone; pages can't update.
- **No deprecation = stale clutter.** Old content alongside new.
- **Inconsistent enforcement.** Sometimes review; sometimes not. Confuses contributors.

## A reasonable starter

For most internal wikis:

1. Lightweight per-page ownership
2. Frontmatter status indicating state
3. Optional review for major changes
4. Annual review of high-traffic pages
5. Deprecation rather than deletion for old content
6. Page templates for common types
7. Stale-page detection

## How Wikantik implements these patterns

The patterns above are generic. Wikantik is a Markdown wiki with a PostgreSQL-backed page store and structured frontmatter, and it builds several of them in:

- **Publishing.** Saving a page publishes it; the server has no draft or review gate. The project's own content policy is publish-direct, in tranches of 6 to 10 pages so per-page verification stays honest (see [Wiki Content Program Policy](ContentProgramPolicy)). Concurrent edits are protected by optimistic locking: the admin MCP `update_page` tool takes the `expectedContentHash` returned by `read_page`, and a stale hash returns the latest content to rebase against.
- **Status.** The canonical values are `draft`, `active` and `archived`; `archived` is Wikantik's deprecation marker. Other values such as `published` or `deprecated` are tolerated with a warning that suggests a canonical one. The field is a label: the server does not hide or gate a `draft` page (see [Frontmatter Conventions](FrontmatterConventions)).
- **Review dates.** There is no `last_reviewed` or `reviewed_by` field. Use `verified_at` and `verified_by`, stamped by the `mark_page_verified` MCP tool. Wikantik derives a `confidence` from them: `stale` when `verified_at` is absent or older than `wikantik.verification.stale_days` (default 90 days).
- **Stale-page detection.** `GET /admin/agent-grade-audit` lists pages with no `verified_at` or a stale verification. The [Wiki Audit Skill](WikiAuditSkill) and the [Wiki Audit Demand and Freshness Loop](WikiAuditDemandLoop) add a sweep of tool-practice clusters for pages unverified for 12 months.
- **Ownership.** Each page has one owner, recorded against its `canonical_id` so a rename keeps it. On first save the owner is the `author` frontmatter value when it names a real login, otherwise the `agents` service account (`wikantik.page_ownership.default_owner`); administrators reassign owners at `/admin/page-ownership`. Ownership records accountability only. Who may view or edit a page is decided by permissions and page ACLs such as `[{ALLOW edit Admin}]`.
- **Bulk edits.** `rename_page` (with link rewriting) and `rename_cluster` (returns a plan until `confirm=true`) cover systematic changes, and every page keeps a version history (`get_page_history`, `diff_page`).

## Further Reading

- [WikiAnalyticsAndEngagement](WikiAnalyticsAndEngagement) — Inputs to editorial decisions
- [WikiPageTemplates](WikiPageTemplates) — Standardized structures
- [WikiPermissionModelGuide](WikiPermissionModelGuide) — Who can do what
