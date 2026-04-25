---
canonical_id: 01KQ12YDW7FX23NRJ4T83SQ6PV
title: Postgresql Advanced Features
type: article
cluster: databases
status: active
date: '2026-04-25'
tags:
- postgresql
- jsonb
- ltree
- listen-notify
- pgvector
- ctes
summary: The Postgres features that turn it from "a database" into "the only
  database we need" — JSONB, LISTEN/NOTIFY, range types, CTEs, window functions,
  pgvector, partitioning, and the reasons not to leave the boundary.
related:
- DatabaseDesign
- DatabaseIndexingStrategies
- JsonbInPostgresql
- VectorDatabases
- DatabasePartitioning
hubs:
- Databases Hub
---
# PostgreSQL Advanced Features

Postgres has been quietly absorbing features for thirty years. In 2026 it's defensible to argue you should reach for non-Postgres datastores only when you have specific reasons, because Postgres can do most of what those datastores do — well enough that the operational simplicity of one system wins.

This page is the features that surprise people, ranked by how often they replace another tool.

## JSONB

JSONB stores binary-encoded JSON with index support and a query language. Most "we need a document database" use cases are served by JSONB without leaving Postgres.

```sql
CREATE TABLE events (
    id BIGSERIAL PRIMARY KEY,
    occurred_at TIMESTAMPTZ NOT NULL,
    payload JSONB NOT NULL
);

-- Path queries
SELECT * FROM events WHERE payload @> '{"type": "click", "user_id": 42}';
SELECT * FROM events WHERE payload->>'session_id' = 'abc-123';

-- GIN index on the whole JSONB (large but flexible)
CREATE INDEX idx_events_payload ON events USING GIN (payload);

-- GIN index for containment queries only (smaller)
CREATE INDEX idx_events_payload ON events USING GIN (payload jsonb_path_ops);

-- Functional index for a specific path
CREATE INDEX idx_events_user ON events ((payload->>'user_id'));
```

When to use:

- **Schema-flexible event logs** where each event type has different fields.
- **Document-like data** that's queried mostly by path.
- **Mostly-structured data with a few flexible fields** — most columns are typed; one `metadata` JSONB.

When not to use:

- Data with a stable schema. Use real columns. JSONB is slower, harder to constrain, and harder to query.
- High-frequency updates of nested fields. JSONB is rewritten as a whole; partial updates are expensive.

See [JsonbInPostgresql] for depth.

## LISTEN / NOTIFY

Lightweight pub-sub built into Postgres. Producers send notifications:

```sql
SELECT pg_notify('order_events', '{"id": 42, "status": "shipped"}');
```

Subscribers listen:

```sql
LISTEN order_events;
-- Connection now receives async notifications
```

Use cases:

- Cache invalidation across application instances.
- Real-time updates for SPAs (combined with a WebSocket bridge).
- Triggering background work without a separate queue.

Limits:

- Payload max 8000 bytes.
- No persistence — if no listener is connected, the notification is lost.
- Not a replacement for Kafka or RabbitMQ for durable messaging.

For "tell other instances something changed" within a single application, LISTEN/NOTIFY is dramatically simpler than running a message broker.

## pgvector

Native vector search via the `pgvector` extension. Adds `VECTOR(n)` type, IVFFlat and HNSW indexes, and operators (`<->` for L2, `<#>` for negative inner product, `<=>` for cosine).

```sql
CREATE EXTENSION vector;

CREATE TABLE chunks (
    id BIGSERIAL PRIMARY KEY,
    document_id BIGINT REFERENCES documents(id),
    content TEXT NOT NULL,
    embedding VECTOR(1536) NOT NULL
);

CREATE INDEX idx_chunks_embedding ON chunks
USING hnsw (embedding vector_cosine_ops);

-- Top-10 most similar
SELECT id, content, 1 - (embedding <=> $1) AS similarity
FROM chunks
ORDER BY embedding <=> $1
LIMIT 10;
```

For most teams in 2026, pgvector is the right vector database. Tens of millions of vectors, sub-100ms recall, no separate operational system. See [VectorDatabases] for the comparison.

## Common Table Expressions (CTEs)

CTEs are SQL's structuring tool. Use them generously.

```sql
WITH active_users AS (
    SELECT id, email FROM users WHERE deleted_at IS NULL
),
recent_orders AS (
    SELECT user_id, total FROM orders 
    WHERE created_at > NOW() - INTERVAL '30 days'
)
SELECT u.email, COALESCE(SUM(o.total), 0) AS spent
FROM active_users u
LEFT JOIN recent_orders o ON o.user_id = u.id
GROUP BY u.id, u.email;
```

Recursive CTEs handle hierarchies (org charts, comment trees) and graph traversals:

```sql
WITH RECURSIVE descendants AS (
    SELECT id, parent_id, name FROM categories WHERE id = 5
    UNION ALL
    SELECT c.id, c.parent_id, c.name
    FROM categories c JOIN descendants d ON c.parent_id = d.id
)
SELECT * FROM descendants;
```

Pre-PG12 CTEs were optimisation fences. PG12+ inlined them, so CTEs are now a structuring tool with no performance cost vs subqueries. Use them.

## Window functions

