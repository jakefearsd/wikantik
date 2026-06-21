---
title: 'Graph Database Fundamentals: Property Graphs, Cypher & Use Cases'
tags:
- graph-database
- neo4j
- cypher
- janusgraph
- property-graph
summary: 'Graph database fundamentals: the property-graph model, Cypher queries, Neo4j
  and the alternatives, and when a graph database genuinely beats Postgres.'
related:
- KnowledgeGraphVsRelationalDatabase
- KnowledgeGraphCompletion
- DatabaseSharding
- NoSqlDatabaseTypes
canonical_id: 01KQEKGDB4WDQQX7D2W6Z994KQ
type: article
status: active
hubs:
- DatabasesHub
cluster: databases
date: '2026-04-25'
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

## When a graph database wins — use cases in depth

The useful test is **not** "is my data connected?" — all data is connected. It's: *do my most valuable queries follow chains of relationships whose depth or shape I don't know in advance?* When the answer is yes, a graph database stops being a luxury and starts paying for itself. The domains below are the ones where that reliably happens. For each, note the **killer query** — the one that is natural in Cypher and miserable in SQL.

### Fraud rings & anti-money-laundering (AML)

Money laundering and organised fraud hide in *paths*, not rows: shell accounts, shared devices, shared addresses, and chains of transfers that connect a new account back to a known-bad one.

- **Killer query:** "Is this account linked to any flagged account within 6 hops of transfers, shared devices, or shared addresses?"
- **Why graph:** ring/cycle detection and variable-depth reachability are native operations. The SQL equivalent is a recursive-CTE that degrades sharply as depth grows.

```cypher
MATCH path = (a:Account {flagged:true})
             -[:TRANSFER|SHARES_DEVICE|SHARES_ADDRESS*1..6]-
             (b:Account {id:$newAccount})
RETURN path LIMIT 1
```

### Recommendation & personalization engines

"Customers like you also bought…" is a multi-hop traversal over a bipartite user–item graph, computed live from a seed node rather than from a pre-baked table.

- **Killer query:** "Find products bought by people who bought what I bought, that I haven't bought yet."
- **Why graph:** collaborative-filtering patterns are a two-hop traversal; new interactions are reflected immediately with no batch recompute.

```cypher
MATCH (me:User {id:$u})-[:BOUGHT]->(:Product)<-[:BOUGHT]-(peer:User)-[:BOUGHT]->(rec:Product)
WHERE NOT (me)-[:BOUGHT]->(rec)
RETURN rec.name, count(*) AS score
ORDER BY score DESC LIMIT 10
```

### Identity, permissions & access control (RBAC / ReBAC)

Authorization is a reachability question: *can this principal reach this resource through any chain of group memberships, roles, and grants?* This is exactly the model behind relationship-based systems like Google Zanzibar.

- **Killer query:** "Can user X access resource Y through any path of memberships and grants?"
- **Why graph:** org structures nest to arbitrary, irregular depth; the traversal is the permission check.

```cypher
MATCH (u:User {id:$u})-[:MEMBER_OF|HAS_ROLE|GRANTS*1..8]->(r:Resource {id:$y})
RETURN count(*) > 0 AS allowed
```

### Dependency & impact analysis (infrastructure, microservices, software)

Telecom networks, microservice meshes, IT asset inventories, and software dependency trees all ask the same thing when something breaks.

- **Killer query:** "If this switch / service / library fails, what downstream is affected?"
- **Why graph:** blast radius is a transitive closure over a directed graph of unbounded depth — and the reverse traversal answers "what does this depend on?" for free.

```cypher
MATCH (svc:Service {id:$s})<-[:DEPENDS_ON*1..]-(affected)
RETURN DISTINCT affected.name
```

### Supply chain & bill-of-materials (BOM)

Manufacturing data is inherently multi-tier: components roll up into sub-assemblies into finished goods, across many supplier levels.

- **Killer query:** "Which finished products contain a part from this recalled supplier, at *any* tier?"
- **Why graph:** the explode/implode of a BOM is variable-depth traversal; tiers are not fixed in advance.

### Master data, entity resolution & customer-360

Records describing the same real-world person, company, or device arrive from many systems and must be linked into one view.

- **Killer query:** "Show every account, device, policy, and household connected to this individual."
- **Why graph:** entity resolution is link analysis across heterogeneous sources — connections are the product, not a side effect.

### Social & organizational network analysis

Influence, communities, and "degrees of separation" are graph-algorithmic by definition.

- **Killer query:** "Who are the most central people in this network, and what communities do they bridge?"
- **Why graph:** centrality, community detection, and shortest-path ship as built-in algorithms (e.g., Neo4j Graph Data Science).

### Knowledge graphs & GraphRAG

Entities plus typed relationships power semantic search and retrieval-augmented generation for LLMs, where traversing relationships expands the relevant context.

- **Why graph:** relationship *semantics* are first-class, and a traversal from a matched entity gathers the neighbourhood an answer needs. See [Knowledge Graph vs. Relational Database](KnowledgeGraphVsRelationalDatabase) and [Knowledge Graph Completion](KnowledgeGraphCompletion).

### Routing, logistics & network flow (with a caveat)

Shortest/cheapest path and max-flow are classic graph problems, and graph DBs handle them well.

- **Caveat:** if heavy route *optimization* is your **only** workload, a dedicated routing engine or operations-research solver often beats a graph DBMS. Reach for a graph database when routing is *one of several* graph queries over the same connected data.

> **The common thread:** every case above is variable-depth reachability, pathfinding, or a graph algorithm — not a fixed-shape report. If your headline queries fit that description, a graph database earns its place.

