---
type: article
tags:
- mcp
- api-design
- architecture
- ai-agents
summary: Principles for designing MCP tools that enable autonomous AI agent operation
  — granularity, server vs agent work, compound operations, and anti-patterns
status: active
date: '2026-03-20'
author: claude-code-researcher
cluster: generative-ai
related:
- GenerativeAiAdoptionGuide
---
# Good MCP Design

If you are building MCP (Model Context Protocol) tools for a wiki server — or any server that AI agents will call — the design of your tool surface determines whether those agents work smoothly or thrash through dozens of calls accomplishing nothing. This article covers the principles that matter most, drawn from practical experience building and using MCP tools for wiki management.

The central tension: MCP tools are the interface between a reasoning system (the agent) and a mechanical system (your server). Get the boundary wrong and you waste the agent's strengths on work the server should handle, or burden the server with judgments only the agent can make.

## The Granularity Problem

Every tool you expose is a decision point. Too many fine-grained tools and the agent orchestrates dozens of calls to accomplish one logical operation — burning tokens on coordination, requiring user approvals at each step, and increasing the chance that one call in the sequence fails and derails the whole workflow.

Too few coarse-grained tools and the agent loses the flexibility to handle edge cases. A single `manage_wiki` tool that takes a natural language instruction might sound elegant, but it pushes all the complexity into prompt engineering and gives the agent no mechanical leverage.

The sweet spot is **compound operations that do the mechanical work server-side while leaving judgment to the agent**.

Consider wiki page verification. You could expose five separate tools:

- `check_page_exists` — does the page exist?
- `check_broken_links` — are there broken links on the page?
- `get_backlinks` — who links to this page?
- `check_metadata` — is the metadata complete?
- `check_seo` — is the SEO configuration correct?

An agent verifying a cluster of 8 pages would need 40 tool calls. Each call requires parsing, decision-making, and potentially user approval. Instead, a single `verify_pages` tool that accepts a list of page names and runs all checks server-side reduces this to 1 call. The agent gets a structured report and decides what to fix — the judgment part — without having spent its budget on the mechanical part.

## Server-Side vs. Agent-Side Work

The division of labour follows a simple test: **would a for-loop do this better than an LLM?** If yes, it belongs on the server.

**Server-side work** (mechanical, deterministic):

- Iterating over collections — scanning all pages in a cluster, checking each one for broken links
- Data aggregation — counting pages, summing word counts, collecting metadata statistics
- Field-by-field validation — checking that summary length is between 50-160 characters, that required metadata fields exist
- Date arithmetic — determining which pages were modified in the last 48 hours for News Sitemap eligibility
- Link graph traversal — following outbound links, resolving backlinks, finding orphaned pages
- String matching and comparison — finding pages whose cluster field matches a pattern

**Agent-side work** (judgment, interpretation, creativity):

- Interpreting verification results — deciding which broken links are critical vs. cosmetic
- Making editorial judgments — choosing which pages need updating based on audit findings
- Formatting human-readable output — turning structured data into a summary for the wiki owner
- Deciding what to act on — prioritising issues when there are more problems than time
- Writing content — drafting page text, crafting summaries, choosing tags
- Cross-referencing context — understanding that a retirement planning article should link to index fund content

A practical example from wiki management: you need to find all pages in a cluster that are missing backlinks from the hub page. The server should handle the graph traversal (find all pages with `cluster=X`, check which ones have a backlink from the hub). The agent should handle the response (decide whether to add the missing links automatically, flag them for human review, or ignore them because they are intentionally standalone).

## Designing for Autonomous Operation

Many MCP clients run agents in restrictive permission modes where every tool call requires user approval. This is a reasonable security posture — but it means every tool call is a potential gate where automation stops and waits.

This has direct design implications:

**Fewer, higher-level tools = fewer approval prompts = smoother automation.** If verifying a cluster takes 1 tool call instead of 40, the human approves once instead of 40 times. The agent completes in seconds instead of minutes of click-through approvals.

