---
canonical_id: 01KQ445HP8DER94KF2WJZZ8JZ5
title: Finding the Right MCP Tool
type: runbook
cluster: agent-cookbook
audience: [agents, humans]
summary: Decision tree for picking the right MCP tool across this wiki's two MCP servers — `/wikantik-admin-mcp` (writes + analytics) and `/knowledge-mcp` (read-only retrieval and graph). Avoids the common mistake of reaching for `get_page` when `get_page_for_agent` is the right call.
tags:
  - mcp
  - tool-selection
  - runbook
  - agent-context
runbook:
  when_to_use:
    - You know what you want to do but don't yet know which tool does it
    - You have used a tool that worked but suspect there's a better-shaped one for your scenario
    - You are about to write a script that loops through pages and want the cheapest read path
  inputs:
    - The intent in one sentence (read, write, navigate, verify, search, propose)
    - Whether you need write access (admin MCP) or read-only is sufficient (knowledge MCP)
  steps:
    - Decide read vs. write — write tools live on /wikantik-admin-mcp and require an author-configurable agent; read tools live on /knowledge-mcp
    - For "find pages on a topic" reach for /knowledge-mcp/search_knowledge first
    - For "give me the agent-shape of this page" use /knowledge-mcp/get_page_for_agent — not get_page
    - For "rename, write, or update" use /wikantik-admin-mcp's rename_page, write_pages, or update_page
    - For "verify a page" use /wikantik-admin-mcp/mark_page_verified, then triage at /admin/verification
    - When in doubt, read this page over /knowledge-mcp/get_page_for_agent and quote its steps directly
  pitfalls:
    - Calling /knowledge-mcp/get_page when you only need orientation — get_page_for_agent is the budgeted projection
    - Mixing /wikantik-admin-mcp tools into a read-only conversation — they require write privileges and will fail noisily
    - Trying to use /knowledge-mcp/find_similar as a search engine — it surfaces semantic neighbours of a known page, not pages matching a query
    - Forgetting that bare tool names work in `related_tools` frontmatter but never in actual MCP calls — calls always need the canonical path
  related_tools:
    - /knowledge-mcp/search_knowledge
    - /knowledge-mcp/get_page_for_agent
    - /knowledge-mcp/get_page
    - /knowledge-mcp/list_pages_by_filter
    - /knowledge-mcp/traverse_relations
    - /wikantik-admin-mcp/mark_page_verified
    - /wikantik-admin-mcp/write_pages
    - /wikantik-admin-mcp/rename_page
  references:
    - GoodMcpDesign
    - AgentGradeContentDesign
    - ChoosingARetrievalMode
    - VerifyingAnAgentGeneratedPage
---

# Finding the Right MCP Tool

There are about thirty MCP tools across the two servers this wiki
exposes. Most agents only ever need five of them. This runbook is the
decision tree.

## When to use this runbook

Any time you catch yourself reaching for `get_page` when you really only
needed orientation, or trying to write through `/knowledge-mcp` (which
is read-only). Re-read this when you start a new agent session.

## Context

Two MCP endpoints, partitioned by trust level:

- **`/knowledge-mcp`** — read-only. Hosts retrieval (search_knowledge,
  retrieve_context, find_similar), navigation (list_clusters,
  list_tags, list_pages_by_filter, get_page_by_id, traverse_relations),
  knowledge-graph traversal (discover_schema, query_nodes, get_node,
  traverse), and the agent-grade projection (get_page_for_agent).
- **`/wikantik-admin-mcp`** — writes + analytics. Hosts page edits
  (write_pages, update_page, rename_page, delete_pages), verification
  (mark_page_verified, verify_pages), structural analytics
  (get_backlinks, get_page_history, diff_page, get_outbound_links,
  get_broken_links, get_orphaned_pages, get_wiki_stats,
  preview_structured_data, ping_search_engines), and graph proposals
  (propose_knowledge, list_proposals).

## Walkthrough

Two questions disambiguate every scenario:

1. **Read or write?** Writes go through `/wikantik-admin-mcp` only and
   require an author-configurable agent. If you're not certain you have
   write privileges, default to read.
2. **Full body or just the shape?** If you only need to *cite* the page
   or *orient* yourself, `get_page_for_agent` returns the token-budgeted
   projection — verification state, key facts, headings, typed
   relations. If you need the prose, fall back to `get_page`.

For the broader retrieval question — "which tool finds me a page" —
defer to `ChoosingARetrievalMode`.

## Pitfalls

The frontmatter `pitfalls` capture the recurring mistakes. The most
expensive in tokens-per-error is the get_page-for-orientation antipattern:
agents pull the entire markdown body when a 4 KB projection would have
sufficed.
