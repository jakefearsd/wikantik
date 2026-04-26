---
title: NoSql Database Types
type: article
cluster: databases
status: active
date: '2026-04-25'
tags:
- nosql
- mongodb
- cassandra
- redis
- dynamodb
- key-value
summary: The four NoSQL families (document, key-value, wide-column, graph) and
  what each is genuinely good at vs the cases where Postgres handles the same
  job with less operational complexity.
related:
- DatabaseDesign
- RedisPatterns
- GraphDatabaseFundamentals
- DatabaseSharding
hubs:
- Databases Hub
---
# NoSQL Database Types

NoSQL is a category that grouped several different things sharing only "not relational." Each family has different strengths, different trade-offs, different cases where it earns its keep. Picking by buzzword ("we need NoSQL because we're scalable") almost always produces regret; picking by fit ("our access pattern is X; this DB optimises for that") is the working approach.

## The four families

| Family | Examples | Sweet spot | Often regretted because |
|---|---|---|---|
| **Document** | MongoDB, Couchbase, Firestore | Schema-flexible content; nested data | Lacks transactional guarantees you needed; schema flexibility becomes schema chaos |
| **Key-value** | Redis, DynamoDB, Memcached, etcd | Simple lookups at very high throughput | Too simple for queries you eventually need |
| **Wide-column** | Cassandra, HBase, ScyllaDB, Bigtable | Time-series; very high write throughput; tunable consistency | Operational complexity outweighs benefit at small scale |
| **Graph** | Neo4j, JanusGraph, TigerGraph | Relationship-heavy data; deep traversals | Most "we need a graph" cases work in Postgres |

In 2026, **most teams should default to Postgres** and reach for NoSQL only when there's a specific reason. Postgres has absorbed many NoSQL features (JSONB for documents; pgvector; range types; ltree). The NoSQL family that still consistently wins is key-value (Redis), and it usually wins as a *cache*, not a primary store.

## Document databases

The pitch: "store JSON-shaped data; query by any field; flexible schema."

The reality: good for some things; less good than promised at others.

### MongoDB

Most popular. Dynamic schemas; rich query language (aggregations, joins, indexes); ACID transactions added in 2018.

Strengths:
- Schema-flexible — useful for varied content types.
- Aggregation framework is genuinely powerful.
- Geo and full-text built in.
- Horizontal scaling via sharding.

Weaknesses:
- Schema flexibility is also a footgun — ad-hoc fields proliferate; consumers can't depend on shape.
- Operational complexity at scale (replica sets, shards, write concerns).
- The transaction story improved but is still less mature than Postgres.
- Performance has surprised teams negatively at certain scales / access patterns.

Use when: schema genuinely is fluid and useful (event stores, content management, varied object types). Don't use when: schema is stable and you'd be happier with Postgres + JSONB columns.

### Postgres + JSONB as alternative

For most "we need flexible document storage" cases:

```sql
CREATE TABLE events (
    id BIGSERIAL PRIMARY KEY,
    event_type TEXT NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX ON events USING GIN (payload);
CREATE INDEX ON events ((payload->>'user_id'));
```

JSONB queries support most MongoDB query patterns. You also get joins, transactions, the SQL ecosystem, and don't run a second database. See [JsonbInPostgresql].

For most teams: prefer this over MongoDB unless you have specific MongoDB-shaped needs.

## Key-value databases

The simplest NoSQL: `set(key, value)`, `get(key)`. High throughput, sub-millisecond latency.

### Redis (and Valkey)

The dominant key-value store; in-memory; rich data types beyond plain key-value (lists, sets, sorted sets, hashes, streams, geo, bitmap, hyperloglog).

Use cases (see [RedisPatterns]):
- Caching (the dominant use).
- Session storage.
- Rate limiting.
- Leaderboards (sorted sets).
- Pub/sub.
- Job queues.
- Real-time analytics.

Caveat: not durable like Postgres. Default config can lose minutes of data on crash. Persistence options exist; tune carefully if Redis is your source of truth.

### DynamoDB

AWS-managed key-value with secondary indexes. Pay per request. Hyper-scalable.

Strengths:
- Fully managed; no operations.
- Single-digit-millisecond latency at any scale.
- Seamless scaling; auto-scaling; multi-region.

Weaknesses:
- Vendor lock-in.
- Query patterns must be designed up front; not flexible.
- Cost can spike unexpectedly.
- Counts as a key-value store but tries to be more (single-table design with composite keys); the data modelling has a learning curve.

