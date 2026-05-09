---
canonical_id: 01KQ0P44N02ZE9M3WGCVNG091N
title: Change Data Capture
type: article
cluster: data-engineering
status: active
date: '2026-05-15'
tags:
- databases
- cdc
- data-engineering
- event-driven
- replication
- debezium
summary: Technical deep dive into log-based Change Data Capture (CDC), Debezium architecture, and transaction log mechanics.
related:
- DataEngineeringHub
- EventSourcing
- StreamProcessing
- RelationalDatabaseFundamentals
- DistributedSystemsHub
auto-generated: false
---
# Change Data Capture (CDC)

Change Data Capture (CDC) is a technique for observing and capturing changes made to a database and delivering them as real-time events to downstream systems. Unlike polling-based methods, modern CDC is **log-based**, directly reading the database's internal transaction logs (e.g., PostgreSQL WAL, MySQL Binlog).

## Why Log-Based CDC?
1. **Low Latency**: Changes are captured near-instantly after a commit.
2. **Zero Impact on Schema**: No need for `last_modified` columns or triggers that slow down production writes.
3. **Capture Deletes**: Polling cannot detect hard deletes; log-based CDC captures the `DELETE` event from the transaction log.
4. **Consistency**: Captures every state change, ensuring no intermediate updates are missed (critical for financial audit trails).

## The Debezium Architecture
Debezium is the industry-standard open-source platform for CDC. It typically runs as a set of connectors within **Kafka Connect**.

- **Source Connector**: Connects to the source DB (e.g., PostgreSQL) using a replication slot. It reads the WAL, parses the binary data, and produces JSON or Avro messages to a Kafka topic.
- **Topic per Table**: By default, Debezium creates one Kafka topic for every table being tracked (e.g., `dbserver1.inventory.orders`).
- **Schema Registry**: Highly recommended to use Avro/Protobuf with a schema registry to handle source schema changes without breaking consumers.

## Concrete Example: Debezium PostgreSQL Connector Config
To capture changes from a PostgreSQL database, you must set `wal_level = logical` in `postgresql.conf` and provide a connector configuration.

**JSON Configuration for Kafka Connect**:
```json
{
  "name": "inventory-connector",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "tasks.max": "1",
    "database.hostname": "postgres-db",
    "database.port": "5432",
    "database.user": "debezium",
    "database.password": "dbz",
    "database.dbname": "inventory",
    "database.server.name": "dbserver1",
    "table.include.list": "public.orders,public.customers",
    "plugin.name": "pgoutput",
    "publication.autocreate.mode": "filtered",
    "slot.name": "debezium_fulfillment_slot",
    "snapshot.mode": "initial"
  }
}
```

### The Payload Structure
A Debezium event contains a `before` and `after` block.
```json
{
  "op": "u",
  "before": { "id": 1, "status": "PENDING" },
  "after":  { "id": 1, "status": "SHIPPED" },
  "source": { "ts_ms": 1716200000000, "snapshot": "false" }
}
```

## Advanced Patterns
- **Outbox Pattern**: Instead of capturing changes from a business table, the application writes a specific event record to an `outbox` table in the same transaction. CDC tracks only the `outbox` table, ensuring that the event is only published if the primary transaction succeeds.
- **Dead Letter Queues (DLQ)**: If a consumer cannot parse a CDC event (e.g., due to an unexpected schema change), the record is routed to a DLQ for manual inspection, preventing the entire pipeline from stalling.
- **Materialized Views**: Using CDC to keep a downstream search engine (Elasticsearch) or cache (Redis) in sync with the relational source of truth.

## Summary of Technical implementation added
- Explained the mechanics of **Log-Based CDC** vs. polling.
- Detailed the **Debezium + Kafka Connect** architecture.
- Provided a concrete **JSON configuration** for a PostgreSQL connector.
- Illustrated the **before/after payload** structure.
- Introduced the **Outbox Pattern** and **Materialized Views** as advanced use cases.
