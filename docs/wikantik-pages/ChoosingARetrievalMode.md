---
verified_at: '2026-10-24T23:05:47.717705118Z'
related:
- AgentCookbook
- WikantikSearchAndRetrieval
- HybridRetrieval
- StructuralSpineDesign
- InterpretingHybridRetrievalMetrics
- HandlingEmbeddingServiceOutages
- CitingAWikiPage
- FindingTheRightMcpTool
audience:
- humans
- agents
status: active
verified_by: wikantik-cli
kg_include: true
summary: Decision procedure for choosing between assemble_bundle, get_briefing, retrieve_context,
  structural filters, and Knowledge Graph MCP tools.
canonical_id: 01KQ4MTH3GHP1521AWH0CCQTHD
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
tags:
- retrieval
- mcp
- runbook
- agent-context
- hybrid-retrieval
title: 'Choosing a Retrieval Mode: Decision Tree for Bundles, Context, Spine, and
  Graph Tools'
type: runbook
cluster: agent-cookbook
date: '2026-10-24'
---
# Choosing a Retrieval Mode: Decision Tree for `get_briefing`, `assemble_bundle`, `retrieve_context`, and Graph Tools

Wikantik exposes **multiple specialized retrieval surfaces** on `/knowledge-mcp`, and its primary answer-grounding tool (`assemble_bundle`) accepts an explicit `mode` parameter (`hybrid`, `lexical`, or `dense`). Each entry point has a distinct token cost, ranking granularity, and failure mode. Calling the wrong tool wastes context window budget and degrades answer quality; following a deterministic decision tree lands the agent on authoritative, version-pinned evidence in **1 to 2 tool calls**.

---

## 1. Agent Retrieval Decision Flowchart

Before issuing a search call, classify your task intent along four axes: **Session Initialization**, **Question Answering / Fact Grounding**, **Exploratory Topic Mapping**, or **Formal Enumeration / Entity Traversal**.

```mermaid
flowchart TD
    Start["Agent Needs Knowledge from Wikantik"] --> Intent{"What is the primary task intent?"}

    Intent -- "1. Starting a coding or curation session\nin a project repo (SA-13)" --> Brief["Call get_briefing(clusters=[...], prompt=...)<br/>Returns cluster spine + cites: contracts + top sections"]

    Intent -- "2. Answering a technical question\nor grounding an edit" --> Bundle["Call assemble_bundle(query=..., mode='hybrid')<br/>Inspect coverage.confidence"]
    Bundle --> Cov{"coverage.confidence?"}
    Cov -- "strong" --> Answer["Answer & cite by canonical_id<br/>(Done in 1 call)"]
    Cov -- "partial / weak\n(Exact term or embedder suspect)" --> Lex["Retry assemble_bundle(mode='lexical')<br/>with broadened keywords"]
    Cov -- "unknown (topSimilarity = -1.0)" --> Outage["Embedder down -> Fail-closed BM25 active<br/>See HandlingEmbeddingServiceOutages"]

    Intent -- "3. Mapping which pages exist\nin a domain / cluster" --> Disc["Call retrieve_context(query=..., maxPages=10)<br/>or list_pages_by_filter(cluster=...)"]

    Intent -- "4. Exact counts, class hierarchies,\nor named entity hops" --> Struct{"Page metadata or\ndomain entity?"}
    Struct -- "Taxonomy / Counts / RDF" --> Sparql["Call sparql_query or list_clusters / list_tags"]
    Struct -- "Named Entity Relationships" --> KG["Call query_nodes -> traverse(startNode=...)"]
```

---

## 2. Comparative Matrix of `/knowledge-mcp` Retrieval Tools

| Tool Name | Indexing Unit | Ranking / Execution Engine | Token Efficiency & Output Shape | When to Choose (and When to Avoid) |
| :--- | :--- | :--- | :--- | :--- |
| **`get_briefing`** | Cluster spine + `cites:` contracts + section chunks | Structural Index + `cites:` graph + Hybrid RRF | High density: hub summary, member list, cited cross-repo contracts (`SA-13`), and prompt-matched sections. | **Mandatory first call at session start (`SA-13`).** Do not call repeatedly mid-session for narrow code questions. |
| **`assemble_bundle`** | Section chunks (`kg_content_chunks`) | Global chunk-level BM25 + `pgvector` HNSW RRF (`mode="hybrid"`, `"lexical"`, or `"dense"`) | Token-budgeted (`maxTokens`), de-duplicated, version-pinned sections + `coverage` telemetry block. | **Default tool for answering questions.** Avoid for "list every page in cluster X" enumerations (use `list_pages_by_filter` instead). |
| **`retrieve_context`** | Full pages + top chunks per page | Page-level BM25 + dense RRF + optional 1-hop `expandNeighbors` | Returns page summaries, metadata, and top $k$ excerpts per page. | **Best for topic discovery** when deciding which pages to read or edit. Raise `maxPages` / `chunksPerPage` rather than rewording queries. |
| **`list_pages_by_filter`** | Page metadata (`page_canonical_ids`) | Deterministic SQL/Spine filter (`cluster`, `tag`, `type`, `status`, `updated_since`) | Zero embedding overhead; exact completeness. | **Best for breadth and recency filtering** (e.g., when top-5 hybrid hits cluster too tightly in one domain). |
| **`query_nodes` + `traverse`** | Knowledge Graph entities (`kg_nodes`, `kg_edges`) | Exact/prefix entity lookup + multi-hop predicate walk (`min_tier`) | Compact JSON entity/edge subgraphs. | **Use only when the query names a specific entity** (`PostgreSQL`, `DeepSeek-R1`). Never use as a substitute for full-text prose search. |
| **`sparql_query`** | Jena TDB2 RDF A-Box (`wikantik.ttl`) | W3C SPARQL 1.1 query engine | Exact tabular bindings (`SELECT`) or triples. | **Best for formal aggregations, `skos:broader` chains, and cross-type joins** that vector search cannot count reliably. |