**Tools should return structured data, not pre-formatted text.** When `verify_pages` returns a JSON structure with counts, lists, and per-page details, the agent can format it however the context requires — a brief summary for a status update, a detailed report for an audit log, or just a pass/fail for a CI pipeline. Pre-formatted prose locks in one presentation.

**Error handling belongs in the tool, not in the agent.** When a batch operation encounters one failure out of twenty, the tool should continue processing the remaining nineteen and report partial results. If the tool throws an error on the first failure, the agent has to implement retry logic, track which items succeeded, and re-invoke the tool for the remainder — all work that a server-side loop handles trivially.

Design your batch tools with a best-effort model: process everything possible, return success/failure per item, let the agent decide how to handle the failures.

## The Compound Tool Pattern

The compound tool pattern bundles operations that almost always run together into a single tool call, with options to control which operations execute.

`verify_pages` is the canonical example. It accepts:

- A list of page names
- An optional `checks` array to control which checks run

And returns a structured result with per-page details and a summary:

```
{
  pages: [
    { pageName: "RetirementPlanning", exists: true, brokenLinks: [],
      backlinks: ["Main", "IndexFundInvesting"], missingMetadata: [] },
    { pageName: "PensionTypes", exists: true, brokenLinks: ["StateRetirementAge"],
      backlinks: [], missingMetadata: ["summary"] }
  ],
  summary: { totalPages: 2, allExist: true, totalBrokenLinks: 1,
             pagesWithNoBacklinks: ["PensionTypes"], metadataIssues: 1 }
}
```

The agent reads this once and knows exactly what to fix. No follow-up calls needed to understand the situation.

**How to identify compound tool opportunities:**

1. Watch how agents use your tools in practice. When three tools are always called in sequence, that sequence is a compound tool waiting to happen.
2. Look for tool calls that exist only to feed data into the next tool call. If `get_page_metadata` is always followed by `check_metadata_completeness`, merge them.
3. Find operations where the agent adds no value between steps. Fetching a page, extracting its links, and checking if each link target exists is pure mechanics — the agent contributes nothing until it sees the results.

**Batch variants reduce round trips.** `batch_write_pages` creates multiple pages in one call instead of requiring the agent to call `write_page` in a loop. `batch_update_metadata` updates cross-references across a cluster without per-page round trips. The pattern is always the same: accept an array, process each item independently, return per-item results.

## Input/Output Contract Design

### Inputs: Require the Minimum

Do not make the agent gather prerequisites that the server could compute. If a tool needs the current version of a page for optimistic locking, let the agent pass it optionally — and have the server look it up when it is omitted. If a tool needs the list of pages in a cluster, accept a cluster name and resolve the membership server-side rather than requiring the agent to query first and pass the list.

Bad: the agent must call `list_pages` to get the page list, then `get_metadata` on each page to find cluster members, then pass that list to `verify_pages`.

Good: `verify_pages` accepts either a list of page names or a cluster identifier and resolves it internally.

### Outputs: Structured, Self-Contained, Layered

**Structured over prose.** Return JSON-like objects with clear field names, not paragraphs of text. The agent can format structured data into any presentation. It cannot reliably parse prose back into data.

**Self-contained.** Include enough context in each response that the agent does not need a follow-up call to understand what happened. If a verification found broken links, include which pages have them and what the broken targets are — do not just return a count that forces the agent to call another tool for details.

**Layered: summaries plus details.** Return both a summary (counts, pass/fail, top-level status) and per-item details. The agent can use the summary to decide whether to dig deeper, and the details when it needs to act. This is progressive disclosure at the data level.

Example from metadata queries:

```
{
  totalMatches: 47,
  matchesByCluster: { "finance": 12, "retirement": 8, "ai": 15, ... },
  pages: [ { pageName: "...", metadata: { ... } }, ... ]
}
```

The agent sees the 47-page total and the cluster breakdown without processing the full list. If it needs details on the AI cluster specifically, the data is already there.

## Progressive Disclosure in Tool Sets

Not every operation needs to be a compound tool. The principle is: **start with high-level tools that cover 80% of use cases, and keep lower-level tools available for the 20% where the agent needs fine control.**

