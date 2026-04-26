---
title: Database Migration Strategies
type: article
cluster: databases
status: active
date: '2026-04-25'
tags:
- database-migration
- schema-evolution
- expand-contract
- zero-downtime
summary: Online schema migrations that don't take production down — the
  expand-contract pattern, locking concerns specific to Postgres / MySQL,
  and the migration tools worth using.
related:
- DatabaseDesign
- DatabaseDesignPatterns
- DatabasePartitioning
- SchemaRegistryAndEvolution
hubs:
- Databases Hub
---
# Database Migration Strategies

Database migrations are where engineering hits operations hardest. A schema change that takes 15 minutes in dev can take a database lock for 15 minutes in production, blocking every write. The discipline is making changes that look additive to the database while behaviour evolves in the application.

## What can go wrong

The classic outage scenarios:

- `ALTER TABLE` takes an `ACCESS EXCLUSIVE` lock; the table is unwritable for the duration.
- `CREATE INDEX` (without `CONCURRENTLY`) locks the table.
- Long-running migration on a billion-row table takes hours; another transaction conflicts; the database wedges.
- Application deploys the new code that requires the new column; column not yet there; everyone errors.
- Migration drops the old column while half the fleet still reads it.

Each of these is a real production incident pattern. The migration patterns below are how to avoid them.

## The migration tools

A migration tool versions every schema change and runs them in order. Standard options:

- **Flyway** — Java; widely used; mature.
- **Liquibase** — Java/XML; more declarative; also widely used.
- **Alembic** — Python; for SQLAlchemy.
- **db-migrate / Knex / Prisma Migrate** — Node.js.
- **golang-migrate / sqlx-cli / sqlc** — Go.
- **dbmate** — language-agnostic, simple.
- **sqlx migrate** — Rust.

The tool's job: apply migrations idempotently, in order, recording what's been applied. The hard part isn't the tool; it's the migration content.

For most teams, pick the standard tool for your stack and don't overthink it.

## Migration discipline rules

Before any migration:

1. **It's a versioned file**, named with a sequence number or timestamp.
2. **It's idempotent**: re-running it produces the same outcome (use `IF NOT EXISTS`, `ON CONFLICT DO NOTHING`).
3. **It's reviewed**: in the same PR as the application change that needs it.
4. **It's never edited after merge**: if you got it wrong, write a new migration.
5. **It's tested**: applied to staging that reflects production-scale data.

The "never edited after merge" rule prevents the worst kind of drift: prod has migration v17 that's different from v17 in dev because someone rewrote it.

## What's safe and what's not

For Postgres specifically (other databases similar but vary):

| Operation | Lock level | Safe online? |
|---|---|---|
| `CREATE TABLE` | None | Yes |
| `DROP TABLE` | ACCESS EXCLUSIVE | Yes if no concurrent reads (which is hard to guarantee) |
| `ADD COLUMN` (with default) | ACCESS EXCLUSIVE briefly (Postgres 11+) | Yes, with caveats |
| `ADD COLUMN` (without default) | None / minimal | Yes |
| `DROP COLUMN` | ACCESS EXCLUSIVE | Yes briefly; but app must be ready |
| `ALTER COLUMN TYPE` | ACCESS EXCLUSIVE; rewrites table | No — rewrites everything |
| `ALTER COLUMN SET NOT NULL` | ACCESS EXCLUSIVE | Yes if the column has no nulls; brief |
| `ADD CONSTRAINT FOREIGN KEY` | SHARE ROW EXCLUSIVE; validates | Use `NOT VALID` then `VALIDATE` |
| `ADD CONSTRAINT CHECK` | ACCESS EXCLUSIVE while validating | Use `NOT VALID` then `VALIDATE` |
| `CREATE INDEX` | SHARE | No — blocks writes |
| `CREATE INDEX CONCURRENTLY` | None | Yes — slower but doesn't block |
| `DROP INDEX` | ACCESS EXCLUSIVE briefly | Yes |
| `DROP INDEX CONCURRENTLY` | None | Yes |
| `RENAME COLUMN` | ACCESS EXCLUSIVE briefly | Don't — see expand/contract |

In MySQL, similar concerns plus its specific implementation quirks; tools like `gh-ost` and `pt-online-schema-change` handle the table-rewrite cases without locking.

## The expand-contract pattern

For any change that isn't a simple "add a column with a default":

```
1. EXPAND  — Add the new structure. Old code still works.
2. MIGRATE — Backfill data. Application uses both old and new in parallel.
3. CONTRACT — Remove the old structure. Application uses only new.
```

Each phase is independently safe. The cost is more migrations and more code paths during transition; the benefit is no big-bang outages.

### Example: rename a column

Bad: `ALTER TABLE users RENAME COLUMN phone TO phone_number;`

This is fast (no rewrite) but the application has the old name; deploys are fragile.

Better, expand-contract:

1. **Expand**: `ALTER TABLE users ADD COLUMN phone_number TEXT;`. Application writes both `phone` and `phone_number`. Reads still use `phone`.
2. **Backfill**: `UPDATE users SET phone_number = phone WHERE phone_number IS NULL;` (in batches if needed).
3. **Migrate readers**: deploy code that reads from `phone_number`, falls back to `phone`. Then deploy code that reads only `phone_number`.
4. **Migrate writers**: deploy code that writes only to `phone_number`.
5. **Contract**: `ALTER TABLE users DROP COLUMN phone;`. Now safe — nobody uses it.

5+ deploys instead of 1; zero downtime; reversible at every step.

### Example: change a column type

Bad: `ALTER TABLE orders ALTER COLUMN amount TYPE NUMERIC(10,2);` 

On a 1B-row table, this rewrites the whole table — hours of `ACCESS EXCLUSIVE`.

Better, expand-contract:

1. **Expand**: `ADD COLUMN amount_v2 NUMERIC(10,2);`.
2. **Trigger** copies new writes: `CREATE TRIGGER ... amount_v2 := amount;`.
3. **Backfill**: in batches, `UPDATE orders SET amount_v2 = amount::numeric WHERE amount_v2 IS NULL;` with batch size and pauses.
4. **Migrate readers** to use `amount_v2`.
5. **Contract**: drop `amount`, drop trigger, optionally rename `amount_v2` → `amount`.

Days instead of seconds, but no downtime.

### Example: add a NOT NULL column

Bad: `ALTER TABLE orders ADD COLUMN status TEXT NOT NULL;` — fails on existing rows.

Better:

1. `ADD COLUMN status TEXT;` — nullable.
2. Backfill: `UPDATE orders SET status = 'unknown' WHERE status IS NULL;` (in batches).
3. `ALTER TABLE orders ALTER COLUMN status SET NOT NULL;` — fast in Postgres if all rows have values.
4. Application starts writing real status values.

Three migrations; no big locks; reversible.

## Locking under load

Even "safe" operations interact poorly with long-running transactions:

- `ADD COLUMN` waits for all current transactions to finish before it can take its momentary lock.
- A long-running `SELECT` can block a quick `ADD COLUMN` from acquiring its lock.
- Other queries queue behind the waiting `ADD COLUMN`.

Result: a "fast" migration takes hours because of one transaction holding things open.

Defences:

- **`SET lock_timeout = '5s'` before the migration** — fail fast instead of holding everything up.
- **Set `statement_timeout`** to prevent runaway migrations.
- **Run during low-traffic windows** when possible, even for "safe" migrations.
- **Kill long-running competitors** if needed (they probably shouldn't be running anyway).

Postgres-specific tooling: `pg_repack` for table rewrites without long locks; `pg_squeeze` for similar.

## Backfills at scale

For large tables, backfilling all rows in one transaction is dangerous (locks, replication lag, transaction-id wraparound). Batch:

```sql
DO $$
DECLARE
    batch_size INT := 10000;
    last_id BIGINT := 0;
BEGIN
    LOOP
        UPDATE orders 
        SET status = 'unknown' 
        WHERE id > last_id AND status IS NULL
        ORDER BY id LIMIT batch_size;
        EXIT WHEN NOT FOUND;
        last_id := (SELECT MAX(id) FROM orders WHERE status IS NULL);
        PERFORM pg_sleep(0.1);  -- ease pressure
    END LOOP;
END $$;
```

For very large tables, use a job system (background worker) rather than SQL DO blocks — better observability and recoverability.

## Rollbacks

The classic question: "should migrations have down scripts?"

Pragmatic answer: **rarely useful**. By the time you'd run a down migration, the application has likely already deployed code expecting the new schema. The down migration becomes a fresh forward migration ("add the column back").

Rollback strategy that works: **don't migrate to changes you can't safely roll back from**. Use expand-contract; each phase is reversible by deploying older code.

Some tools support `down` migrations for development convenience (resetting a dev database). Treat those as dev-only; don't rely on them in production.

## Multi-database / multi-tenant migrations

For schema-per-tenant or DB-per-tenant systems, migrations apply to many databases. Considerations:

- **Idempotency** is even more critical (some tenants may already be migrated, others not).
- **Parallel application** speeds up large fleets but raises concurrency concerns.
- **Per-tenant rollout** lets you stop on first failure.

Tools: most migration tools handle this if you script the loop; some have built-in multi-database support.

## Failure modes seen in the wild

- **Migration ran in dev, looks fine; took down prod.** Different scale; different lock behaviour. Always test on production-equivalent data volumes.
- **Migration completed but data wasn't backfilled.** App expected backfilled values; got nulls; surprised. Always audit post-migration.
- **Migration deployed; old code still running on some hosts.** Mid-rollout. Code must tolerate either schema during deploy.
- **Migration applied but tracking table didn't update.** Migration runs again; double-applied. Use idempotent migrations.
- **Long-running transaction blocks migration; everything queues; site goes down.** Set `lock_timeout`.

Each of these is a real outage. Each has a cheap mitigation. Apply them.

## Further reading

- [DatabaseDesign] — schema design that anticipates evolution
- [DatabaseDesignPatterns] — patterns at multi-table level
- [DatabasePartitioning] — partition-aware migrations
- [SchemaRegistryAndEvolution] — same problem on the messaging side
