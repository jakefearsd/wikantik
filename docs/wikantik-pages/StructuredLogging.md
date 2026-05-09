---
cluster: devops-sre
canonical_id: 01KQ0P44X1943YF2DY88JN37EK
title: Structured Logging
type: article
tags:
- logging
- observability
- json
- elasticsearch
- indexing
summary: Advanced architectural patterns for structured logging, focusing on JSON schemas, high-cardinality attribute indexing, and the transition from narrative logs to queryable data assets.
auto-generated: false
date: 2024-05-16
---
# Structured Logging: From Text Streams to Queryable Data

Structured logging is the practice of treating application logs as first-class, typed data rather than arbitrary strings. In modern distributed systems, narrative logs (plain text) are technical debt; they require expensive, brittle regex parsing at search time and fail to provide the correlation required for complex debugging.

## 1. The JSON Paradigm and Schema Authority

The industry standard for structured logging is **JSON Lines (JSONL)**. Each log event is a self-contained JSON object on a single line. This format allows for efficient stream processing by tools like Vector, Fluentd, or Logstash without the need for multi-line buffering.

### 1.1 Standardized Schemas (ECS and Beyond)
Ad-hoc JSON logging leads to "field sprawl" where different services use `user_id`, `uid`, and `user.id` for the same entity. To solve this, organizations must adopt a standardized schema like the **Elastic Common Schema (ECS)**.

**Example ECS-compliant log entry:**
```json
{
  "@timestamp": "2024-05-16T14:30:15.123Z",
  "log.level": "error",
  "message": "database connection timeout",
  "service.name": "order-service",
  "event.dataset": "db.pool",
  "user.id": "u_99823",
  "trace.id": "5318625901235",
  "db.instance": "postgres-primary",
  "error.code": "ETIMEDOUT"
}
```

## 2. High-Cardinality Attribute Indexing

High-cardinality attributes—fields with a vast number of unique values such as `request_id`, `session_token`, or `user_id`—pose a significant challenge for log storage engines.

### 2.1 The Field Explosion Problem
In engines like Elasticsearch or OpenSearch, every unique field name creates a mapping entry. If an application logs dynamic keys (e.g., `{"metadata_key_123": "value"}`), it can trigger a **Mapping Explosion**, crashing the cluster's master node.

**Mandate:** Always use a stable set of keys. Use a nested `labels` or `tags` object for user-defined metadata to prevent top-level field sprawl.

### 2.2 Keyword vs. Text Mapping
For high-cardinality identifiers, the choice of index type is critical:
*   **Keyword:** Used for exact matches, filtering, and aggregations. It treats the value as a single token. This is mandatory for IDs.
*   **Text:** Analyzes the string into multiple tokens. Never use this for IDs, as "uuid-123" becomes "uuid" and "123", breaking exact-match lookups.

### 2.3 Cardinality Management Strategies
1.  **Selective Indexing:** Do not index every high-cardinality field. Use `index: false` for raw payloads that are only needed for display in the UI but never for filtering.
2.  **Sampling:** In high-volume systems (e.g., 100k+ req/sec), sample traces and associated logs.
3.  **Global Unique Identifiers:** Use UUIDv7 or ULIDs. These are lexicographically sortable, which significantly improves B-Tree index performance in relational databases and search engines compared to random UUIDv4.

## 3. The Ingestion Pipeline: Flattening and Enrichment

Logs arriving from the application boundary often require transformation before they are searchable.

### 3.1 Controlled Flattening
Deeply nested JSON structures can be difficult to query. Pipelines should flatten critical fields to a predictable depth.
*   *Nested:* `{"error": {"context": {"id": 123}}}`
*   *Flattened:* `error.context.id: 123`

### 3.2 Dynamic Enrichment
The pipeline (e.g., Logstash or Vector) should enrich logs with data the application may not have:
*   **GeoIP:** Adding `geo.country_name` based on a `client.ip` field.
*   **CMDB Lookup:** Adding `host.environment` (prod/staging) or `host.owner` based on the hostname.

## 4. Operational Best Practices

*   **Correlation IDs:** Every log emitted during a request must include a `trace.id`. This is the single most important factor in multi-service debugging.
*   **No PII in Keys:** Ensure that keys never contain sensitive data. Redact values at the application layer or via regex in the ingestion pipeline.
*   **Index Lifecycle Management (ILM):** Move logs from "Hot" (SSD, high-cost) to "Cold" (Object Storage, low-cost) storage based on age. Most logs lose 90% of their utility after 7 days.

Structured logging transforms logs from a cost center (storage only) into a strategic asset for real-time analytics and incident response.
