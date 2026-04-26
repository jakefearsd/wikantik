---
title: Database Design Patterns
type: article
cluster: databases
status: active
date: '2026-04-25'
tags:
- database
- design-patterns
- event-sourcing
- audit-trail
- soft-delete
summary: Patterns at the multi-table level — event sourcing, audit trails,
  polymorphic associations, materialised views, soft delete, temporal data —
  with the trade-offs that decide when each pays.
related:
- DatabaseDesign
- DatabaseIndexingStrategies
- CqrsPattern
- EventDrivenArchitecture
hubs:
- Databases Hub
---
# Database Design Patterns

Where [DatabaseDesign] covers single-table choices (columns, types, constraints), this page is about patterns spanning multiple tables — how to structure related data, audit changes, model time, handle inheritance-like scenarios.

## Audit trails

Track who changed what when. Three approaches by cost / fidelity:

### Audit columns (cheapest)

Add `created_at`, `updated_at`, `created_by`, `updated_by` to every table. Trigger or app sets these. Tells you when and who; not what changed.

Universal default. Always do this.

### History table

For each table, a parallel history table:

```sql
CREATE TABLE orders (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    total NUMERIC(10,2) NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE orders_history (
    history_id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    snapshot JSONB NOT NULL,  -- full row at this point
    op TEXT NOT NULL CHECK (op IN ('insert','update','delete')),
    changed_by BIGINT,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

Trigger on the main table writes a snapshot to history on every change. Query history with `WHERE order_id = X ORDER BY changed_at`.

Trade-off: 2× storage; trigger overhead on writes. For tables with auditing requirements, the cost is justified.

### Event sourcing

Events are the source of truth; current state is a projection. See [CqrsPattern], [EventDrivenArchitecture].

```sql
CREATE TABLE order_events (
    id BIGSERIAL PRIMARY KEY,
    order_id BIGINT NOT NULL,
    event_type TEXT NOT NULL,  -- 'OrderPlaced', 'OrderShipped', etc.
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 'orders' table is a projection of these events
```

Heaviest pattern; full history; replayable. Adopt selectively (financial transactions, audit-critical domains).

## Soft delete

```sql
ALTER TABLE users ADD COLUMN deleted_at TIMESTAMPTZ;
-- Queries filter: WHERE deleted_at IS NULL
```

Trade-offs:

- Pros: recoverable; audit-friendly; cascading isn't immediate.
- Cons: every query has to filter; bugs leak deleted rows; tables grow forever.

Mitigations:

- **Default `WHERE deleted_at IS NULL` in views.** Reduces filter discipline burden.
- **Partial unique constraints.** `UNIQUE (email) WHERE deleted_at IS NULL` lets users re-register after deletion.
- **Scheduled hard delete** after retention period.

Use selectively — for things users expect to recover (accounts, posts), not for derived/cache data.

## Polymorphic associations

When a relation can point to one of several tables (a comment can attach to a post, a video, or a project):

### Bad: polymorphic FK without database constraint

```sql
CREATE TABLE comments (
    id BIGSERIAL PRIMARY KEY,
    parent_type TEXT,  -- 'post' / 'video' / 'project'
    parent_id BIGINT,
    body TEXT
);
```

Type-tag tells you which table; FK isn't enforced. Easy to write; the DB can't validate; broken references happen.

### Better: separate FK columns

```sql
CREATE TABLE comments (
    id BIGSERIAL PRIMARY KEY,
    post_id BIGINT REFERENCES posts(id),
    video_id BIGINT REFERENCES videos(id),
    project_id BIGINT REFERENCES projects(id),
    body TEXT,
    CHECK (num_nonnulls(post_id, video_id, project_id) = 1)
);
```

Verbose but correct. FKs validated; no orphan comments.

### Or: a single shared parent

```sql
CREATE TABLE commentables (
    id BIGSERIAL PRIMARY KEY,
    type TEXT NOT NULL  -- 'post' / 'video' / 'project'
);

CREATE TABLE posts (
    id BIGINT PRIMARY KEY REFERENCES commentables(id),
    -- post-specific fields
);

CREATE TABLE comments (
    id BIGSERIAL PRIMARY KEY,
    commentable_id BIGINT NOT NULL REFERENCES commentables(id),
    body TEXT
);
```

The "shared abstract parent" pattern. Adds a join layer; gives you a single stable foreign key.

For most applications, the "separate FK columns" version is the most pragmatic. The polymorphic-without-constraint version is the most common; it's also the most often regretted.

## Materialised views

Computed denormalisation, refreshed periodically:

```sql
CREATE MATERIALIZED VIEW user_stats AS
SELECT 
    u.id,
    u.email,
    COUNT(o.id) AS order_count,
    SUM(o.total) AS lifetime_value,
    MAX(o.created_at) AS last_order_at
FROM users u
LEFT JOIN orders o ON o.user_id = u.id
GROUP BY u.id, u.email;

CREATE UNIQUE INDEX ON user_stats (id);  -- enables CONCURRENT refresh

-- Refresh:
REFRESH MATERIALIZED VIEW CONCURRENTLY user_stats;
```

Use when:

- Same expensive aggregation queried many times.
- Staleness on the order of minutes-to-hours is acceptable.
- The underlying tables change less than the view is queried.

Refresh strategies:

- **Scheduled** (cron/pg_cron every N minutes).
- **Triggered** by underlying data changes (more complex; harder to keep correct).
- **On read with stale-while-revalidate** (background refresh; reads see stale until it completes).

Materialised views are underrated. They're cheaper than separate caching layers and sit inside the database where transactions and consistency are simpler.

## Temporal data

When a row's value changes over time but you need history:

### Effective-dated rows

```sql
CREATE TABLE prices (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL,
    price NUMERIC(10,2) NOT NULL,
    effective_from TIMESTAMPTZ NOT NULL,
    effective_until TIMESTAMPTZ
);

-- Current price: WHERE effective_from <= NOW() AND (effective_until IS NULL OR effective_until > NOW())
-- Price at time T: WHERE effective_from <= T AND (effective_until IS NULL OR effective_until > T)
```

Bitemporal versions add a separate "valid time" vs "system time" axis — what the data said vs when it said it. Useful for legal/financial systems where retroactive corrections happen.

### Postgres temporal tables

Native `PERIOD FOR SYSTEM_TIME` is in the SQL standard but Postgres doesn't have it built-in. Extensions (`pg_versioning`) provide it. For most needs, manual effective-dated tables work fine.

### Range types

Postgres `tstzrange` plus `EXCLUDE USING GIST` constraint can enforce non-overlapping time windows:

```sql
CREATE TABLE bookings (
    id BIGSERIAL PRIMARY KEY,
    room_id BIGINT,
    period TSTZRANGE,
    EXCLUDE USING GIST (room_id WITH =, period WITH &&)
);
```

The DB enforces "no two overlapping bookings of the same room." See [PostgresqlAdvancedFeatures].

## Hierarchies

Storing tree-shaped data:

### Adjacency list

```sql
CREATE TABLE categories (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    parent_id BIGINT REFERENCES categories(id)
);
```

Simple. Recursive CTE for traversal. Default for most cases.

### Materialised path

```sql
CREATE TABLE categories (
    id BIGSERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    path TEXT  -- e.g. '/electronics/computers/laptops'
);

CREATE INDEX ON categories USING GIST (path);
```

Or use Postgres `ltree` extension. Fast prefix queries ("everything under /electronics"); pain on rename / move.

### Closure table

A table of all ancestor-descendant pairs. Constant-time queries; expensive updates.

For most application needs, adjacency list with recursive CTE suffices. ltree / closure for very specific access patterns (tag hierarchies queried by prefix; performance-critical tree operations).

## Idempotency

For operations that might be retried:

```sql
CREATE TABLE idempotency_keys (
    key TEXT PRIMARY KEY,
    operation TEXT,
    result_hash TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX ON idempotency_keys (created_at);  -- for cleanup
```

Caller passes a unique idempotency key with each request. Server checks: if key exists, return same result; else perform and record.

Critical for any side-effect-producing API. Caller idempotency key + database idempotency table = no duplicate side effects on retry.

## Outbox pattern

For atomic database write + event publish:

```sql
CREATE TABLE outbox (
    id BIGSERIAL PRIMARY KEY,
    event_type TEXT NOT NULL,
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    published_at TIMESTAMPTZ
);

-- In transaction:
INSERT INTO orders (...) VALUES (...);
INSERT INTO outbox (event_type, payload) VALUES ('OrderCreated', ...);
COMMIT;

-- Background process polls/CDCs unpublished rows; publishes to broker; marks published_at.
```

See [EventDrivenArchitecture].

## State machines

For entities with states and transitions (orders, applications, tickets):

```sql
ALTER TABLE orders ADD CONSTRAINT valid_status 
  CHECK (status IN ('pending', 'paid', 'shipped', 'delivered', 'cancelled'));
```

Enforce transitions in the application layer (Postgres can't easily enforce "from A you can go to B but not C"); audit transitions to a state-history table.

For complex state machines, dedicated FSM libraries with persistence (Spring State Machine, XState with persistence). Often a separate concern from the database.

## What aging-well multi-table designs have in common

- Surrogate keys everywhere; FKs defined; constraints used.
- `created_at`, `updated_at`, `tenant_id` standardised.
- History tables for audit-relevant tables.
- Materialised views for expensive aggregations.
- Idempotency table for retry-prone APIs.
- Outbox for events.
- Migrations are versioned, additive-by-default, expand-contract for changes.

The pattern matters more than the framework. Most ORMs let you implement these patterns; the discipline is human.

## Further reading

- [DatabaseDesign] — single-table design choices
- [DatabaseIndexingStrategies] — making patterns above performant
- [CqrsPattern] — when read/write models diverge
- [EventDrivenArchitecture] — outbox, event sourcing in context
