# Converge MCP page-name parameters on `slug`/`slugs`

**Date:** 2026-06-12
**Status:** Design approved, pending implementation
**Components:** `wikantik-admin-mcp`, `wikantik-knowledge`

## Problem

The page-name parameter diverges across the two MCP surfaces, hurting agent zero-shot
reliability: an LLM that learned `pageNames` on the admin MCP's `delete_pages` naturally
tries `pageNames` on the knowledge MCP's `read_pages`, which only accepted `slugs`.

- **admin-MCP** identifies a page by name with **`pageName`/`pageNames`**.
- **knowledge-MCP** uses **`slug`/`slugs`**.
- `canonical_id` (the stable ULID) is a *separate* identifier axis, already consistent in
  both modules — out of scope, left untouched.

A first pass (2026-06-12) added forgiving aliases to `get_page`/`read_pages`. This spec
finishes the job: converge the *advertised* parameter name to `slug`/`slugs` on **every**
page-by-name tool in both modules, while still accepting the legacy names as silent aliases.

## Decisions

1. **Canonical term: `slug` (singular) / `slugs` (plural)** — adopt the knowledge-MCP
   convention everywhere. The page name *is* the URL slug in Wikantik; shorter is better for
   agent tokens; pairs naturally as `slug`/`slugs`.
2. **Old names kept as silent aliases.** Each tool advertises only `slug`/`slugs` in its
   schema, but still resolves the legacy/guessable names (`pageName`, `pageNames`, `name`,
   `names`, `page`, `pages`). Zero cost — the alias plumbing already exists. Robust to
   stragglers without polluting the advertised surface.
3. **`canonical_id` untouched** — distinct axis, already consistent.

## Architecture

Centralize the accepted-alias set in two shared accessors in
`com.wikantik.mcp.tools.McpToolUtils` (the cross-module util `wikantik-knowledge` already
depends on), so the alias set is defined once and cannot drift per-tool:

```java
/** Canonical singular page identifier: advertises `slug`; accepts legacy/guessable aliases. */
public static String pageSlug( final Map< String, Object > args ) {
    return getStringAny( args, "slug", "pageName", "name", "page" );
}

/** Canonical plural page identifiers: first list-valued arg among the accepted keys, else null. */
public static List< ? > pageSlugs( final Map< String, Object > args ) {
    return firstListArg( args, "slugs", "pageNames", "names", "pages" );
}

/** First list-valued argument among {@code keys}, or null. (Promoted from ReadPagesTool.) */
public static List< ? > firstListArg( final Map< String, Object > args, final String... keys ) { … }
```

Every page-by-name tool resolves its identifier through `pageSlug` / `pageSlugs`.

## Per-tool changes

For each affected tool: (a) the input-schema property key becomes `slug`/`slugs` with an
updated description + examples; (b) it's removed from `pageName`/`pageNames` advertising;
(c) it resolves via the shared accessor; (d) the missing-arg error names the accepted keys
(e.g. `"a page identifier is required (one of: slug, pageName, name)"`).

**admin-MCP — singular `pageName` → `slug`:** `read_page`, `diff_page`, `get_backlinks`,
`get_broken_links`, `get_outbound_links`, `get_page_history`, `preview_structured_data`.

**admin-MCP — plural `pageNames` → `slugs`:** `delete_pages`, `mark_page_verified`,
`verify_pages`.

**admin-MCP — mixed:**
- `update_page`: rename the name selector `pageName` → `slug` (accept alias); keep
  `canonical_id` exactly as-is (it's the other, separate way to address the page).
- `write_pages`: the identifier lives on **each page object** in the `pages` array — rename
  that per-item field `pageName` → `slug` (accept `pageName` per item). The outer `pages`
  array name is unchanged (it's a list of page *objects*, not names).

**knowledge-MCP:** already on `slug`/`slugs` (`get_page`, `read_pages`, `get_page_by_id`,
`get_page_for_agent`, `list_pages_by_filter`). Work here is confirmation only: ensure none
still *advertise* `pageName`/`pageNames`, and route singular/plural through the shared
accessors for a single alias source of truth.

**Out of scope (not page identifiers):** KG-entity `name` (`get_node`, `find_similar`,
`curate_nodes`, `traverse`, `list_orphaned_kg_nodes`), cluster `name` (`list_clusters`),
retrieval/query params, and all output-field names.

## Testing

- **Per converged tool:** a test asserting the schema advertises `slug`/`slugs` (not
  `pageName`/`pageNames`), the canonical name resolves, and a legacy alias (`pageName`) still
  resolves.
- **Convergence-guard test:** iterate every registered tool in both servers' registries
  (`McpServerInitializer`, `KnowledgeMcpInitializer`) and assert **no tool's input-schema
  `properties` contains `pageName` or `pageNames`**. This is the enforcement of "fully
  converged" and catches future drift. (If wiring a live registry in a unit test is heavy,
  fall back to a reflection/file scan of the tool classes — but a registry walk is preferred.)
- **Regression:** existing tests that pass `pageName`/`pageNames` keep passing (accepted as
  aliases), so breakage is minimal; fix only those that assert the *schema advertises*
  `pageName`.

## Risks

- `write_pages`/`update_page` have nested/dual identifier handling — change carefully and
  test each path (slug, alias, and canonical_id for update_page).
- The convergence-guard test must enumerate the *real* registered tool set; if a tool is
  missed by the registry walk, drift could slip through. Prefer the registry over a hardcoded
  list.
