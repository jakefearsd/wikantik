---
date: ''
runbook:
  when_to_use:
  - You need to find pages on a topic and the default retriever isn't returning what
    you expected
  - You are starting a new agent task and want to pick the right retrieval entry-point
    on the first call
  - You suspect the embedding service is degraded and want to fall back deliberately
  inputs:
  - Natural-language query or keyword query
  - Optional cluster or tag filter you already know
  - Optional recency bias (e.g. only pages updated in the last 30 days)
  steps:
  - To answer a question, start with /knowledge-mcp/assemble_bundle (default mode=hybrid)
    and read coverage.confidence - strong means answer from the bundle and cite it;
    partial or weak means refine the query or escalate to sparql_query / get_ontology
    for counts and enumerations
  - To discover which pages cover a topic, call /knowledge-mcp/retrieve_context (BM25
    + dense, page level); raise maxPages / chunksPerPage rather than rewording the
    query
  - If the top-5 results all share one cluster but you want more breadth, call /knowledge-mcp/list_pages_by_filter
    with that cluster, tag or type to broaden
  - If the query names a specific entity (a class, a person, a product), call /knowledge-mcp/query_nodes
    followed by /knowledge-mcp/traverse to walk the Knowledge Graph
  - If hybrid returns fewer than 3 relevant hits, retry assemble_bundle with mode=lexical
    (BM25 only) and a loosened query - sometimes the embedding model just doesn't
    cover the term, or the embedding service is degraded
  - If lexical also returns nothing, treat the query as not-yet-indexed; tell the
    user rather than fabricating
  pitfalls:
  - Do not chain more than 3 retrieval calls - every additional call costs tokens
    and rarely improves an already-mediocre result set
  - Cite results by canonical_id, not slug - slugs change on rename (see CitingAWikiPage)
  - /knowledge-mcp/search_knowledge searches Knowledge Graph nodes only, not page
    bodies - use retrieve_context or assemble_bundle for body content
  - /knowledge-mcp/find_similar uses Knowledge Graph mention centroids - it is not
    a semantic substitute for retrieve_context
  - Recency bias is a filter (list_pages_by_filter updated_since, retrieve_context
    filters.modifiedAfter) - don't try to encode it in the query string
  related_tools:
  - /knowledge-mcp/assemble_bundle
  - /knowledge-mcp/retrieve_context
  - /knowledge-mcp/list_pages_by_filter
  - /knowledge-mcp/query_nodes
  - /knowledge-mcp/traverse
  - /knowledge-mcp/search_knowledge
  - /knowledge-mcp/find_similar
  - /api/search
  references:
  - HybridRetrieval
  - StructuralSpineDesign
  - InterpretingHybridRetrievalMetrics
  - HandlingEmbeddingServiceOutages
title: Choosing a Retrieval Mode
audience:
- agents
- humans
canonical_id: 01KQ4MTH3GHP1521AWH0CCQTHD
cluster: agent-cookbook
type: runbook
tags:
- retrieval
- mcp
- runbook
- agent-context
summary: Decision procedure for choosing between assemble_bundle, retrieve_context,
  structural filters and Knowledge Graph tools - where to start and when to switch.
---
# Choosing a Retrieval Mode

Wikantik exposes several retrieval surfaces, and one tool (`assemble_bundle`) takes an explicit retrieval `mode`. Each surface has a different cost profile and a different sweet spot. Picking the wrong one wastes tokens and produces low-quality answers; picking the right one tends to land the agent on a useful page in one or two calls.

## When to use this runbook

Whenever an agent's first instinct is "I'll just call a search tool and see what comes back". That works most of the time, but the failure modes are real and the cost of a wrong second call is high. Use the frontmatter `steps` block as the contract; this body explains the *why* behind each step.

## Context

- **`assemble_bundle`** (`/knowledge-mcp`) — the primary tool for *answering* a question. Returns ranked, de-duplicated, version-pinned sections to ground and cite, plus a `coverage` block. Its optional `mode` is `hybrid` (default — BM25 + dense fused at chunk level), `dense` (vector only) or `lexical` (BM25 only, no dependence on the embedding service). It never synthesizes an answer.
- **`retrieve_context`** (`/knowledge-mcp`) — page and section *discovery*: BM25 + dense fused by Reciprocal Rank Fusion at page level, with the top contributing chunks per page and optional cluster / tag / type / `modifiedAfter` filters. `GET /api/search` is the REST face of the same pipeline. Both fall back to plain BM25 order when the embedding service is unavailable.
- **Structural and Knowledge Graph tools** — `list_pages_by_filter`, `list_clusters` and `list_tags` read the page index directly, with no retrieval pipeline. `query_nodes` + `traverse` walk the Knowledge Graph and only help when the query names an *entity* the graph has indexed; wrong tool for "how does feature X work" questions. `search_knowledge` is full-text search over Knowledge Graph *node* names and properties only — not page bodies.

## Walkthrough

The frontmatter `steps` are the canonical sequence. A few elaborations:

- Read `coverage.confidence` before composing an answer: `strong` means answer from the bundle; `partial` or `weak` means refine the query or escalate to a structured query (`sparql_query`, `get_ontology`, `list_clusters`) — especially for counts and enumerations. `unknown` (a `topSimilarity` of `-1.0`) means the dense side contributed nothing; suspect the embedding service — see [HandlingEmbeddingServiceOutages](HandlingEmbeddingServiceOutages).
- The "top-5 share one cluster" trick is a classic over-eager hybrid result. Hybrid loves clusters; if you want breadth, you have to ask for it explicitly via the structural index (`list_pages_by_filter`).
- The graph-traversal path sounds powerful but is a niche optimisation. Most agent queries are not about named entities; reach for it only when you can name the entity in the query.
- `mode=lexical` is the diagnostic move. If it returns results that the default hybrid missed, the embedding side is probably misbehaving.

## Pitfalls

The frontmatter `pitfalls` list captures the failure modes worth internalising. The chaining-budget pitfall (≤ 3 calls) is the most expensive to violate — agents that loop "search again with a different phrasing" routinely burn 15+ retrieval calls without improving the hit set. Raise `maxPages` / `chunksPerPage` instead of rewording.
