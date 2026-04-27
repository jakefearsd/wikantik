---
canonical_id: 01KQ0P44Q2FPJEVFC9PGMPG9D1
title: Elasticsearch Fundamentals
type: article
cluster: databases
status: active
date: '2026-04-26'
summary: How Elasticsearch works — indexes, mappings, queries, aggregations — and
  the cases where it's the right tool vs. where Postgres full-text or other options
  fit better.
tags:
- elasticsearch
- search
- databases
- analytics
related:
- FullTextSearchInPostgresql
- CloudDatabases
- ReadReplicasAndReplication
---
# Elasticsearch Fundamentals

Elasticsearch is a search and analytics engine built on Apache Lucene. Distributed; document-oriented; queryable via JSON DSL. The dominant choice for full-text search in modern applications.

This page covers the fundamentals.

## The model

### Documents

Elasticsearch stores JSON documents:

```json
{
    "id": "abc",
    "title": "Hello World",
    "body": "This is the content...",
    "tags": ["intro", "tutorial"],
    "published": "2026-04-26"
}
```

Documents are stored in indexes (collection of documents). An index is roughly equivalent to a database in SQL terms.

### Indexes and shards

Each index is split into shards (for distribution) and replicas (for availability). The cluster distributes shards across nodes.

For small data: 1 shard + 1 replica is fine.
For large data: many shards across many nodes.

### Mappings

Schema for documents in an index. Defines field types: text, keyword, date, number, geo, etc.

Important: text fields are analyzed (tokenized for search); keyword fields are exact-match only.

```json
{
    "title": {"type": "text"},
    "tags": {"type": "keyword"},
    "published": {"type": "date"}
}
```

Get the mapping right; changing it later requires reindexing.

## Queries

Elasticsearch's query DSL is rich. Common patterns:

### Match

```json
{ "match": { "body": "hello world" } }
```

Tokenizes the query; finds documents with the tokens.

### Term

```json
{ "term": { "tags": "tutorial" } }
```

Exact match on keyword field. No tokenization.

### Bool query

```json
{
    "bool": {
        "must": [{ "match": { "body": "hello" } }],
        "filter": [{ "term": { "tags": "tutorial" } }],
        "must_not": [{ "term": { "status": "draft" } }]
    }
}
```

Combine queries. `must` affects scoring; `filter` doesn't.

### Aggregations

Like SQL GROUP BY:

```json
{
    "aggs": {
        "by_tag": {
            "terms": { "field": "tags" }
        }
    }
}
```

Aggregations are powerful: histograms, percentiles, geo-distance, date ranges, etc.

## Common use cases

### Full-text search

The original use case. Fuzzy matching, stemming, relevance scoring.

### Logging and observability

ELK stack: Elasticsearch + Logstash + Kibana. Aggregate logs from many sources; query and visualize.

Note: Elastic licensing changes have led to OpenSearch (AWS fork). For new log workloads, evaluate both.

### Analytics dashboards

Aggregations + Kibana = real-time dashboards.

### Geographic search

Native geo-types and queries. "Find restaurants within 5km of (lat, lon)."

### Autocomplete

Prefix and fuzzy queries support type-ahead UX.

## When Elasticsearch is the right tool

- **True full-text search**: relevance ranking matters
- **Large-scale logs**: terabytes of structured data
- **Complex aggregations**: real-time dashboards
- **Geographic data**: location-based queries
- **High write throughput**: append-mostly workloads

## When it's not

### Transactional workloads

Not ACID; not for primary data. Use a relational database.

### Strong consistency

Eventually consistent. Recent writes may not be queryable for a few seconds.

### Small data

For a few thousand documents, Postgres full-text is much simpler. See [FullTextSearchInPostgresql](FullTextSearchInPostgresql).

### Single source of truth

Use a relational database as primary; index into Elasticsearch for search.

### Joins

Elasticsearch handles parent-child and nested poorly. SQL joins are better in a relational store.

## Operational concerns

### Cluster management

Self-hosted: managing master nodes, data nodes, hot/warm/cold tiers.

Managed: AWS OpenSearch, Elastic Cloud.

For most teams, managed is the right choice.

### Reindexing

Mapping changes require reindexing. The pattern:
1. Create new index with new mapping
2. Reindex from old to new (Elasticsearch supports this)
3. Update aliases to point to new index
4. Delete old index

Plan for this; it happens.

### Capacity planning

Elasticsearch is memory-hungry. JVM heap + OS file cache. Sizing depends on document count, query patterns, retention.

Common rule: keep heap to 50% of RAM, max 32 GB.

### Hot/warm/cold architecture

For logs/time-series: recent data on fast storage; older data on cheap storage.

Reduces cost while preserving searchability.

## Search relevance

Elasticsearch scores results by relevance. The default scoring (BM25) works well; tunable for specific needs:

- Boosts: weight specific fields ("title is more important than body")
- Synonyms: handle alternate spellings
- Stemming: "running" matches "run"
- Stopwords: ignore common words ("the", "a")

Search relevance tuning is its own discipline; iterate based on user behavior.

## Common failure patterns

### Wrong mapping

Wrong types prevent expected queries. Plan mappings up front.

### No backup of indexes

Snapshots to S3 or equivalent. Test restoration.

### Single-node "production"

Single-node cluster has no redundancy. At least 3 nodes for production.

### Underestimating storage

Logs grow fast. Without retention, indexes balloon.

### Heavy JVM heap

OOM kills cluster. Tune heap; monitor.

### Treating it as primary data store

Index into ES from a primary store; don't make ES the source of truth.

## Further Reading

- [FullTextSearchInPostgresql](FullTextSearchInPostgresql) — Lighter-weight alternative
- [CloudDatabases](CloudDatabases) — Database options
- [ReadReplicasAndReplication](ReadReplicasAndReplication) — Adjacent scaling
