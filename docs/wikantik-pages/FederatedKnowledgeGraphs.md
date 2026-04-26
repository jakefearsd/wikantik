---
title: Federated Knowledge Graphs
type: article
cluster: agentic-ai
status: active
date: '2026-04-25'
tags:
- knowledge-graph
- federated-data
- entity-resolution
- multi-source
summary: Combining knowledge graphs from multiple sources without merging
  storage — query federation, entity resolution across sources, and the
  hard problem of disagreement between sources.
related:
- KnowledgeGraphCompletion
- KnowledgeGraphVsRelationalDatabase
- EntityResolutionTechniques
- GraphDatabaseFundamentals
hubs:
- AgenticAi Hub
---
# Federated Knowledge Graphs

A federated knowledge graph queries across multiple independent KGs without merging them into a single store. Instead of building one master KG, you query several — each owned by a different team or organisation — and integrate at query time.

The pitch is decentralisation: organisations keep ownership of their data; queries combine across sources. The reality is harder than the pitch suggests; entity resolution and disagreement between sources are the load-bearing problems.

## When federation is worth the cost

You should consider federation when:

- **Multiple teams own different parts of the data** and you can't move everything into one store (organisational, legal, technical reasons).
- **Sources have different update cadences** — one is updated daily; another quarterly; merging would require complex sync.
- **Data sovereignty** — geo-restricted data can't leave its source.
- **Source-of-truth ambiguity** — different sources are authoritative for different aspects.

You should not consider federation when:

- One team owns all the data — just merge into one KG.
- Sources change rarely — periodic ETL into a unified KG is simpler.
- Query patterns are always single-source — federation overhead doesn't pay.

Federation is operationally heavier than a unified KG. Adopt only when the unified alternative is genuinely worse.

## The hard problem: entity resolution across sources

The same entity in different sources rarely has the same identifier. "Anthropic" in one source is `anthropic-01`; in another it's `org-12345`; in a third it's stored only as the string "Anthropic, PBC".

Federation requires resolving these to the same logical entity at query time.

Approaches:

### Shared identifier registry

A centralised entity registry maps source-specific IDs to canonical IDs:

```
Canonical: ent-anthropic
Source A id: anthropic-01
Source B id: org-12345  
Source C id: (string match: "Anthropic, PBC")
```

Sources publish to the registry; the registry enforces uniqueness; queries translate via the registry.

Works for highly structured data with curatable mappings.

### Probabilistic matching

For entities without explicit cross-source IDs, match on attributes (name, address, founder, etc.) with similarity scoring. Confidence below a threshold = "might be the same"; above = "treat as same."

Probabilistic matching is fuzzy; can produce false positives (wrong entities merged) and false negatives (same entity treated as different).

Fundamental: entity resolution is its own discipline. See [EntityResolutionTechniques].

### Embedding-based matching

Embed each entity (name + key attributes) into a vector space; nearest-neighbour matching across sources. Works well when sources have descriptive text; fragile when names are ambiguous.

### Hybrid

Most production systems combine: explicit ID mappings where they exist; rule-based matching where attributes match unambiguously; probabilistic / embedding-based fallback.

## Query federation

Three styles:

### Pre-computed unification

Periodically pull from all sources into a unified store. Queries hit only the unified store.

This is "ETL into a master KG." Not really federation; just a unified KG with periodic refresh. Simple operationally; doesn't preserve real-time across sources.

### Query-time federation with a coordinator

A federation engine receives queries; decomposes them per source; queries each source; combines results.

```
Query: "all Anthropic competitors that produce LLMs"

Coordinator:
  1. Source A: list Anthropic's competitors.
  2. Source B: list LLM producers.
  3. Resolve entities across sources.
  4. Intersect.
  5. Return.
```

Tools: GraphQL Federation (for graph APIs); SPARQL federation (for RDF stores); custom orchestration for property graphs.

Strengths: fresh data; no sync.
Weaknesses: slow (multiple round-trips); complex (coordinator owns query planning); fragile (any source down breaks queries).

