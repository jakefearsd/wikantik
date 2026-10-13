---
title: Citing a Wiki Page
cluster: agent-cookbook
kg_include: true
status: active
type: runbook
canonical_id: 01KQ419DJ32TGYRV7ZDVE83M9V
audience:
- agents
- humans
runbook:
  when_to_use:
  - You are about to mention a wiki page in an answer or in another runbook
  - You are emitting structured data (JSON, frontmatter, MCP responses) that references
    other pages
  - You are tracking a page across renames in a long-running agent task
  inputs:
  - A page slug (what users typed) or a canonical_id (already stable)
  steps:
  - If you have a slug, get its canonical_id from the page's frontmatter (read_page
    or read_pages), from the canonicalId field of /knowledge-mcp/list_pages_by_filter
    results, or from /api/structure/sitemap — /knowledge-mcp/get_page_by_id goes the
    other way, from an id to the page
  - Cite the canonical_id verbatim — 26 characters, Crockford base32, no quotes around
    it in prose
  - When linking in markdown for human readers, a page name ([label](PageName) or
    [[PageName]]) is fine while it is current — rename_page rewrites in-wiki referrers
    by default, but nothing redirects an old slug
  - In structured outputs (runbook `references:`, MCP tool results, anything stored
    outside the wiki), use canonical_id
  - To tie a claim to a specific passage, write a cite:// link whose target is the
    canonical_id (plus an optional heading path and quoted span) so the wiki can grade
    it stale when the passage changes
  - Treat the slug as ephemeral metadata and the canonical_id as the identity
  pitfalls:
  - Citing only the slug in machine-readable contexts — a future rename silently breaks
    the citation
  - Caching slug→canonical_id mappings in agent memory across sessions — the slug
    can change between sessions
  - Mixing canonical_ids and slugs in the same list — pick one form per field
  - Using the page title (the human-readable string) as a citation — it's neither
    stable nor unique
  - Putting a double quote in a cite:// span, or a closing parenthesis or whitespace
    in its target or heading path — the link is then not recognised as a citation
  related_tools:
  - /knowledge-mcp/get_page_by_id
  - /knowledge-mcp/list_pages_by_filter
  - /knowledge-mcp/list_stale_citations
  - /api/pages/by-id/{canonical_id}
  - /api/structure/sitemap
  references:
  - StructuralSpineDesign
  - ChoosingARetrievalMode
summary: Cite pages by canonical_id in machine-readable output; slugs change on rename.
  Covers the slug-to-id lookup and cite:// claim citations.
tags:
- citation
- canonical-id
- runbook
- agent-context
---

# Citing a Wiki Page

A canonical_id is a 26-character ULID. It is assigned when the page is
first saved (the structural-spine save filter auto-injects it, controlled by
`wikantik.structural_spine.enforcement.enabled`, default true) and stays
with the page across every rename. Slugs are routes; canonical_ids are
identity.

## When to use this runbook

Whenever you write a wiki citation that another process or another
session might read.

## Context

The structural index maintains a `page_canonical_ids` table that maps
canonical_id → current_slug, plus a `page_slug_history` audit trail of
every previous slug. This is why `/api/pages/by-id/{canonical_id}` (and
`get_page_by_id` on `/knowledge-mcp`) keep working after a rename — the
index resolves through the mapping table. Both treat a page the caller may
not view as nonexistent. The slug history is a record only: nothing resolves
an old slug to the new page, so a stale slug is simply a dead name.

## Walkthrough

For human-facing links in prose, a page name is fine as long as it is
current: `rename_page` rewrites in-wiki referrers when `updateLinks` is true
(the default), but a name stored outside the wiki, or in another session's
memory, keeps the old spelling. For machine-readable identity, only
canonical_ids will do. The frontmatter `steps` are the lookup procedure.

To cite a specific passage rather than a whole page, use a `cite://` link: a
Markdown link whose address is `cite://` plus the target's canonical_id, an
optional heading path and an optional quoted span of the exact words you rely
on. The shape is below, written with a space after the closing bracket so that
this page does not itself record a citation; write it without the space:

```text
[claim text] (cite://<canonical_id>/<Heading>/<Subheading> "exact words you rely on")
```

When a page is saved the wiki records each citation with the version of the
target it saw and grades it `current`, `stale` (the heading is gone or the span
no longer appears) or `target_missing`, and it re-grades whenever the target is
saved. Readers see an ordinary link; curators find stale ones with
`list_stale_citations` on `/knowledge-mcp` and `GET /admin/drift/citations`.
Tracking is on by default (`wikantik.citations.enabled`) and needs the database.
The parser is a plain pattern match over the page body, so an example written
as a real link, even inside a code block, is still recorded as a citation.

Sections returned by `assemble_bundle` carry a similar handle: the page's
`canonicalId`, its `version`, the `headingPath` and a span hash. Cite the page
together with that version (for example `AuthenticationArchitecture @ 4`).

## Pitfalls

The frontmatter `pitfalls` are the recurring failure modes. The most
expensive is caching slug→canonical_id mappings in long-lived agent
memory: when a page renames, the cached slug is wrong forever.
