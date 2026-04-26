---
title: Jsonb In Postgresql
type: article
cluster: databases
status: active
date: '2026-04-25'
tags:
- postgresql
- jsonb
- semi-structured
- gin-index
summary: JSONB in Postgres for flexible schema — query operators, indexing
  strategies (GIN, functional, covering), and when JSONB beats EAV / a
  separate document database.
related:
- PostgresqlAdvancedFeatures
- DatabaseDesign
- DatabaseIndexingStrategies
- NoSqlDatabaseTypes
hubs:
- Databases Hub
---
# JSONB in PostgreSQL

JSONB is Postgres's binary JSON type — nested, flexible, queryable, indexable. It turns Postgres into a credible document store while you keep transactions, joins with relational data, and the rest of the SQL ecosystem.

Most "we need a NoSQL document database" cases are better served by JSONB. This page is the working set.

## JSON vs JSONB

Two JSON types in Postgres:

- **`JSON`** — text storage; preserves whitespace, key order, duplicates. Faster to write; slower to query.
- **`JSONB`** — binary storage; normalised; queryable; indexable. Faster to query; slightly slower to write.

For almost every use case: **JSONB**. Use plain `JSON` only if you need to preserve the exact text representation.

## Storage and writes

JSONB normalises on write:

```sql
INSERT INTO docs (data) VALUES ('{"a": 1, "a": 2, "b":  3 }');
SELECT data FROM docs;
-- {"a": 2, "b": 3}  -- duplicate key removed; whitespace gone
```

Internally, JSONB stores as a tree of typed elements, not as a string. Operations don't re-parse.

Update of a single field rewrites the whole JSONB value (Postgres MVCC). For very large JSONB values updated frequently, this is expensive — split into separate columns or rows.

## Querying

### Path operators

```sql
-- Get value at path
SELECT data->'name' FROM docs;            -- returns JSONB
SELECT data->>'name' FROM docs;           -- returns text
SELECT data->'address'->>'city' FROM docs;
SELECT data#>'{address,city}' FROM docs;  -- text path syntax
SELECT data#>>'{address,city}' FROM docs;
```

### Existence operators

```sql
-- Has key
SELECT * FROM docs WHERE data ? 'email';

-- Has all keys
SELECT * FROM docs WHERE data ?& array['name', 'email'];

-- Has any of
SELECT * FROM docs WHERE data ?| array['phone', 'email'];
```

### Containment

```sql
-- data contains the given JSON (subset match)
SELECT * FROM docs WHERE data @> '{"status": "active"}';

-- Array containment
SELECT * FROM docs WHERE data->'tags' @> '["urgent"]';
```

`@>` is the workhorse for JSONB queries. It matches partial structure; the right side need only be a subset.

### JSONPath (Postgres 12+)

```sql
SELECT * FROM docs WHERE data @? '$.address.city ? (@ == "Berlin")';
```

More expressive but less commonly used. Useful for complex path queries; awkward syntax.

## Indexing

The trick with JSONB is index strategy.

### GIN on the whole JSONB

```sql
CREATE INDEX idx_docs_data ON docs USING GIN (data);
```

Indexes every key-value pair. Supports `@>`, `?`, `?&`, `?|` queries. Works for diverse query patterns; large index size.

### GIN with `jsonb_path_ops`

```sql
CREATE INDEX idx_docs_data ON docs USING GIN (data jsonb_path_ops);
```

Smaller (~30% smaller) and faster for `@>` queries specifically. Doesn't support `?`, `?&`, `?|`. Use when containment is your primary query.

For most JSONB use cases, `jsonb_path_ops` is the right pick.

### Functional indexes on specific paths

```sql
CREATE INDEX idx_docs_user_id ON docs ((data->>'user_id'));
CREATE INDEX idx_docs_status ON docs ((data->>'status')) WHERE data->>'status' IS NOT NULL;
```

Far smaller than full-JSONB GIN; faster for queries on that specific path. Right when you have a few hot paths and don't need general-purpose JSON queries.

The pattern: GIN for general flexibility; functional indexes for hot paths.

### Composite indexes with JSONB paths

```sql
CREATE INDEX idx_docs_tenant_status ON docs (
    tenant_id, 
    (data->>'status')
);
```

For multi-tenant queries that filter by JSONB fields, this works.

## When JSONB is the right call

- **Schema-flexible content.** Event logs where each event type has different fields; user-defined custom fields; configurations.
- **Sparse data.** Most rows have a small subset of possible fields; columns would be mostly null.
- **Polymorphic data.** A "content" field that could be text, image metadata, video metadata.
- **External integration payloads.** Storing the full payload from a webhook for later processing or audit.
- **Prototype phase.** Schema is evolving; lock it down later.

## When JSONB is the wrong call

