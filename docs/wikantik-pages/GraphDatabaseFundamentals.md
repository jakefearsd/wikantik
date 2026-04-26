---
title: Graph Database Fundamentals
type: article
cluster: databases
status: active
date: '2026-04-25'
tags:
- graph-database
- neo4j
- cypher
- janusgraph
- property-graph
summary: Graph databases (Neo4j, JanusGraph, TigerGraph) — the property-graph
  model, Cypher queries, and the cases where graph DBs genuinely beat the
  Postgres-with-graph-schema alternative.
related:
- KnowledgeGraphVsRelationalDatabase
- KnowledgeGraphCompletion
- DatabaseSharding
- NoSqlDatabaseTypes
hubs:
- Databases Hub
---
# Graph Database Fundamentals

A graph database stores nodes (entities) and edges (relationships) and is optimised for traversal queries — "find all nodes reachable from this one within 3 hops." The two main flavours: property graphs (Neo4j-style) and triple stores (RDF-style).

Most teams considering a graph database don't actually need one — Postgres with a graph schema handles 90% of cases. This page is the working set for when you genuinely do, and what to expect.

## The property-graph model

Each node has:
- An identifier (auto-assigned).
- One or more labels (e.g., `:Person`, `:Company`).
- Properties (key-value pairs).

Each edge has:
- A type (e.g., `WORKS_AT`).
- Direction (source → target).
- Optional properties.

```
(p:Person {name:'Dario'}) -[:WORKS_AT {role:'CEO', since:2021}]-> (c:Company {name:'Anthropic'})
```

This is what Neo4j, JanusGraph, TigerGraph, and most modern graph DBs use.

## Cypher: the query language

Neo4j's Cypher has become the de-facto standard for property-graph queries. Pattern-matching syntax:

```cypher
MATCH (p:Person)-[:WORKS_AT]->(c:Company {name:'Anthropic'})
RETURN p.name, p.role
```

Powerful for graph patterns:

```cypher
// All people who work at companies that compete with companies in SF
MATCH (p:Person)-[:WORKS_AT]->(c1:Company)-[:COMPETES_WITH]->(c2:Company {city:'San Francisco'})
RETURN p.name, c1.name, c2.name
```

Variable-length paths:

```cypher
// Find shortest path between two people through any relationship
MATCH path = shortestPath((a:Person {name:'Alice'})-[*..6]-(b:Person {name:'Bob'}))
RETURN path
```

This is where graph DBs shine. The equivalent SQL with recursive CTEs is verbose and slower for deep traversals.

GQL (Graph Query Language) standardised in 2024 is the ISO standard inspired heavily by Cypher. Most graph DBs are converging on GQL or maintaining Cypher compatibility.

## Strengths of graph databases

- **Deep traversals.** 5+ hop queries that would be painful in SQL.
- **Pattern matching.** Multi-hop patterns expressed declaratively.
- **Variable-length paths.** "Within N hops" without manually unrolling.
- **Graph algorithms.** Centrality, community detection, shortest path, max flow built in (Neo4j Graph Data Science library is the most mature).
- **Schema flexibility.** Adding new node types / edge types without migrations (subject to discipline).

## Weaknesses

- **Operational complexity.** Another database to operate; another query language to learn; another set of failure modes.
- **Sharding maturity.** Graph databases sharded poorly historically; partitioning a graph efficiently is a hard problem. Modern systems (TigerGraph, Memgraph) handle this better, but it's still less mature than sharded SQL.
- **Mixed workloads.** Joining graph data with non-graph relational data is awkward; you end up running two databases.
- **Tooling ecosystem.** Less mature than SQL — fewer ORMs, fewer migration tools, fewer dashboards.
- **Cost.** Commercial offerings (Neo4j Enterprise, TigerGraph) are pricey.

## When a graph database wins

Specific cases:

- **Heavy graph algorithms.** Computing centrality, community detection, PageRank on millions of nodes. Graph DBs (Neo4j with GDS) outperform SQL by orders of magnitude.
- **Frequent deep traversals.** Recommendation systems with multi-hop "users like you also liked" queries.
- **Knowledge graphs with rich relationship semantics.** Where every relationship matters and queries traverse many.
- **Fraud detection.** "Is this user connected to a known fraud ring through any path of length ≤ 5?"
- **Network/social analysis.** Finding influencers, communities, shortest paths in social or organisational graphs.

For these, graph DBs are the right tool.

## When a graph database loses

- **The graph is shallow.** Most queries are 1-2 hops. SQL handles this fine.
- **Mixed workloads dominate.** You need transactional CRUD + graph queries; running two DBs is more pain than one Postgres.
- **You don't need graph algorithms.** "We have related data" is not the same as "we need a graph database."
- **Team unfamiliarity.** Cypher / GQL is a real learning curve.