## When NOT to choose a graph database

"We have relationships" is not a reason — every relational schema has foreign keys. The honest question is whether *traversal* is your bottleneck. Where it isn't, another store is simpler, cheaper, and faster. Each anti-pattern below names the tool that actually fits.

- **Shallow, fixed-shape joins (1–2 hops).** Order → Customer → Address. → **Relational (Postgres/MySQL).** Indexed joins are faster and far simpler to operate.
- **Aggregation / OLAP analytics.** "Sum revenue by region by month across 2B rows." → **Columnar warehouse** (ClickHouse, BigQuery, Snowflake, DuckDB). Graph DBs are weak at large scans and group-bys.
- **Bounded-depth hierarchies & trees.** Category trees, org charts, threaded comments. → **SQL adjacency list + closure table**, or Postgres `ltree`. A graph DBMS is overkill when depth is small and known.
- **Document- or blob-centric data.** JSON documents, content, events with few cross-links. → **Document DB** (MongoDB) or **Postgres JSONB**.
- **Simple key-value / cache / session state.** → **Redis** or another KV store.
- **Time-series & metrics.** Sensor, telemetry, market ticks. → **Time-series DB** (TimescaleDB, InfluxDB, Prometheus).
- **Full-text relevance search is the point.** → **Search engine** (Elasticsearch / OpenSearch, Lucene).
- **High-throughput OLTP where relationships are incidental.** Payments ledger, inventory. → **Relational / NewSQL** — you need ACID throughput, not traversal.
- **Huge scale needing mature sharding + multi-region ACID.** → **Distributed SQL / NewSQL** (CockroachDB, Spanner, Vitess). Graph partitioning is still less mature — see [Database Sharding](DatabaseSharding).
- **One-off graph analytics on a static snapshot.** A single centrality or community run for a report. → **A graph-compute library** (NetworkX, igraph, Spark GraphX, cuGraph) — no need to stand up and operate a graph DBMS.

**Rule of thumb:** if you can't name a query that traverses a *variable or unknown* number of hops, you probably don't need a graph database.

## A 60-second litmus test

Score your project. The more rows you land on the left, the more a graph database earns its keep.

| Lean **graph database** | Lean **another store** |
|---|---|
| Headline queries traverse 3+ hops, often variable-length | Almost every query is 1–2 hops |
| You need pathfinding, shortest-path, or cycle detection | You mostly need `GROUP BY` / `SUM` over big tables |
| Relationships carry properties you actually query | Relationships are just foreign keys you join on |
| You'll run graph algorithms (PageRank, community, centrality) | You need full-text search or time-series rollups |
| The shape of connections is irregular and evolving | The schema is tabular and stable |
| Connected-data queries are your competitive core | Connected data is incidental to a mostly-CRUD app |

**Verdict:** 4+ ticks on the left and a graph database likely pays off. Mostly right-hand ticks — start with Postgres (a `nodes`/`edges` schema, or the Apache AGE extension for Cypher syntax) and migrate only if a concrete query later forces the move.

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

## Frequently Asked Questions

**What is a graph database?**
A database that stores data as nodes (entities) and edges (relationships) and is optimized for traversal queries — following relationships across many hops — rather than the row/table joins of a relational database.

**Graph database vs. relational database — what's the difference?**
Relational databases excel at structured, tabular data and set-based queries; graph databases excel at deeply connected data and multi-hop traversals where SQL joins become slow and verbose. See [Knowledge Graph vs. Relational Database](KnowledgeGraphVsRelationalDatabase).

**When should I use a graph database?**
When you run frequent deep traversals (5+ hops), need built-in graph algorithms (PageRank, community detection, shortest path), or model richly connected domains like fraud rings, recommendations, and social networks. For shallow 1–2 hop queries, Postgres is usually enough.

**What are some real-world graph database use cases?**
Fraud-ring and anti-money-laundering detection, recommendation engines, identity and permission (reachability) checks, network and dependency/impact analysis, supply-chain and bill-of-materials roll-ups, master-data and entity resolution, social-network analysis, and knowledge graphs / GraphRAG. The common thread is queries that follow chains of relationships of variable or unknown depth.

**When is a graph database overkill — when should I NOT use one?**
When your queries are shallow (1–2 hops), aggregation-heavy (use a columnar warehouse), document- or key-value-shaped, time-series, or search-centric — or when relationships are merely foreign keys you join on. If you can't name a variable-depth traversal query, skip the graph database.

**Can I run graph queries in Postgres instead?**
Yes. A `nodes`/`edges` schema with recursive CTEs covers shallow-to-moderate graphs, and the Apache AGE extension adds Cypher syntax on top of Postgres. Most teams should start here and only migrate to a dedicated graph DB when a real query or scale limit forces it.

**What is the most popular graph database?**
Neo4j is the dominant property-graph database and the safe default for most projects; TigerGraph, JanusGraph, Memgraph, and ArangoDB serve more specialized needs.

**What query language do graph databases use?**
Cypher (Neo4j and compatibles) is the de-facto standard; the ISO **GQL** standard (2024) is heavily inspired by it. JanusGraph uses Gremlin; TigerGraph uses GSQL.

## Further reading

- [Knowledge Graph vs. Relational Database](KnowledgeGraphVsRelationalDatabase) — when to reach for graph at all
- [Knowledge Graph Completion](KnowledgeGraphCompletion) — building / extending the graph
- [Database Sharding](DatabaseSharding) — graph DBs and sharding
- [NoSQL Database Types](NoSqlDatabaseTypes) — broader NoSQL context