- **All rows have the same fields.** Use proper columns. JSONB is more verbose, slower, and harder to constrain.
- **You need strict typing or constraints on inner values.** JSON is mostly typeless; constraints are awkward.
- **Heavy update on inner fields.** Each update rewrites the whole JSONB. Hot inner-field updates suffer.
- **Need to enforce relationships.** FKs reference rows, not JSONB inner values.

The pragmatic shape: typed columns for stable, queryable, constrainable fields; JSONB for flexible / sparse / payload fields. Hybrid.

## Updating JSONB

### Replace the whole value

```sql
UPDATE docs SET data = '{"new":"data"}' WHERE id = 1;
```

Simplest; rewrites the row.

### `jsonb_set` — update a path

```sql
UPDATE docs SET data = jsonb_set(data, '{address,city}', '"Berlin"') WHERE id = 1;
```

Cleaner than reading-modifying-writing in app code.

### Concatenate

```sql
UPDATE docs SET data = data || '{"updated_at": "2026-04-25"}' WHERE id = 1;
```

Merges the new fields into the existing JSONB.

### Remove a key

```sql
UPDATE docs SET data = data - 'temporary_field' WHERE id = 1;
UPDATE docs SET data = data #- '{nested,path}' WHERE id = 1;
```

For complex updates, consider extracting into proper columns instead. Once update patterns stabilise, JSONB's flexibility is no longer earning its keep.

## Performance gotchas

### Toast overhead

JSONB columns over 2KB get TOAST-stored (out-of-line). Reading the row reads the inline part fast; reading the JSONB requires the TOAST fetch. For very large JSONB values, this matters.

Mitigation: split large JSONB into smaller pieces; use separate tables for parts queried independently.

### Expensive `@>` for deep nesting

`@>` is fast with `jsonb_path_ops` GIN for top-level matches; deeper-nested matches are slower. Profile.

### Update bloat

Each JSONB update rewrites the row entirely (MVCC). High-update workloads on big JSONBs produce dead tuples; vacuum has to clean. Monitor.

### Statistics

Postgres's planner doesn't have great statistics for JSONB. Selectivity estimates can be off; query plans suboptimal.

Workaround: where critical, use functional indexes on specific paths and let the planner use those columns' statistics.

## Schema enforcement on JSONB

JSONB is schemaless by default. Enforce structure where it matters:

```sql
-- CHECK constraint
ALTER TABLE docs ADD CONSTRAINT data_has_id 
    CHECK (data ? 'id' AND jsonb_typeof(data->'id') = 'string');
```

For complex schema validation, store schemas externally (JSON Schema) and validate at the application or via a function trigger.

## Migration from documents to columns

Mature pattern: data starts in JSONB while shape is uncertain; specific frequently-queried fields get extracted to proper columns.

```sql
-- Add a typed column, backfill from JSONB
ALTER TABLE docs ADD COLUMN status TEXT;
UPDATE docs SET status = data->>'status' WHERE data ? 'status';
CREATE INDEX ON docs (status);

-- Optionally remove from JSONB to avoid duplication
UPDATE docs SET data = data - 'status';
```

Or, generated columns:

```sql
ALTER TABLE docs ADD COLUMN status TEXT 
    GENERATED ALWAYS AS (data->>'status') STORED;
CREATE INDEX ON docs (status);
```

The generated column auto-syncs; no separate update logic.

## Comparison with MongoDB

For most "document store" use cases, Postgres + JSONB matches MongoDB on:

- Storage flexibility.
- Indexing on inner fields.
- Most query patterns.

And exceeds on:

- Transactions.
- Joins with non-JSONB data.
- SQL ecosystem (ORMs, BI tools, observability).
- Operations (one DB to operate, not two).

Where MongoDB wins:

- Aggregation pipelines (Postgres has window functions and CTEs but the syntax is different).
- Native sharding (Postgres needs Citus or sharding-application-side).
- Some "fluid schema, no SQL knowledge" team cases.

For most teams in 2026: JSONB in Postgres beats MongoDB for the same use cases.

## A pragmatic recipe

For schema-flexible data:

1. **Store as JSONB** with a `data` column.
2. **Add typed columns** for frequently-queried fields (or use generated columns).
3. **Index** with GIN+jsonb_path_ops for general flexibility, plus functional indexes on hot paths.
4. **Validate at insert** if structure matters.
5. **As patterns emerge, extract more columns**.
6. **Regularly review JSONB usage**: are these fields stable enough to be columns?

A pragmatic JSONB schema ages well. A JSONB-everything schema stagnates.

## Further reading

- [PostgresqlAdvancedFeatures] — JSONB in the broader feature context
- [DatabaseDesign] — when to JSONB vs columns
- [DatabaseIndexingStrategies] — GIN and functional index details
- [NoSqlDatabaseTypes] — JSONB vs MongoDB in context
