# Wikantik Database Migrations

This directory holds the canonical, ordered schema definition for the
Wikantik PostgreSQL database. Every schema change ships as a numbered,
idempotent migration so production databases can be brought forward
safely and repeatably.

## Files

- `V001__schema_migrations.sql` — tracking table used by `migrate.sh`
- `V002__core_users_groups.sql` — users, roles, groups, group_members + default admin seed
- `V003__policy_grants.sql` — database-backed authorization policy
- `V004__knowledge_graph.sql` — knowledge graph tables + pgvector + embeddings
- `V005__hub_membership.sql` — Hub centroids and Hub membership proposals

The companion scripts live one directory up:

- `../migrate.sh` — apply any pending migrations to an existing database
- `../install-fresh.sh` — create a new DB + app user and run `migrate.sh`

## Naming convention

```
V<NNN>__<snake_case_description>.sql
```

- `NNN` is a monotonically increasing zero-padded integer (`V006`, `V007`, …).
- Descriptions are lowercase snake_case.
- Double underscore separates the version from the description.
- One migration = one logical change. Don't bundle unrelated schema work.

## Rules for writing a migration

1. **Must be idempotent.** Use `CREATE TABLE IF NOT EXISTS`,
   `CREATE INDEX IF NOT EXISTS`, `ALTER TABLE … ADD COLUMN IF NOT EXISTS`,
   `INSERT … ON CONFLICT DO NOTHING`, and `INSERT … WHERE NOT EXISTS`.
   Running the same migration twice against the same database must be a
   no-op.
2. **Must not reference hard-coded role names** for grants. Use the
   `:app_user` psql variable — `migrate.sh` sets it from the
   `DB_APP_USER` environment variable (default `jspwiki`).
3. **Must not be edited after it has been applied in production.** Once
   `V00N` has run anywhere other than local dev, fix mistakes by writing
   a follow-up `V00(N+1)` migration. The `schema_migrations` table
   assumes history is append-only.
4. **Keep migrations fast and focused.** Long-running data backfills
   should happen in application code with a progress indicator, not in
   a DDL migration that holds locks.
5. **Document prerequisites** at the top of the file, especially when
   the migration depends on a PostgreSQL extension or an earlier
   migration.

## Adding a new migration

1. Pick the next version number by looking at the highest `V*.sql` in
   this directory and incrementing.
2. Create `V<NNN>__<description>.sql`.
3. Write the DDL using the idempotent patterns above.
4. Run `../migrate.sh` against your local dev database to verify it
   applies cleanly.
5. Run `../migrate.sh` a second time to verify it is a no-op.
6. Commit the migration in the same commit as the code that depends on
   the schema change.

## Running migrations

```bash
# Against the default local dev database
cd wikantik-war/src/main/config/db
./migrate.sh

# Check what has been applied
./migrate.sh --status

# Production: set connection vars and run
DB_NAME=wikantik_prod PGHOST=db.example.com PGUSER=postgres \
    PGPASSWORD='…' ./migrate.sh
```

## Relationship to the legacy `postgresql-*.ddl` files

The `postgresql.ddl`, `postgresql-permissions.ddl`,
`postgresql-knowledge.ddl`, and `postgresql-hub.ddl` files predate this
migration system and are kept only for historical reference. **Do not
add new schema there.** New schema goes in a numbered migration in this
directory.

The equivalence is:

| Legacy file                   | Current migration           |
|-------------------------------|-----------------------------|
| `postgresql.ddl`              | `V002__core_users_groups`   |
| `postgresql-permissions.ddl`  | `V003__policy_grants`       |
| `postgresql-knowledge.ddl`    | `V004__knowledge_graph`     |
| `postgresql-hub.ddl`          | `V005__hub_membership`      |