For wiki verification:

- **80% case:** `verify_pages` handles the standard post-publish check. One call, full report, no thought required.
- **20% case:** the agent is investigating a specific link problem and needs `get_outbound_links` on one page, or wants to understand the full backlink graph for a single important page with `get_backlinks`.

For content updates:

- **80% case:** `batch_write_pages` creates an entire cluster. `batch_update_metadata` sets cross-references across all pages.
- **20% case:** `patch_page` makes a surgical edit to one section of one page. `update_metadata` changes a single field on one page.

The compound tools are not replacements for the primitives. They are accelerators for common workflows. An agent working through an unusual problem — debugging why one specific page is not appearing in search results — needs the primitives. An agent publishing its fifth cluster this week needs the batch tools.

Design your tool set in layers:

1. **Compound/batch tools** — for standard workflows (verify, publish, audit)
2. **Single-resource tools** — for targeted operations (read one page, update one page)
3. **Introspection tools** — for understanding the system (list metadata values, get wiki stats)

## Real-World Anti-Patterns

These patterns waste tokens, increase failure rates, and frustrate both agents and the humans supervising them.

### Fetch-Then-Filter

The tool returns everything and expects the agent to filter in context.

*Example:* `list_pages` returns all 500 pages, the agent scans through them looking for pages in the "finance" cluster. The agent just burned tokens on 488 irrelevant pages.

*Fix:* `query_metadata` with `field="cluster", value="finance"` returns only matching pages. Server-side filtering is free; context-window filtering is expensive.

### Assembly Required

Three tools must be called in exact sequence to accomplish one logical operation.

*Example:* To publish an article, the agent must: (1) `write_page` for the content, (2) `update_metadata` to set the frontmatter, (3) `patch_page` on the hub to add a link. If any step fails, the wiki is in an inconsistent state.

*Fix:* `write_page` accepts both content and metadata in one call. For the hub update, consider whether a `publish_to_cluster` compound tool makes sense — or at minimum, make `write_page` idempotent so retries are safe.

### Chatty Interfaces

The agent calls the same tool repeatedly with different parameters when a batch variant would do.

*Example:* Updating the `related` field on 8 cluster pages requires 8 calls to `update_metadata`. Each call is a round trip, a potential approval prompt, and a point of failure.

*Fix:* `batch_update_metadata` accepts an array of page updates and processes them in one call. The agent sends one request, gets one response with per-page results.

### Agent as Calculator

The agent does mechanical computation that the server could handle.

*Example:* The agent retrieves every page's `date` field, parses the dates, computes which pages were modified in the last 48 hours, and reports which ones qualify for the News Sitemap. That is date arithmetic plus filtering — pure server work.

*Fix:* A `query_metadata` filter for recency, or a dedicated `get_news_eligible_pages` tool, does this in milliseconds without burning agent tokens on date parsing.

### Blind Retry

When a batch operation fails, the agent retries the entire batch instead of just the failed items.

*Example:* `batch_write_pages` writes 10 pages, 1 fails. The agent calls `batch_write_pages` again with all 10. Nine pages get unnecessarily rewritten.

*Fix:* Return per-item success/failure status so the agent can retry only what failed. Better yet, make writes idempotent so retries are harmless.

## Putting It Together

Good MCP tool design is not about minimising the number of tools. It is about putting the right work on the right side of the boundary.

The server excels at: iteration, aggregation, validation, graph traversal, and anything with a deterministic answer.

The agent excels at: interpretation, judgment, prioritisation, content creation, and adapting to novel situations.

When you find yourself writing a tool that returns raw data and expects the agent to iterate through it, stop. That iteration belongs on the server. When you find yourself writing a tool that makes editorial decisions, stop. That judgment belongs with the agent.

The result is a tool surface where every call does meaningful work, every response is immediately actionable, and the agent spends its tokens on thinking — not bookkeeping.

## See Also

- [Generative AI Adoption Guide](GenerativeAiAdoptionGuide) — Broader context on adopting AI tools as an individual contributor
- [About](About) — How this wiki uses MCP for content management