Use when: AWS-only; predictable access patterns; simple-ish data; you'd rather pay than operate.

### Memcached

Older; simpler; cache-only. Mostly superseded by Redis for new deployments. Persists in some legacy systems where it works.

### etcd / Consul / ZooKeeper

Distributed key-value with consensus. Used for cluster metadata, service discovery, distributed locks, configuration. Not a general-purpose database; very specific use case.

## Wide-column databases

Data modelled as rows with sparse columns, often per-row column families. Storage and queries optimised for writes-then-rare-reads of related columns.

### Cassandra (and ScyllaDB)

The canonical wide-column. Distributed by default; tunable consistency; massive write throughput.

Strengths:
- Linear horizontal scalability.
- Multi-datacenter replication first-class.
- Tunable consistency (per-query R, W).
- Write-optimised (LSM trees + memtables).

Weaknesses:
- Operations are real work — many knobs; many failure modes.
- Query model is restrictive — every query needs a matching primary key / partition key.
- Sparse-column model is unfamiliar; data modelling has a steep curve.
- ScyllaDB is API-compatible but reimplemented in C++ for better performance.

Use when: very high write volume (>100k/sec); time-series; wide-area replication; query patterns known up front. Don't use when: query patterns evolve frequently; ad-hoc queries common.

### Bigtable / HBase

Google Bigtable (managed) / HBase (open-source equivalent). Similar shape to Cassandra; tighter integration with the rest of Google's stack (Bigtable) or Hadoop (HBase).

Use cases similar to Cassandra; less common in non-Google / non-Hadoop shops.

## Graph databases

Stores nodes and edges; queries traverse relationships. See [GraphDatabaseFundamentals], [KnowledgeGraphVsRelationalDatabase].

For most "we have related data" cases, foreign keys in Postgres do the job. Graph databases are right when:
- Traversals are deep (5+ hops).
- Relationships are the primary thing being queried.
- Graph algorithms (centrality, community detection) are needed.

Otherwise, Postgres + a graph schema works.

## Specialised: time-series, search, vector

Adjacent to NoSQL but each its own category:

- **Time-series**: InfluxDB, TimescaleDB (Postgres extension), QuestDB. Optimised for time-stamped writes, range queries.
- **Search**: Elasticsearch, OpenSearch, Meilisearch. Optimised for full-text, scoring, faceted search.
- **Vector**: Pinecone, Qdrant, Milvus, pgvector. Optimised for similarity search.

Each is a NoSQL-flavoured database with a specific shape. Use when the shape fits.

## When NoSQL is the right call

Honest cases where Postgres-everywhere is wrong:

- **Genuine high-write scale** (>50k writes/sec sustained on a single table). Cassandra, ScyllaDB.
- **Single-digit-millisecond latency at unbounded scale.** DynamoDB managed.
- **Real-time caching / counters / leaderboards.** Redis.
- **Cluster coordination.** etcd / Consul.
- **Search at scale.** Elasticsearch (or pgsearch / paradedb up to a point).
- **Vector search at extreme scale.** Qdrant or specialised, beyond pgvector's comfort zone.

These are real cases. They're not the majority of cases.

## When NoSQL is the wrong call

Common over-adoptions:

- **"We chose MongoDB for flexibility."** Most schemas converge over time; flexibility becomes inconsistency. Postgres + JSONB gives you flexibility with the option of constraints.
- **"We chose Cassandra for scalability."** Until you actually need Cassandra-scale, you're paying operational cost for benefits you don't use.
- **"We chose DynamoDB to avoid operations."** Cost surprises are real; lock-in is real; the operations savings are smaller than the marketing suggests.
- **"We chose Neo4j for our knowledge graph."** Most KGs work in Postgres until they don't.

## A pragmatic decision

For a new project:

1. **Default to Postgres.** Includes JSONB, pgvector, full-text, range types.
2. **Add Redis for caching / sessions / queues** when the workload calls for it.
3. **Add a specialised database** when Postgres's limits become specific bottlenecks. Document which limits; benchmark before adopting.
4. **Justify each new database** with a concrete reason that wouldn't be solved by tuning Postgres.

Most production stacks in 2026 are Postgres + Redis + maybe one or two specialists. Architecture diagrams with five databases are usually mistakes.

## Further reading

- [DatabaseDesign] — relational design starting point
- [RedisPatterns] — when key-value wins
- [GraphDatabaseFundamentals] — graph DBs in depth
- [DatabaseSharding] — horizontal scaling concerns