For most teams, Postgres with a `nodes` and `edges` table works. See [KnowledgeGraphVsRelationalDatabase].

## The major options in 2026

### Neo4j

The dominant graph database. Mature; rich ecosystem; excellent Cypher implementation; Graph Data Science library for algorithms.

- **Community Edition** — open source, single-server, no clustering.
- **Enterprise** — clustering, Aura cloud, advanced features. Paid.
- **Aura** (managed) — reasonable cloud offering.

For most graph DB projects: Neo4j is the safe default.

### TigerGraph

High-performance distributed graph DB. Strong on analytical workloads at scale. GSQL query language. Commercial.

For very large graphs (hundreds of millions of nodes) and analytical workloads, TigerGraph competes well.

### JanusGraph

Open-source distributed graph; built on Cassandra / HBase / ScyllaDB for storage. Gremlin query language (TinkerPop standard).

Strengths: very large scale; open source; flexible storage backend.
Weaknesses: complex to operate; Gremlin is harder than Cypher; less polished than Neo4j.

### Memgraph

Newer; Cypher-compatible; in-memory; fast. Open source core; commercial features.

Good for streaming graph analytics; less mature than Neo4j for general use.

### ArangoDB

Multi-model: graph + document + key-value. Useful when you genuinely need multiple shapes; less specialised than dedicated graph DBs.

### Postgres extensions

- **AGE (Apache AGE)** — Cypher-on-Postgres extension. Lets you use Cypher syntax over Postgres. Newer; growing.
- **pg_graph** — similar idea.

These let you avoid running a separate graph DB. For mid-scale graph workloads inside a Postgres-shop, they're worth trying.

## Triple stores (RDF)

A different model: triples (subject-predicate-object) with formal semantics, ontologies (OWL), and SPARQL queries.

Used in:
- Semantic web / linked data.
- Life sciences (UniProt, PubChem).
- Library/cultural-heritage.
- Some enterprise knowledge graphs.

Tools: Apache Jena Fuseki, Stardog, GraphDB, Virtuoso.

For most modern industrial applications, property graphs win on usability. RDF is more powerful for formal reasoning but the property-graph model is simpler.

## Modeling decisions

A few patterns that age well:

### Edge directionality

Edges are directed; queries can traverse either direction. Cypher's `-->` vs `<--` vs `--`. For most relationships, directionality matches the natural reading: `(employee)-[:WORKS_AT]->(company)`.

### Edge properties

Properties on edges represent relationship attributes — `since`, `role`, `weight`, `confidence`. Use them; don't promote every edge property to a separate node.

### Avoid super-nodes

A node with millions of edges (a celebrity in a social graph; "USA" in a "located in" graph) creates query hotspots. Some traversals balloon at super-nodes.

Mitigations: split logically (separate edge types for different relations); index the edge type to filter early; avoid traversing through super-nodes by structure.

### Use labels generously

Multiple labels per node give flexibility:

```
(p:Person:Engineer {name:'Dario'})
```

Queries can match `:Person`, `:Engineer`, or both. More expressive than a single label.

## Operational concerns

- **Backup and restore.** Graph DBs have specific tooling; usually slower than SQL backups at the same scale.
- **Schema evolution.** Adding new node / edge types is generally smooth; renaming or restructuring is painful (similar to relational).
- **Indexing.** Property indexes on labelled nodes; relationship type indexes. Verify queries use them via `EXPLAIN`.
- **Memory.** Graph DBs are memory-hungry. Plan for it.
- **Clustering / HA.** Single-master in many graph DBs; replicas for read scaling. Real high-availability requires Enterprise (Neo4j) or specific configuration.

## A pragmatic choice

For a new project considering a graph DB:

1. **Sketch your queries.** Write out the 5-10 most common.
2. **Try them in Postgres** with a graph schema. Are they ugly?
3. **Try them in Cypher.** Materially better?
4. **Estimate scale and depth.** Below ~10M nodes / 50M edges, Postgres handles fine. Above, graph DBs scale better.
5. **Consider the team.** Operational and learning curve are real.

Most teams should start with Postgres + graph schema. Migrate to a dedicated graph DB if and when specific limits force it.

## Further reading

- [KnowledgeGraphVsRelationalDatabase] — when to reach for graph at all
- [KnowledgeGraphCompletion] — building / extending the graph
- [DatabaseSharding] — graph DBs and sharding
- [NoSqlDatabaseTypes] — broader NoSQL context
