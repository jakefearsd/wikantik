---
canonical_id: 01KQ0P44QS3AFFX90J5XXAABJA
title: Graph RAG
type: article
cluster: generative-ai
status: active
date: '2026-04-26'
summary: Graph-augmented retrieval for LLMs — combining knowledge graphs with vector
  search to get answers that span multiple documents and capture relationships,
  with practical guidance on when graph approaches add value.
tags:
- rag
- knowledge-graph
- generative-ai
- retrieval
- llm
related:
- AgentPromptEngineering
- TransformerArchitecture
hubs:
- GenerativeAIHub
---
# Graph RAG

Graph RAG augments retrieval-augmented generation with structured knowledge graph traversal. The promise: questions whose answers span multiple documents can be answered by following graph relationships, not just by vector similarity.

This page covers what graph RAG is, when it helps, and the costs.

## Standard RAG, briefly

1. Embed query
2. Find similar passages from a vector index
3. Pass passages + query to LLM
4. LLM generates answer

Works well when:
- Answer exists in one passage
- Top-k retrieval surfaces it

Fails when:
- Answer requires combining passages
- Implicit reasoning across documents needed
- Specific entities or relationships need to be tracked

## What graph RAG adds

Build a knowledge graph from your corpus:
- Entities (nodes)
- Relationships (edges)
- Entity properties

Graph queries can:
- Traverse relationships (find papers citing X cited by Y)
- Aggregate (count, list)
- Constrain by entity type or property

For multi-hop questions, graph traversal can find answers vector retrieval misses.

## Architectures

### Naive: graph-only retrieval

Extract entities from query; traverse graph; pass results to LLM.

Issues: brittle to entity extraction errors; misses passages without entities.

### Hybrid: vector + graph

Vector retrieval for breadth; graph for relationships.

Combine results before LLM call.

### Iterative: agent with graph tool

LLM agent uses graph queries as a tool. Decides when to traverse vs read.

Most flexible; highest cost.

### Microsoft GraphRAG

Build hierarchical community summaries from graph. Use community summaries for global questions.

Specifically targets "what are the major themes" type queries.

## Building the graph

### Entity extraction

Identify entities mentioned in documents.

Tools:
- spaCy NER
- LLM-based extraction (more flexible)
- Custom domain models

### Relationship extraction

Identify relationships between entities.

LLMs are good at this with appropriate prompts.

### Schema

Two extremes:
- **Schema-free**: any entity, any relationship. Flexible but messy.
- **Strongly typed**: predefined entity and relationship types. Cleaner but limited.

Most production systems land in the middle.

### Storage

- Property graph: Neo4j, Amazon Neptune
- Triple store: Apache Jena, Stardog
- General databases: PostgreSQL with graph extensions
- Specialized: TigerGraph, ArangoDB

Choice depends on query patterns and existing infrastructure.

## Querying the graph

### Direct graph queries

Cypher (Neo4j), SPARQL (RDF), Gremlin (TinkerPop).

Powerful but require schema knowledge.

### Natural language to graph query

LLM translates question to graph query language.

Quality depends on schema clarity and example coverage.

### Path queries

"Find paths from X to Y." Useful for knowledge exploration.

### Graph algorithms

PageRank for importance, community detection for clusters, shortest path for relationships.

## When graph RAG helps

### Genuinely relational questions

"Who collaborates with researchers at company X?"
"What are the consequences of decision Y?"

Vector retrieval misses these.

### Disambiguation

Multiple entities with same name. Graph context resolves.

### Aggregation

"How many papers cite this work in the last year?"

### Provenance / lineage

"Where did this claim originate?"

### Multi-hop reasoning

Connecting facts across documents.

## When standard RAG is enough

Most question-answering doesn't need graphs:
- Single-document answers
- "What does X mean" / definitions
- Procedural questions
- Most enterprise FAQ use cases

For these, graph RAG adds complexity without quality.

## Costs

### Construction

Building a knowledge graph from documents:
- Significant LLM costs (entity + relationship extraction)
- Iteration on schema
- Quality issues to fix

Often as much work as the rest of the system.

### Maintenance

- New documents → new entities and relationships
- Entity resolution (X mentioned in different docs)
- Schema evolution
- Quality drift over time

### Query latency

Graph queries can be slow for complex traversals.

### Engineering complexity

Now you have two retrieval systems to maintain.

## Practical patterns

### Start without graph

Build standard RAG. Measure quality.

If quality is good enough, you don't need graph.

### Add graph for specific queries

Identify question types where standard RAG fails.

Build graph features specifically for those.

### Hybrid retrieval

For each query, do both vector retrieval and graph queries; combine.

Often the best balance.

### Document the graph

Schema documentation matters. LLMs querying the graph need it.

## Microsoft GraphRAG specifically

Anthropic's open-source GraphRAG builds:
- Entity-relationship graph
- Hierarchical community detection
- Community summaries at multiple levels

For "global" questions about a corpus:
- Generate answer from community summaries
- Reduce summaries to single answer

For "local" questions:
- Standard graph + vector retrieval

Effective for "summarize the main themes in this corpus" use cases.

## Evaluation

Hard. Standard RAG evaluation (precision/recall on retrieved passages) doesn't capture graph value.

### Approaches

- Question typology: easy / multi-hop / aggregation
- Answer correctness on multi-hop questions
- Latency / cost comparison
- Coverage of graph relationships

Curate eval sets that test relational reasoning.

## Common failure patterns

### Graph quality issues

Bad entity extraction → useless graph.

### Schema rigidity

Schema doesn't match evolving content.

### Over-engineering

Graph RAG when standard RAG would suffice.

### LLM-generated queries fail silently

Query returns empty; agent makes up answer.

### Update lag

Graph stale relative to documents.

### Hallucinated entities

LLM extraction creates entities that aren't in source.

## Decision framework

Build graph RAG when:
- Standard RAG measurably fails on important question types
- Your domain has clear entity/relationship structure
- You can invest in graph maintenance
- Multi-hop reasoning is core to use cases

Skip graph RAG when:
- Standard RAG works
- Domain is unstructured
- Maintenance cost outweighs benefit
- Team can't support two retrieval systems

## Future direction

Graph RAG is evolving:
- Better automated schema discovery
- LLM-native graph reasoning
- Better evaluation methodologies
- Tooling maturing (LangChain, LlamaIndex graph integrations)

It's promising for complex domains; not always needed.

## Further Reading

- [AgentPromptEngineering](AgentPromptEngineering) — Agent patterns
- [TransformerArchitecture](TransformerArchitecture) — LLM foundation
- [Generative AI Hub](GenerativeAIHub) — Cluster index
