---
canonical_id: 01KQ0P44TDQR5Z0R057S05HGAJ
title: Outbox Pattern
type: article
cluster: distributed-systems
status: active
date: '2026-05-15'
tags:
- outbox-pattern
- postgresql
- cdc
- debezium
- distributed-systems
summary: Technical implementation guide for the Outbox Pattern using PostgreSQL logical decoding and Debezium for guaranteed at-least-once event delivery.
auto-generated: false
---

# The Outbox Pattern

The Outbox Pattern solves the "dual-write" problem in distributed systems: the risk that a database transaction commits but the subsequent message publication to a broker (like Kafka) fails, or vice versa. By writing the event to a local `outbox` table within the same ACID transaction as the business logic, we guarantee that the event is captured if and only if the state change is persisted.

## PostgreSQL CDC Implementation Logic

For high-throughput systems, polling the outbox table is inefficient. The modern standard is **Change Data Capture (CDC)** via PostgreSQL's logical decoding.

### 1. Database Schema Setup
Create a dedicated outbox table. Using a UUID for the `id` helps with idempotency on the consumer side.

```sql
CREATE TABLE outbox (
    id UUID PRIMARY KEY,
    aggregate_type TEXT NOT NULL,
    aggregate_id TEXT NOT NULL,
    type TEXT NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Index for manual cleanup/audit if needed
CREATE INDEX idx_outbox_created_at ON outbox (created_at);
```

### 2. Configuring Logical Replication
PostgreSQL must be configured to support logical decoding. In `postgresql.conf`:

```ini
wal_level = logical
max_replication_slots = 5
max_wal_senders = 5
```

Create a **Publication** for the outbox table:
```sql
CREATE PUBLICATION outbox_pub FOR TABLE outbox;
```

### 3. Consuming the WAL (Logical Decoding)
You can consume the Write-Ahead Log (WAL) directly using the `pgoutput` plugin. This is what tools like Debezium use under the hood.

**Low-level consumption example (Python with `psycopg2`):**
```python
import psycopg2
from psycopg2.extras import LogicalReplicationConnection

conn = psycopg2.connect("dbname=mydb user=postgres", 
                        connection_factory=LogicalReplicationConnection)
cur = conn.cursor()

# Create a logical replication slot using pgoutput plugin
try:
    cur.create_replication_slot('outbox_slot', output_plugin='pgoutput')
except psycopg2.errors.DuplicateObject:
    pass

# Start replication stream
cur.start_replication(slot_name='outbox_slot', decode=True, 
                      options={'proto_version': '1', 'publication_names': 'outbox_pub'})

def handle_message(msg):
    # msg.payload contains the raw WAL log entry
    # Logic here to parse 'INSERT' into the outbox table and 
    # publish to Kafka/RabbitMQ
    print(f"Captured WAL entry: {msg.payload}")
    msg.cursor.send_feedback(flush_lsn=msg.data_start)

cur.consume_stream(handle_message)
```

## Debezium Outbox Routing

Debezium is the industry-standard connector for this pattern. It uses a **Single Message Transform (SMT)** to route events from a single outbox table to multiple Kafka topics based on the `aggregate_type`.

### Debezium Connector Configuration (JSON)
```json
{
  "name": "outbox-connector",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "database.hostname": "postgres",
    "database.dbname": "inventory",
    "table.include.list": "public.outbox",
    "tombstones.on.delete": "false",
    "transforms": "outbox",
    "transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
    "transforms.outbox.table.field.event.id": "id",
    "transforms.outbox.table.field.event.key": "aggregate_id",
    "transforms.outbox.table.field.event.payload": "payload",
    "transforms.outbox.route.topic.replacement": "events.${routedByValue}",
    "transforms.outbox.route.by.field": "aggregate_type"
  }
}
```

### How the SMT Works:
1.  **Detection:** Debezium detects an `INSERT` in the `outbox` table.
2.  **Transformation:** The `EventRouter` SMT extracts the `payload` and `id`.
3.  **Routing:** It sets the Kafka topic name to `events.Order` (if `aggregate_type` was 'Order').
4.  **Cleanup:** After the event is safely in Kafka, you can optionally delete the row from the `outbox` table. Note that Debezium captures the *insert*, so deleting the row later doesn't affect the event already in the broker.

## Resilience and Idempotency

### The "At-Least-Once" Guarantee
Logical decoding ensures you don't miss an event. However, failures during the network hop between the CDC connector and the broker can result in duplicate messages.

### Consumer Idempotency Strategies
1.  **Database Deduplication:** Store the `outbox_id` in a `processed_events` table on the consumer side.
2.  **Natural Idempotency:** Design business logic such that repeating the operation is safe (e.g., "Set Status to Shipped" vs "Increment Shipped Count").

## Operational Checklist
-   **WAL Bloat:** If the CDC consumer stops, PostgreSQL will retain WAL files until they are consumed, potentially filling the disk. Monitor replication slot lag.
-   **Schema Evolution:** Ensure the `payload` JSON structure is versioned or managed via a Schema Registry if using Avro.
-   **Cleanup:** Implement a background job to truncate the `outbox` table after $N$ days to prevent the table from becoming a performance bottleneck.

## Further Reading
- [ChangeDataCapture](ChangeDataCapture)
- [EventSourcing](EventSourcing)
- [IdempotencyPatterns](IdempotencyPatterns)
