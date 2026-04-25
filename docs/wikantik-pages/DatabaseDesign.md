---
canonical_id: 01KQ12YDTGNPYEV2YB12F0QM96
title: Database Design
type: article
cluster: databases
status: active
date: '2026-04-25'
tags:
- database
- schema-design
- normalization
- denormalization
- data-modeling
summary: Schema design that survives growth — normalisation, when to denormalise,
  the constraints worth enforcing, and the patterns (audit columns, soft delete,
  surrogate keys) you'll thank yourself for later.
related:
- DatabaseDesignPatterns
- DatabaseIndexingStrategies
- DatabaseMigrationStrategies
- DimensionalModeling
hubs:
- Databases Hub
---
# Database Design

Database design decisions compound. A schema that survives the first five years of growth was usually designed by someone who'd seen a schema not survive. This page is the working set of choices that lead to schemas that age well.

## Normalisation, briefly

The classical normal forms are taught in every database course. The compact useful summary:

- **1NF** — atomic values per field, no repeating groups. Required.
- **2NF / 3NF** — every non-key attribute depends on the whole key, only on the key. Practically: no redundant data.
- **BCNF / 4NF / 5NF** — refinements that catch edge cases of 3NF. Useful to know about; you'll rarely need them in practice.

The pragmatic version: **start in 3NF, denormalise deliberately** when measurements show normalised queries are too expensive. Denormalising prematurely creates duplicate-of-truth bugs that are far worse than the join cost you saved.

## The columns every table should have

For mutable application data, almost every table benefits from:

- **Surrogate primary key** — `BIGINT GENERATED ALWAYS AS IDENTITY` or `UUID`. Don't use natural keys (email, username) as primary keys; they change.
- **`created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`** — when the row was created.
- **`updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`** — when last modified. Trigger to update.
- **`deleted_at TIMESTAMPTZ NULL`** — for soft delete (see below). Or use a `deleted BOOLEAN` if you don't need the timestamp.
- **`tenant_id`** — for multi-tenant systems, on every table from day one. Adding tenant isolation later is a nightmare.

These add a few bytes per row and pay back many times over the schema's lifetime.

## Surrogate vs natural keys

Always use a surrogate key as the primary key. Reasons:

- **Natural keys change.** Emails get rewritten. SKUs get reissued. ISBNs get reused over decades. Foreign keys to natural-key tables make every change a cascade.
- **Indexing is faster on integers.** A `BIGINT` is 8 bytes; an email could be 50+. Indexes are smaller; range scans faster.
- **Privacy.** Surrogate keys don't reveal anything about the row. Natural keys often do.

Use natural keys as **unique constraints** alongside the surrogate key — that's how you enforce "email is unique" without making it the FK target.

The "BIGINT vs UUID" debate:

- **BIGINT** — sequential, smaller, cache-friendly, leaks order-of-creation.
- **UUID v4** — random, large (16 bytes), bad for index locality, doesn't leak.
- **UUID v7** (timestamp-prefixed, 2024 standard) — best of both: locality of BIGINT, distributability of UUID. Recommended default for new schemas.

For high-write tables, UUID v7 over UUID v4. For internal-only tables in single-DB systems, BIGINT is still fine.

## Soft delete vs hard delete

Soft delete (`deleted_at` timestamp, queries filter `WHERE deleted_at IS NULL`):

- Pros: recoverable, audit-friendly, easy to implement.
- Cons: every query has to remember the filter; bugs leak deleted rows; tables grow forever; cascading delete logic gets complex.

Hard delete (actually `DELETE FROM`):

- Pros: clean; no filter discipline needed; smaller tables.
- Cons: irreversible; audit trail must live elsewhere; foreign keys with `ON DELETE` rules need careful design.

Pragmatic answer:

- **Soft delete for user-controlled data** where "undo" is expected (user accounts, posts, files).
- **Hard delete for derived / cache data** where re-deriving is fine.
- **Soft delete + scheduled hard-delete** after a retention period for things like deleted user accounts (compliance + recovery window).

If you go soft, set up a default `WHERE deleted_at IS NULL` clause via a view or framework convention so callers don't forget.

## Audit / history

When you need to know what a row used to look like:

1. **Audit columns only** (`updated_at`, `updated_by`) — knows when and who, not what changed.
2. **History table** — `orders` and `orders_history`; trigger writes a row on every change.
3. **Temporal tables** — `PERIOD FOR SYSTEM_TIME` in some databases; you query "what was this row like on 2026-01-01."
4. **Full event sourcing** — events are the source of truth; current row is a projection.

For most CRUD apps, option 2 (history table) is the right balance. Built once, costs little per row, easy to query.

## Foreign keys and constraints