### Hybrid: cache + fall-through

Hot data cached locally (refreshed periodically); cold data fetched live.

Pragmatic approach for most production federations. Cache hits are fast; cache misses fall through to source.

## Disagreement between sources

The hardest federated KG problem: sources disagree.

- Source A says Anthropic was founded in 2021. Source B says 2020.
- Source A says Dario Amodei is CEO. Source B has him as Chief Scientist.
- Source A has a relation Source B doesn't. Source B has the inverse.

Resolution strategies:

- **Source ranking.** Trust source A over B for facts about companies; B over A for facts about people. Encode the priorities.
- **Provenance.** Don't pick one; surface both, with their sources, to the consumer. Let the application decide.
- **Recency.** Prefer the most recently updated.
- **Confidence weighting.** Each fact has a confidence; weighted decision.

For agentic / RAG use cases, the "provenance" approach is often cleanest — let the LLM see the conflict and synthesise. For automated decisions, you need a tie-breaking policy.

## Standards: SPARQL, RDF federation

The Semantic Web community spent decades on this. SPARQL has explicit federation:

```sparql
SELECT ?company ?ceo
WHERE {
  ?company rdf:type :Company .
  SERVICE <https://source-a.example/sparql> {
    ?company :founded ?date .
  }
  SERVICE <https://source-b.example/sparql> {
    ?company :ceo ?ceo .
  }
}
```

SPARQL federation works for RDF triple stores; less applicable for property graphs (Neo4j-style).

## Practical alternatives to "real" federation

Often, what teams call "federation" is actually one of:

- **API composition** — call a few APIs; merge results in the application. Works for narrow query patterns; doesn't scale to general-purpose queries.
- **Read-only data lake** — replicate sources into a data lake; query unified. Effectively a unified KG with stale data.
- **Periodic ETL into central KG** — same as above; more transformation; probably what you actually want.

True query-time federation is rare in production. Most "federated KG" projects evolve into "centralised KG with imports from many sources, refreshed regularly."

## Tools

- **Apache Jena Fuseki** — SPARQL endpoint, supports federation.
- **Stardog** — graph platform with virtualisation (federation-like access to relational sources).
- **Anzo** — enterprise graph platform with federation features.
- **GraphQL Federation (Apollo, others)** — for graph APIs, not necessarily KGs but related shape.
- **Custom orchestration** — most teams roll their own thin layer over multiple KGs / databases.

The tooling is sparser than the "build your own KG" tooling. Federation is harder; less commodity software exists.

## Failure modes

**Stale entity mappings.** Source A renamed an ID; the registry didn't update; queries miss the entity. Periodic reconciliation jobs.

**Cascading source failures.** Source B is down; coordinator fails open or fails closed? Each option has tradeoffs; explicitly decide.

**Latency unpredictability.** Federated queries depend on the slowest source. P95 / p99 latency is the slowest source's latency; outliers are bad.

**Schema drift across sources.** Source A added a field; consumer doesn't know; queries miss data. Federation requires periodic schema reconciliation.

**Trust boundary leaks.** Federated query exposes entity in source A; source A's access control wasn't enforced. Always enforce ACLs at each source; the federation layer can't substitute.

## A pragmatic recommendation

For most teams considering federation:

1. **Default to centralised.** Even if you must pull from many sources, ETL into one KG is simpler.
2. **Add federation only where centralisation is genuinely impossible** (data sovereignty, organisational boundaries, scale).
3. **Even with federation, accept some staleness.** Cache aggressively.
4. **Invest heavily in entity resolution.** It's the load-bearing problem.
5. **Make conflicts visible.** Don't paper over disagreement; surface it.

Federation is an advanced pattern. Most teams that think they need it would be served by simpler alternatives.

## Further reading

- [KnowledgeGraphCompletion] — building each KG
- [KnowledgeGraphVsRelationalDatabase] — when to use a graph at all
- [EntityResolutionTechniques] — the central problem in federation
- [GraphDatabaseFundamentals] — graph DB tooling