```sql
SELECT 
    user_id,
    order_id,
    total,
    ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY created_at) AS order_seq,
    SUM(total) OVER (PARTITION BY user_id ORDER BY created_at) AS running_total,
    LAG(total) OVER (PARTITION BY user_id ORDER BY created_at) AS prev_order_total
FROM orders;
```

Solves "running totals," "rank within group," "time since last event," and similar queries that would otherwise require self-joins.

## Range types

Native types for ranges (numeric, date, timestamp). With `EXCLUDE` constraints, you get conflict-free booking systems:

```sql
CREATE TABLE bookings (
    id BIGSERIAL PRIMARY KEY,
    room_id BIGINT REFERENCES rooms(id),
    period TSTZRANGE NOT NULL,
    EXCLUDE USING gist (room_id WITH =, period WITH &&)
);
```

This is one constraint that prevents any two bookings of the same room from overlapping in time. The database does the conflict detection; you don't.

## Partitioning

Native declarative partitioning since PG10. Partition by range, list, or hash.

```sql
CREATE TABLE events (
    id BIGINT,
    occurred_at TIMESTAMPTZ NOT NULL,
    -- ...
) PARTITION BY RANGE (occurred_at);

CREATE TABLE events_2026_q1 PARTITION OF events
FOR VALUES FROM ('2026-01-01') TO ('2026-04-01');
```

Use cases:

- Time-series tables that grow forever — drop old partitions instead of `DELETE`.
- Multi-tenant tables where queries are mostly per-tenant — partition by `tenant_id` for query pruning.
- Massive tables where vacuum, indexing, and maintenance per-partition wins.

`pg_partman` automates partition creation/dropping for time-series. Worth the install on any production time-series workload.

## Logical replication

Publish/subscribe between Postgres instances at the row level (not just byte-level streaming):

- Replicate a subset of tables to a reporting database.
- Migrate between major versions with minimal downtime (zero-downtime if you're careful).
- Cross-region read replicas with bandwidth control.
- Capture changes for downstream pipelines (alternative to Debezium for some use cases).

```sql
-- On primary
CREATE PUBLICATION my_pub FOR TABLE orders, users;

-- On replica
CREATE SUBSCRIPTION my_sub 
CONNECTION 'host=primary user=replicator dbname=main' 
PUBLICATION my_pub;
```

## FDW (Foreign Data Wrappers)

Query other databases as if they were tables. `postgres_fdw` for cross-Postgres; `oracle_fdw`, `mysql_fdw`, `mongo_fdw`, `clickhouse_fdw` for others.

Use case: pulling data from a legacy system into reports without ETL. Less common in 2026 (data warehouses subsume this), but still useful for migrations.

## Trigger-based history

Combined with `JSONB`, simple history table:

```sql
CREATE TABLE orders_history (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    snapshot JSONB NOT NULL,
    op TEXT NOT NULL CHECK (op IN ('insert','update','delete')),
    changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE FUNCTION log_orders_history() RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO orders_history (order_id, snapshot, op)
    VALUES (COALESCE(NEW.id, OLD.id), to_jsonb(COALESCE(NEW, OLD)), TG_OP::TEXT);
    RETURN COALESCE(NEW, OLD);
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER orders_history_trigger
AFTER INSERT OR UPDATE OR DELETE ON orders
FOR EACH ROW EXECUTE FUNCTION log_orders_history();
```

Cheap, comprehensive, queryable. Adequate for most audit/history use cases without needing temporal-table machinery.

## Other tools worth knowing

- **`generate_series`** for synthetic test data.
- **`array_agg` / `jsonb_agg`** for compact aggregations.
- **`tablefunc.crosstab`** for pivots.
- **`unnest`** for exploding arrays into rows.
- **GIST / GIN / BRIN / Bloom indexes** for non-B-tree indexing — see [DatabaseIndexingStrategies].
- **PostGIS** for geographic data; mature and excellent.
- **TimescaleDB** for time-series at very large scale (extension on top of Postgres).
- **Citus** for horizontal scaling (extension turning Postgres into a distributed SQL database).
- **`pg_cron`** for scheduling jobs inside Postgres.

## Where to leave Postgres

Despite all of the above, some workloads still want a different store:

- **Sub-millisecond key-value lookups at extreme scale** — Redis, Memcached.
- **Heavy time-series at billions of points** — InfluxDB, ClickHouse, TimescaleDB (Postgres extension).
- **OLAP / analytics over TB+** — Snowflake, BigQuery, ClickHouse, DuckDB.
- **Full-text search at extreme scale** — Elasticsearch / OpenSearch (Postgres has full-text but tops out earlier).
- **Document store with global distribution** — MongoDB, Cosmos DB.
- **Truly distributed SQL with multi-region writes** — CockroachDB, Spanner.

For most teams under "extreme" scale, the Postgres-everywhere posture saves operational cost. Adding a new datastore should require a specific reason.

## Further reading

- [DatabaseDesign] — schema choices that take advantage of these features
- [DatabaseIndexingStrategies] — Postgres index types in depth
- [JsonbInPostgresql] — JSONB specifically
- [VectorDatabases] — pgvector vs alternatives
- [DatabasePartitioning] — partitioning patterns
