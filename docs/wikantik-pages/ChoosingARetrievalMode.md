---
canonical_id: 01KQ4MTH3GHP1521AWH0CCQTHD
title: Choosing a Retrieval Mode
type: runbook
cluster: agent-cookbook
audience: [agents, humans]
summary: Decision procedure for picking between BM25, hybrid, and graph-traversal retrieval against this wiki — when to start where, when to switch modes, and when to give up and ask for human help.
tags:
  - retrieval
  - mcp
  - runbook
  - agent-context
runbook:
  when_to_use:
    - You need to find pages on a topic and the default retriever isn't returning what you expected
    - You are starting a new agent task and want to pick the right retrieval entry-point on the first call
    - You suspect the embedding service is degraded and want to fall back deliberately
  inputs:
    - Natural-language query or keyword query
    - Optional cluster or tag filter you already know
    - Optional recency bias (e.g. "only pages updated in the last 30 days")
  steps:
    - Start with /knowledge-mcp/search_knowledge — hybrid is the default and dominates for most queries
    - If the top-5 results all share one cluster but you want more breadth, call /knowledge-mcp/list_pages_by_filter with that cluster set to broaden
    - If the query names a specific entity (a class, a person, a product), call /knowledge-mcp/query_nodes followed by /knowledge-mcp/traverse to walk the graph
    - If no mode returns more than 3 relevant hits, fall back to /api/search BM25 with a loosened query — sometimes the embedding model just doesn't cover the term
    - If BM25 also returns nothing, treat the query as not-yet-indexed; tell the user rather than fabricating
  pitfalls:
    - Do not chain more than 3 retrieval calls — every additional call costs tokens and rarely improves an already-mediocre result set
    - Cite results by canonical_id, not slug — slugs change on rename (see CitingAWikiPage)
    - /knowledge-mcp/find_similar uses mention centroids — it is not a semantic substitute for search_knowledge
    - Recency bias is a tag/filter on /knowledge-mcp/list_pages_by_filter — don't try to encode it in the search_knowledge query string
  related_tools:
    - /knowledge-mcp/search_knowledge
    - /knowledge-mcp/list_pages_by_filter
    - /knowledge-mcp/query_nodes
    - /knowledge-mcp/traverse
    - /knowledge-mcp/find_similar
    - /api/search
  references:
    - HybridRetrieval
    - StructuralSpineDesign
    - InterpretingHybridRetrievalMetrics
    - HandlingEmbeddingServiceOutages
---

# Choosing a Retrieval Mode

Wikantik exposes three retrieval surfaces. Each has a different cost
profile and a different sweet spot. Picking the wrong one wastes tokens
and produces low-quality answers; picking the right one tends to land
the agent on a useful page in one or two calls.

## When to use this runbook

Whenever an agent's first instinct is "I'll just call search_knowledge
and see what comes back". That works most of the time, but the failure
modes are real and the cost of a wrong second call is high. Use the
frontmatter `steps` block as the contract; this body explains the *why*
behind each step.

## Context

- **BM25 (`/api/search`)** — fast, deterministic, no dependency on the
  embedding service. Best when the query already contains the right
  vocabulary.
- **Hybrid (`/knowledge-mcp/search_knowledge`)** — BM25 + dense
  embeddings fused via Reciprocal Rank Fusion (k=60). The default. Best
  for natural-language queries that don't necessarily share vocabulary
  with the target page.
- **Graph traversal (`/knowledge-mcp/query_nodes` + `traverse`)** — only
  useful when the query is about a *named entity* the knowledge graph
  has indexed. Wrong tool for "how does feature X work" questions.

## Walkthrough

The frontmatter `steps` are the canonical sequence. A few elaborations:

- The "top-5 share one cluster" trick (step 2) is a classic over-eager
  hybrid result. Hybrid loves clusters; if you want breadth, you have to
  ask for it explicitly via the structural index.
- The graph-traversal path (step 3) sounds powerful but is a niche
  optimisation. Most agent queries are not about named entities; reach
  for it only when you can name the entity in the query.
- The BM25 fallback (step 4) is the diagnostic move. If BM25 returns
  results that hybrid missed, the embedding service is probably
  misbehaving — see `HandlingEmbeddingServiceOutages`.

## Pitfalls

The frontmatter `pitfalls` list captures the failure modes worth
internalising. The chaining-budget pitfall (≤ 3 calls) is the most
expensive to violate — agents that loop "search again with a different
phrasing" routinely burn 15+ retrieval calls without improving the
hit set.
