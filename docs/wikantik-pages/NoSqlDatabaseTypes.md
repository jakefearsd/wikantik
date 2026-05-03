---
canonical_id: 01KQEKGDDKWH1Q0DX0DA2B3026
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
- DatabasesHub
---
# NoSQL Database Selection: Architectural Fit

Selecting a NoSQL database should be driven by specific access patterns rather than general scalability claims. In 2026, most technical requirements are met by Postgres, with specialized NoSQL families earning their place only when specific latency or throughput thresholds are breached.

## I. NoSQL Families and Sweet Spots

| Family | Implementation | Sweet Spot | Common Regrets |
|---|---|---|---|
| **Document** | MongoDB, Firestore | Fluid schemas; deeply nested data. | Lack of ACID cross-document constraints; schema chaos. |
| **Key-Value** | Redis, DynamoDB | Sub-ms latency; simple lookups. | Restricted query patterns; eventual consistency issues. |
| **Wide-Column** | Cassandra, ScyllaDB | High write volume (>50k/sec); multi-DC. | Operational complexity; rigid partitioning. |
| **Graph** | Neo4j, JanusGraph | Relationship traversals (5+ hops). | Most graph needs are met by relational FKs/CTE. |

---

## II. Document Databases

**Core Pitch**: Store JSON-shaped data with indexing on any field.
*   **Postgres Alternative**: Before adopting MongoDB, evaluate **JSONB in Postgres**. It supports GIN indexing and most document query patterns while maintaining transactional integrity and relational joins.
*   **Adoption Trigger**: Reach for a document store when the domain is fundamentally semi-structured (e.g., content management systems with hundreds of varying attributes) and you require horizontal sharding that exceeds Postgres's managed capacity.

## III. Key-Value and In-Memory

### Redis / Valkey
The dominant in-memory store. Used as a secondary layer for:
*   **Caching**: Accelerating slow queries.
*   **Rate Limiting**: Low-latency counters.
*   **Sorted Sets**: Real-time leaderboards.
*   **Streams**: High-throughput message queuing.
*   *Caveat*: Persistence is not its primary strength. Do not use as the sole source of truth for critical transactional data without careful RDB/AOF tuning.

### DynamoDB
Managed, serverless key-value store.
*   **Trade-off**: Predictable latency and zero ops at the cost of vendor lock-in and rigid data modeling (Single-Table Design).

---

## IV. Wide-Column: High-Throughput Writes

Cassandra and ScyllaDB are designed for linear scalability and multi-datacenter replication.
*   **Write Path**: Optimized via LSM trees and memtables.
*   **Query Constraint**: Every query must align with a partition key. Ad-hoc filtering is computationally expensive or prohibited.
*   **Adoption Trigger**: Sustained write volumes exceeding the capacity of single-node relational instances, or a requirement for multi-region active-active replication.

---

## V. Specialized Stores

*   **Time-Series** (TimescaleDB, InfluxDB): Optimized for timestamped range queries and data retention policies.
*   **Search** (Elasticsearch, Meilisearch): Full-text indexing, scoring, and faceting.
*   **Vector** (pgvector, Qdrant): High-dimensional similarity search for RAG and embeddings.

## VI. The Pragmatic Decision Framework

1.  **Default to Postgres**: It handles documents (JSONB), vectors (pgvector), and full-text search adequately for most scales.
2.  **Add Redis**: For caching, sessions, and real-time counters.
3.  **Adopt Specialists**: Only when a specific bottleneck (write throughput, deep graph traversal, search complexity) is identified and measured.
4.  **Justify Operational Overhead**: Every new database increases the surface area for failure, backup complexity, and engineering cognitive load.
