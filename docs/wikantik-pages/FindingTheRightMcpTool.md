---
title: Finding the Right MCP Tool
cluster: agent-cookbook
kg_include: true
status: active
type: runbook
canonical_id: 01KQ445HP8DER94KF2WJZZ8JZ5
audience:
- agents
- humans
runbook:
  when_to_use:
  - You know what you want to do but don't yet know which tool does it
  - You have used a tool that worked but suspect there's a better-shaped one for your
    scenario
  - You are about to write a script that loops through pages and want the cheapest
    read path
  inputs:
  - The intent in one sentence (read, write, navigate, verify, search, propose)
  - Whether you need write access (admin MCP) or read-only is sufficient (knowledge
    MCP)
  steps:
  - Decide read vs. write — write tools live on /wikantik-admin-mcp and need an mcp_admin
    key; read tools live on /knowledge-mcp and an mcp_read key is enough
  - For "answer a question from the wiki" use /knowledge-mcp/assemble_bundle and check
    its coverage.confidence; for "which pages are relevant" use /knowledge-mcp/retrieve_context;
    search_knowledge finds Knowledge Graph nodes, not pages
  - For "give me the agent-shape of this page" use /knowledge-mcp/get_page_for_agent
    (it takes a canonical_id, which list_pages_by_filter returns) — not get_page
  - For "rename, write, or update" use /wikantik-admin-mcp's rename_page, write_pages,
    or update_page
  - For "verify a page" use /wikantik-admin-mcp/mark_page_verified, then triage at
    /admin/verification
  - When in doubt, read this page over /knowledge-mcp/get_page_for_agent and quote
    its steps directly
  pitfalls:
  - Calling /knowledge-mcp/get_page when you only need orientation — it returns only
    frontmatter metadata and a URL; get_page_for_agent is the budgeted projection,
    and read_pages gives the raw markdown
  - Mixing /wikantik-admin-mcp tools into a read-only conversation — they require
    an mcp_admin key (an mcp_read key gets 403 there) and then run with no per-user
    permission checks
  - Trying to use /knowledge-mcp/find_similar as a search engine — it surfaces Knowledge
    Graph nodes similar to a known node, not pages matching a query
  - Treating the /knowledge-mcp/<tool> form used in related_tools as call syntax —
    an MCP call names only the tool (tools/call with name assemble_bundle) on the
    endpoint you connected to; related_tools also accepts bare tool names
  related_tools:
  - /knowledge-mcp/assemble_bundle
  - /knowledge-mcp/retrieve_context
  - /knowledge-mcp/search_knowledge
  - /knowledge-mcp/get_page_for_agent
  - /knowledge-mcp/get_page
  - /knowledge-mcp/read_pages
  - /knowledge-mcp/list_pages_by_filter
  - /knowledge-mcp/traverse
  - /wikantik-admin-mcp/mark_page_verified
  - /wikantik-admin-mcp/write_pages
  - /wikantik-admin-mcp/rename_page
  references:
  - GoodMcpDesign
  - AgentGradeContentDesign
  - ChoosingARetrievalMode
  - VerifyingAnAgentGeneratedPage
summary: 'MCP tool decision tree: writes go to `/wikantik-admin-mcp`, reads to `/knowledge-mcp`.
  Fixes the `get_page`-vs-`get_page_for_agent` antipattern.'
tags:
- mcp
- tool-selection
- runbook
- agent-context
---

# Finding the Right MCP Tool

There are up to fifty MCP tools across the two servers this wiki
exposes (29 on the admin server and 21 on the knowledge server when every
optional subsystem is wired; see [MCP Integration](McpIntegration) for the
inventory). Most agents only ever need five of them. This runbook is the
decision tree.

## When to use this runbook

Any time you catch yourself reaching for `get_page` when you really only
needed orientation, or trying to write through `/knowledge-mcp` (which
is read-only). Re-read this when you start a new agent session.

## Context

Two MCP endpoints, partitioned by trust level:

- **`/knowledge-mcp`** — read-only, authenticated with an `mcp_read` (or
  stronger) key, and limited to pages an anonymous visitor may view. Hosts
  grounding and retrieval (assemble_bundle, retrieve_context, get_briefing,
  read_pages, get_page, list_pages, list_metadata_values), navigation
  (list_clusters, list_tags, list_pages_by_filter, get_page_by_id),
  knowledge-graph traversal (discover_schema, query_nodes, get_node,
  traverse, search_knowledge, find_similar), the agent-grade projection
  (get_page_for_agent), and the ontology and citation tools (get_ontology,
  sparql_query, list_stale_citations).
- **`/wikantik-admin-mcp`** — writes + analytics, authenticated with an
  `mcp_admin` key. Hosts page edits (write_pages, update_page, rename_page,
  rename_cluster, delete_pages, read_page), verification
  (mark_page_verified, verify_pages), structural analytics
  (get_backlinks, get_page_history, diff_page, get_outbound_links,
  get_broken_links, get_orphaned_pages, get_wiki_stats,
  preview_structured_data, ping_search_engines), graph proposals and
  curation (propose_knowledge, list_proposals, inspect_proposals,
  review_proposals, curate_nodes, curate_edges, plus admin-bypass copies of
  query_nodes and search_knowledge and list_orphaned_kg_nodes), and the
  query-log and content-opportunity tools (list_retrieval_queries,
  list_content_opportunities, snooze_opportunity).

## Walkthrough

Three questions disambiguate every scenario:

1. **Read or write?** Writes go through `/wikantik-admin-mcp` only and
   need an `mcp_admin` key. If you're not certain you have
   write privileges, default to read.
2. **Answer a question, or find a page?** To answer from wiki content use
   `assemble_bundle` and read its `coverage.confidence`; `strong` means cite
   the returned sections, anything else means escalate to `retrieve_context`
   (which pages), then `read_pages` (their bodies), then `traverse` or
   `query_nodes` (entities), then `sparql_query` (counts and enumerations).
3. **Full body or just the shape?** If you only need to *cite* the page
   or *orient* yourself, `get_page_for_agent` returns the token-budgeted
   projection — verification state, key facts, headings outline, recent
   changes, tool hints, derived agent hints and any stale citations. It takes
   a `canonical_id`; `list_pages_by_filter` returns one for each page. If you
   need the prose, use `read_pages` (up to 20 slugs; `get_page` returns only
   frontmatter metadata and a URL).

For the broader retrieval question — "which tool finds me a page" —
defer to [ChoosingARetrievalMode](ChoosingARetrievalMode).

## Pitfalls

The frontmatter `pitfalls` capture the recurring mistakes. The most
expensive in tokens-per-error is the orientation antipattern: agents pull
a whole markdown body (or a bare `get_page` that tells them nothing) when
a 4 KB `get_page_for_agent` projection would have sufficed.
