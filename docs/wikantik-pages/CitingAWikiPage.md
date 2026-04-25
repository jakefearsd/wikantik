---
canonical_id: 01KQ419DJ32TGYRV7ZDVE83M9V
title: Citing a Wiki Page
type: runbook
cluster: agent-cookbook
audience: [agents, humans]
summary: Always cite by canonical_id, never by slug. Slugs change on rename; canonical_ids are stable for the life of the page. Includes the lookup procedure when you only have a slug to start with.
tags:
  - citation
  - canonical-id
  - runbook
  - agent-context
runbook:
  when_to_use:
    - You are about to mention a wiki page in an answer or in another runbook
    - You are emitting structured data (JSON, frontmatter, MCP responses) that references other pages
    - You are tracking a page across renames in a long-running agent task
  inputs:
    - A page slug (what users typed) or a canonical_id (already stable)
  steps:
    - If you have a slug, look up canonical_id via /knowledge-mcp/get_page_by_id (after resolving via search) or via /api/structure/sitemap
    - Cite the canonical_id verbatim — 26 characters, Crockford base32, no quotes around it in prose
    - When linking in markdown, the slug is still in the URL (e.g. /wiki/HybridRetrieval) — that's fine for human-facing links because the SPA redirects renamed slugs
    - In structured outputs (frontmatter `references:`, `relations:`, MCP tool results), use canonical_id only
    - Treat the slug as ephemeral metadata and the canonical_id as the identity
  pitfalls:
    - Citing only the slug in machine-readable contexts — a future rename silently breaks the citation
    - Caching slug→canonical_id mappings in agent memory across sessions — the slug can change between sessions
    - Mixing canonical_ids and slugs in the same list — pick one form per field
    - Using the page title (the human-readable string) as a citation — it's neither stable nor unique
  related_tools:
    - /knowledge-mcp/get_page_by_id
    - /api/pages/by-id/{canonical_id}
    - /api/structure/sitemap
  references:
    - StructuralSpineDesign
    - ChoosingARetrievalMode
---

# Citing a Wiki Page

A canonical_id is a 26-character ULID. It is assigned when the page is
first saved (the structural-spine save filter auto-injects it) and stays
with the page across every rename. Slugs are routes; canonical_ids are
identity.

## When to use this runbook

Whenever you write a wiki citation that another process or another
session might read.

## Context

The structural index maintains a `page_canonical_ids` table that maps
canonical_id → current_slug, plus a `page_slug_history` audit trail of
every previous slug. This is why `/api/pages/by-id/{canonical_id}`
always works even after a rename — the index resolves through the
mapping table.

## Walkthrough

For human-facing links in prose, slugs are fine — the SPA redirects.
For machine-readable identity, only canonical_ids will do. The
frontmatter `steps` are the lookup procedure.

## Pitfalls

The frontmatter `pitfalls` are the recurring failure modes. The most
expensive is caching slug→canonical_id mappings in long-lived agent
memory: when a page renames, the cached slug is wrong forever.
