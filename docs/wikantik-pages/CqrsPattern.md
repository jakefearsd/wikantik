---
canonical_id: 01KQ12YDT9W70ZAA9TNNEERXSH
title: Cqrs Pattern
type: article
cluster: software-architecture
status: active
date: '2026-04-25'
tags:
- cqrs
- command-query
- event-sourcing
- read-model
summary: When CQRS pays for itself, when it's overkill, and the practical
  variations between "separate models" and "separate services with separate
  databases."
related:
- EventDrivenArchitecture
- DomainDrivenDesign
- DatabaseDesign
- DomainAndIntegrationEvents
hubs:
- SoftwareArchitecture Hub
---
# CQRS Pattern

CQRS — Command Query Responsibility Segregation — is the idea that the model used to update data should be different from the model used to read it. Writes go through one path with strict invariants; reads go through another path optimised for the queries you actually run.

The pattern is almost always misapplied. Most teams adopt it as ceremony when separating reads and writes wasn't actually their bottleneck. When it *does* fit, the gains are large.

## The core insight

In a typical CRUD app, your domain model serves two masters:

- **Writes** care about invariants, validation, transactions. The model needs to enforce business rules ("can't ship before paid").
- **Reads** care about query performance and the shape that fits the UI ("homepage needs the user, their last 5 orders, total spent this month").

These have different requirements. A single normalised SQL schema serves both badly: writes are fine but reads need 8 joins; or you denormalise for reads and writes become risky.

CQRS says: split the models. Writes go to a transactional, normalised model; reads go to a denormalised, query-optimised model. Synchronisation between them is explicit (often via events).

## The spectrum

CQRS comes in flavours from "lightweight" to "full event sourcing." Don't conflate them.

| Variant | Write side | Read side | Sync |
|---|---|---|---|
| **In-process** | Domain model | DTO classes | Direct mapping |
| **Same DB, separate models** | Normalised tables | Materialised views | DB-managed |
| **Separate stores** | Postgres | Elasticsearch / Redis | App or CDC pipeline |
| **Event-sourced** | Event log | Projections | Apply events |

Pick the lightest one that solves your problem. Going full event-sourced when "materialised views" would suffice is the textbook over-engineering case.

### In-process

You have a `User` domain object with methods (`changeEmail`, `verify`) and `UserView` DTOs for read responses. Mapper translates. This is just "use different classes for reads and writes." Cheap, useful, not really CQRS the architecture pattern.

### Same DB, separate models

Materialised views (Postgres `MATERIALIZED VIEW`, denormalised tables) serve read queries; the underlying normalised schema serves writes. The DB updates the views on a schedule or trigger.

Example:

```sql
CREATE MATERIALIZED VIEW user_dashboard AS
SELECT u.id, u.name, u.email,
       count(o.id) as order_count,
       sum(o.total) as lifetime_value,
       max(o.created_at) as last_order_at
FROM users u LEFT JOIN orders o ON o.user_id = u.id
GROUP BY u.id;

REFRESH MATERIALIZED VIEW CONCURRENTLY user_dashboard;
```

Reads hit the view; writes hit the underlying tables. Stale read tolerance is whatever the refresh cadence is — usually fine for dashboards, not fine for "is the order I just placed there yet."

This variant is underrated. It buys most of the CQRS read-optimisation benefit at minimal complexity.

### Separate stores

Writes go to Postgres (transactional, normalised). Reads go to Elasticsearch (full-text), Redis (caches), or denormalised SQL tables.

Synchronisation is the hard part:

- **Application dual-write** — naive, fragile, prone to inconsistency on partial failures.
- **Outbox pattern** — write event to outbox table in same transaction; pipeline forwards to read store. Reliable. See [EventDrivenArchitecture].
- **Change Data Capture (CDC)** — Debezium or similar tails the DB log and forwards changes. Lower latency than outbox, more operational overhead.

This variant is justified when the read store has materially different capabilities than the write store (full-text search, geospatial, sub-millisecond response). If the read store is "Postgres but denormalised," materialised views in the same DB are usually simpler.

### Event-sourced CQRS

Write side: append-only event log. Reads side: any number of projections that derive state from the events.

Powerful but operationally expensive:
- Event log becomes the source of truth; it can never be wrong. Schema migrations are events themselves.
- Read projections are rebuildable from the log — useful for new views or fixing bugs.
- Replay performance becomes a real concern at scale (years of events to replay on cold start).

Adopt event sourcing only when audit, replay, and time-travel are genuine product requirements. Financial systems, medical systems, audit-heavy domains. Otherwise the complexity tax is too high.

## When CQRS earns its keep

You probably benefit when:

- **Read and write workloads are fundamentally different** — high-volume reads vs occasional writes, or vice versa.
- **The read model genuinely doesn't fit the write model** — UI needs aggregations, search, full-text, geospatial that the normalised schema can't serve fast.
- **You have specific scaling differences** — write throughput is fine, reads need 100× more capacity.

You probably don't when:

- **You're in CRUD-app territory** with reasonable query volume. Normalised SQL with judicious indexes is simpler.
- **Reads can wait for writes to commit** and you don't need denormalisation. CQRS would add latency without benefit.
- **You don't yet know the access patterns.** Picking read-side denormalisation upfront freezes assumptions; iterate on the simple shape first.

## A pragmatic adoption ladder

1. **Separate DTOs from domain entities.** Read responses don't expose your domain model. Cheap, almost always worth it.
2. **Add materialised views or denormalised tables** for the slowest read queries. Same DB. Refresh on a schedule.
3. **Move read traffic to a different store** when the gap between the read and write models is large enough that views can't bridge it. Sync via outbox or CDC.
4. **Event-source the write side** only if you have specific audit/replay requirements that justify it.

Most teams stop at step 2 or 3. Step 4 is rare and should be.

## Failure modes

**Eventual-consistency confusion.** User places order, refreshes page, doesn't see it. Read store hasn't caught up. Surface this in UX (optimistic update, "your order is being processed") or use read-after-write to the write store for the user's own changes.

**Sync pipeline lag.** Outbox / CDC pipeline backs up; read store grows stale. Monitor lag in seconds; alert at thresholds; ensure consumers can catch up faster than producers produce.

**Schema drift between sides.** Write side adds a field; read side doesn't. Read side breaks. Couple schema changes; review both sides in the same PR.

**Reading from the wrong side.** "I'll just query the write DB for this read" — defeats CQRS. If the read side is wrong for the use case, fix it; don't bypass.

**Projection rebuild cost.** For event-sourced systems, replaying a year of events to rebuild a projection takes hours. Snapshotting helps; build it from day one.

## CQRS without DDD, or vice versa

The pattern is often introduced together with [DomainDrivenDesign] and event sourcing as a single bundle. They aren't actually coupled.

- CQRS without DDD is fine — separate read/write models in any architecture.
- DDD without CQRS is fine — bounded contexts and aggregates without read-side separation.
- Event sourcing without CQRS is technically possible but unusual.

Adopt each independently based on whether it solves your specific problem.

## Further reading

- [EventDrivenArchitecture] — common substrate for CQRS sync
- [DomainDrivenDesign] — bounded contexts as the natural CQRS unit
- [DatabaseDesign] — read-vs-write schema choices
- [DomainAndIntegrationEvents] — events as the integration contract