---

## 3. Interpreting `assemble_bundle` Coverage Signals & Switching Modes

Every `assemble_bundle` response includes a `coverage` diagnostic object computed from the top dense similarity score $s_{\max} = \max_i \text{sim}(\vec{u}_i, \vec{v}_q)$ and the number of distinct qualifying sections $N_{\text{sec}}$:

$$
\text{confidence} = \begin{cases}
\text{unknown} & \text{if } s_{\max} = -1.0 \text{ (dense embedder offline; BM25 fallback)} \\
\text{strong} & \text{if } s_{\max} \ge \tau_{\text{strong}} \text{ and } N_{\text{sec}} \ge 3 \\
\text{partial} & \text{if } \tau_{\text{weak}} \le s_{\max} < \tau_{\text{strong}} \\
\text{weak} & \text{if } s_{\max} < \tau_{\text{weak}}
\end{cases}
$$

### Actionable Rules by `coverage.confidence`:
1. **`strong`:** Stop retrieving. Ground your answer directly in the returned sections and cite each source by its 26-character ULID (`canonical_id`, see [[CitingAWikiPage|Citing a Wiki Page]]).
2. **`partial` or `weak` on an exact technical identifier:** Retry **once** with `assemble_bundle(query="...", mode="lexical")` using 2–4 core identifier tokens (e.g., `"OntologyShaclValidator write chokepoint"`). General-purpose embedding models occasionally compress rare Java class names or config flags poorly, whereas Lucene BM25 hits them at rank #1.
3. **`partial` or `weak` on an enumeration or count question:** Do not re-run vector search. Switch immediately to `list_clusters`, `list_pages_by_filter`, or `sparql_query`.
4. **`unknown` (`topSimilarity: -1.0`):** The Ollama embedding service is unreachable and hybrid retrieval has failed closed to BM25 lexical ranking. Proceed with lexical keyword queries and follow [[HandlingEmbeddingServiceOutages|Handling Embedding Service Outages]] if you are an ops agent.

---

## 4. Worked JSON-RPC Examples

### Step 1: Default Hybrid Bundle Call
```json
{
  "name": "assemble_bundle",
  "arguments": {
    "query": "How doesConfidenceComputer calculate authoritative vs provisional page confidence?",
    "mode": "hybrid",
    "maxTokens": 3000
  }
}
```

### Step 2: Deliberate Switch to `mode="lexical"` for Exact Config/Symbol Lookup
```json
{
  "name": "assemble_bundle",
  "arguments": {
    "query": "wikantik.verification.stale_days trusted_authors ConfidenceComputer",
    "mode": "lexical",
    "maxTokens": 3000
  }
}
```

### Step 3: Broadening When Hybrid Results Over-Concentrate in One Cluster
```json
{
  "name": "list_pages_by_filter",
  "arguments": {
    "cluster": "agent-cookbook",
    "type": "runbook",
    "limit": 25
  }
}
```

---

## 5. Critical Pitfalls to Avoid

1. **Violating the 3-Call Retrieval Ceiling:** Never chain 5–15 paraphrased `retrieve_context` or `assemble_bundle` calls hoping new synonyms will surface a missing page. If `hybrid` $\to$ `lexical` $\to$ `list_pages_by_filter` returns nothing, the topic is an unindexed content gap—report that gap cleanly rather than burning context tokens.
2. **Confusing `search_knowledge` with Full-Text Search:** `search_knowledge` searches `kg_nodes.name` and JSONB entity properties only—it does **not** search Markdown page bodies.
3. **Confusing `find_similar` with Chunk Search:** `find_similar` compares entity mention-centroid vectors ($\vec{e}_v$) in the Knowledge Graph; use `assemble_bundle` or `retrieve_context` for document sections.
4. **Encoding Recency in the Query String:** Passing `"recent pages updated in October 2026"` into a vector query matches pages that literally contain the word `"October"`. Always use structural date filters (`updated_since` on `list_pages_by_filter` or `filters.modifiedAfter` on `retrieve_context`).

---

**Cluster Hub:** [[AgentCookbook|Agent Cookbook]] (`agent-cookbook`)