Use them. The "we'll enforce this in the application layer" approach reliably loses to bugs that put the database into impossible states.

- **`FOREIGN KEY` constraints** — enforced at the database; impossible to write a `user_id` that doesn't exist in `users`.
- **`CHECK` constraints** — enforce column-level invariants (`status IN ('pending', 'paid', 'shipped')`, `total >= 0`).
- **`UNIQUE` constraints** — including partial unique indexes (`UNIQUE (email) WHERE deleted_at IS NULL`).
- **`NOT NULL`** wherever the column shouldn't be null. A nullable column is a much weaker contract than a non-null one.

The performance overhead is small. The correctness gain is large.

## Choosing types correctly

A few specific recommendations:

- **`TIMESTAMPTZ` everywhere, never `TIMESTAMP`.** Time without zone is a bug waiting to happen.
- **`TEXT` over `VARCHAR(n)`.** In modern Postgres they're identical performance-wise; `TEXT` doesn't impose an arbitrary cap.
- **`NUMERIC(p, s)` for money,** never `FLOAT` or `DOUBLE`. Floating-point rounding compounds.
- **`BOOLEAN` is a real type.** Don't use `INT 0/1` or `CHAR Y/N`.
- **`JSONB` for flexible blobs,** but only when there's a real reason. Use proper columns when you can.
- **Enums** via `CHECK` constraint or DB-native `ENUM`. Hard-coded magic strings without constraints decay.

## Multi-tenancy patterns

Three common shapes:

| Shape | Tenant isolation | Cost | Use when |
|---|---|---|---|
| **Shared DB, shared schema** | `tenant_id` column on every table | Cheapest | Most B2B SaaS, < 1000 tenants |
| **Shared DB, schema per tenant** | Postgres schema per tenant | Medium | Stronger isolation, ~100s of tenants |
| **Database per tenant** | Separate DB per tenant | Highest | Strict compliance / huge customers |

For most B2B SaaS in 2026, **shared DB with `tenant_id` on every table + Row Level Security (RLS)** is the right answer. Postgres RLS enforces tenant filtering at the DB layer; even a query missing the `WHERE tenant_id =` clause is filtered. Hard belt-and-braces protection.

Implement RLS:

```sql
ALTER TABLE orders ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON orders
  USING (tenant_id = current_setting('app.tenant_id')::bigint);
```

Set `app.tenant_id` per session at login. Cross-tenant access becomes impossible from the application; admin queries use a separate connection that bypasses RLS.

## Indexing decisions in design

Index decisions can be deferred but the schema decisions that *enable* indexing can't:

- **Foreign keys should be indexed.** Postgres doesn't auto-index FK columns; you'll regret it on `WHERE user_id =` queries. Always add the index.
- **`updated_at` is often worth an index** for incremental sync queries (`WHERE updated_at > $1`).
- **Composite columns on common filters.** If queries often `WHERE tenant_id = $1 AND status = $2`, a composite index `(tenant_id, status)` serves them.

See [DatabaseIndexingStrategies] for the full strategy.

## Schema migration discipline

Every schema change is a migration. Migrations are versioned, reviewed, run in order, never edited after merging. Tools: Flyway, Liquibase, sqlx, alembic, dbmate.

Backward-compatibility rules for online migrations:

1. **Adding columns is safe** if they're nullable or have defaults.
2. **Adding indexes is safe** with `CREATE INDEX CONCURRENTLY`.
3. **Renaming columns is unsafe** as a single step. Use the expand/contract pattern: add the new name, dual-write, migrate readers, drop the old.
4. **Dropping columns is unsafe** while old code still reads them. Same expand/contract.
5. **Type changes are usually unsafe** (rewriting a TEXT to JSONB on a 1B-row table = hours of lock).

See [DatabaseMigrationStrategies] for the patterns.

## What aging-well schemas have in common

Looking at schemas that have survived 5-10 years of evolution:

- They started in roughly 3NF.
- They have surrogate keys everywhere.
- They have `created_at`, `updated_at`, `tenant_id` from day one.
- Soft delete is used selectively, not universally.
- Foreign keys, CHECK constraints, NOT NULL are pervasive.
- Migrations are versioned and additive-by-default.
- Indexes were added because queries demanded them, not speculatively.

What aging-poorly schemas have in common: natural-key foreign keys, missing constraints, denormalisation done early to "save" joins, schema migrations applied manually, columns with magical strings ("type": "approved" but also "Approved" and "APPROVED").

## Further reading

- [DatabaseDesignPatterns] — patterns at table-set level (event sourcing, polymorphic associations)
- [DatabaseIndexingStrategies] — indexing decisions
- [DatabaseMigrationStrategies] — online schema change patterns
- [DimensionalModeling] — schemas for analytics / DW work
