---
canonical_id: 01KQ0P44TYETZXD312AGPK59ZE
title: Property Graph Model
type: article
cluster: databases
status: active
date: '2026-04-26'
summary: How property graphs work — Neo4j, Neptune, JanusGraph — the cases where
  graph databases are the right tool, and where SQL with joins is actually fine.
tags:
- graph-database
- neo4j
- neptune
- cypher
- databases
related:
- CloudDatabases
- ElasticsearchFundamentals
---
# Property Graph Model

Property graph: data modeled as nodes (entities) and edges (relationships), each with properties. Relationships are first-class; queries traverse them.

Graph databases (Neo4j, Neptune, JanusGraph) implement this. The use cases are real but narrower than the marketing implies.

This page covers when graph databases fit and when SQL is fine.

## The model

```
Node (User: alice, age=30)
  -[:FOLLOWS, since=2024]→
Node (User: bob, age=35)
```

Properties on both nodes and edges. Queries can traverse relationships:

```cypher
MATCH (alice:User {name: "Alice"})-[:FOLLOWS*1..3]->(other)
RETURN other
```

"Find users Alice follows directly or up to 3 hops away."

The Cypher query language (Neo4j) is widely used. SPARQL for RDF graphs.

## When graph databases fit

### Genuinely graph-shaped problems

Social networks (friend-of-friend queries), recommendation engines, fraud detection (suspicious networks), knowledge graphs.

Genuinely meaning: queries traverse relationships in non-trivial ways. Multiple hops; pattern matching; shortest paths.

### Variable-depth queries

"Find all dependencies up to 5 levels deep" is awkward in SQL (recursive CTE) and natural in graph databases.

### Pattern matching

"Find triangles where A knows B knows C and A doesn't know C" — natural in Cypher; complex in SQL.

### Schema flexibility

Different node/edge types can have different properties. Less rigid than relational schemas.

## When SQL is fine

### Hierarchies (one-to-many)

Org charts, category trees, file systems. Can be done in SQL (recursive CTE, materialized path, nested sets, closure tables). Not always elegant but works.

### Simple foreign keys

If "relationships" are mostly 1:N or N:M with foreign keys, SQL is the natural fit.

### Read-heavy with simple joins

If you join two tables, that's not a graph query. SQL handles it well.

### Analytical workloads

Aggregations, group-by, reporting. SQL's bread and butter.

### Most CRUD apps

The "everything is connected so we should use a graph DB" instinct is usually wrong. Most CRUD apps are fine in SQL.

## The major implementations

### Neo4j

The dominant graph database. Cypher query language; ACID; clustering.

Strengths: mature; widely supported; good tooling.
Weaknesses: licensing for enterprise features; performance ceiling.

### Amazon Neptune

AWS-managed. Supports both property graphs (Gremlin) and RDF (SPARQL).

Strengths: managed; integrates with AWS.
Weaknesses: AWS-only; less feature-rich than Neo4j.

### JanusGraph

Open-source distributed graph database. Backed by Cassandra or HBase.

For very large graphs.

### Postgres + extensions

PostgreSQL with recursive CTE handles many graph queries reasonably. Apache AGE extension adds graph capabilities.

For most use cases that aren't deeply graph-natured, this is sufficient.

## Specific patterns

### Recommendations

"Users similar to user X also liked these products."

Graph traversal: from user X, find similar users (similar interests/connections), find their preferences, aggregate.

### Fraud detection

Suspicious patterns: "this account is connected to known-fraud accounts within 3 hops."

Graph queries find patterns SQL would struggle with.

### Knowledge graphs

Entities and their relationships: companies, people, products, events. Questions like "who's connected to this entity through which path?"

### Social networks

Friend-of-friend, "people you may know," shortest path between users.

### Dependency analysis

"What depends on this service, transitively?" Common in microservices, code-base analysis.

## Implementation considerations

### Cypher vs. Gremlin vs. SPARQL

Different query languages for different graph databases. Not interchangeable.

Cypher (Neo4j) is the most popular; Gremlin (Neptune, JanusGraph) is the multi-vendor standard; SPARQL is for RDF.

For new projects, Cypher tends to be most accessible.

### Modeling

The core decision: what's a node, what's an edge?

Rule of thumb: entities are nodes; verbs/relationships are edges. "User Alice follows User Bob" — both users are nodes; FOLLOWS is an edge.

Avoid making everything a node ("user_follows" as a node) — defeats the graph model.

### Performance

Graph databases are fast for graph queries. They're slower than SQL for non-graph queries.

Don't put data that doesn't need graph features into a graph DB.

### Polyglot persistence

Use the right tool for each part. Graph DB for graph queries; relational for relational; document for document.

For most apps, SQL is the primary store; specialized DBs for specific needs.

## Common failure patterns

- **Graph DB for non-graph data.** Wrong tool; performance and complexity.
- **Modeling everything as nodes.** Defeats the graph model.
- **Ignoring schema.** Schema-flexibility doesn't mean schemaless; data needs structure.
- **Heavy aggregations on graph DB.** Better in SQL or analytical DB.
- **Underestimating learning curve.** Cypher/Gremlin are different mindsets.

## A reasonable position

For most apps: stay in SQL.

For genuinely graph-shaped problems: introduce a graph DB for that part. Keep transactional data in SQL.

For the "is this graph-shaped?" question: if your queries are joins between known tables, it's relational. If they traverse variable-depth relationships in pattern-matching ways, it's graph.

Most "graph database" pitches don't actually require a graph database.

## Further Reading

- [CloudDatabases](CloudDatabases) — Database options
- [ElasticsearchFundamentals](ElasticsearchFundamentals) — Adjacent specialized DB
