---
title: Planning a Migration Change
cluster: agent-cookbook
kg_include: true
status: active
type: runbook
canonical_id: 01KQ4D6CH7MGBCGE5QSNEDS00C
audience:
- agents
- humans
runbook:
  when_to_use:
  - You are adding a column, table, index, or constraint
  - You are renaming or dropping a database object
  - You need to backfill data alongside a schema change
  inputs:
  - The intended schema change in plain SQL
  - The name of the next migration (`V<NNN>__description.sql`)
  steps:
  - 'Pick the next number: look at the highest `V*.sql` in `bin/db/migrations/` (currently
    V061) and read the README there for the naming convention (`V<NNN>__snake_case.sql`,
    one logical change per file)'
  - Write the migration before the code and tests that depend on it, with idempotent
    DDL — `CREATE TABLE IF NOT EXISTS`, `ADD COLUMN IF NOT EXISTS`, `CREATE INDEX
    IF NOT EXISTS`, `INSERT … ON CONFLICT DO NOTHING` — so re-applying is a no-op
  - Use the `:app_user` psql variable for grants — never hard-code the application
    role
  - 'Apply it locally: `DB_NAME=wikantik DB_APP_USER=wikantik PGUSER=postgres bin/db/migrate.sh`
    (set PGHOST and PGPASSWORD as needed; the default PGUSER is the least-privilege
    `migrate` role). `bin/deploy-local.sh` and `bin/redeploy.sh` also run it'
  - Run it a second time to confirm idempotence (no errors, no changes)
  - Run `bin/db/migrate.sh --status` to confirm the migration is registered
  - 'Test against the real schema: `@RequiresPostgres` plus `PostgresTestDb.createDataSource()`
    applies every migration to a throwaway pgvector container; a multi-statement write
    also gets a `FaultInjectingDataSource` rollback test. Never hand-write `CREATE
    TABLE` in a test (`TestSchemaSingleSourceTest` fails the build)'
  - 'Commit the migration in the same commit as the code that depends on it. A data
    backfill is not a migration: put it in `bin/db/one-shots/` and run it by hand'
  pitfalls:
  - Editing a migration after it has been applied anywhere beyond local dev — never;
    fix mistakes with a follow-up migration (the `schema_migrations` history is append-only)
  - Skipping IF NOT EXISTS on a CREATE — re-applies will fail loudly
  - Forgetting to grant on the new table — the application role can't read/write what
    it doesn't own
  - Bundling unrelated DDL in one migration — each migration should answer one schema
    question
  - Hand-running DDL against the live database without a migration file — the migration
    history loses authority
  - Putting a data backfill or a long-running statement in a versioned migration —
    migrations stay fast, DDL-only and run in one transaction
  - Dropping or renaming an object in the same release that stops using it — an image
    rollback does not roll the schema back; add first, remove in a later migration
  references:
  - BuildingAndDeployingLocally
  - WikantikReleaseAndDeployProcess
summary: 'What to touch when a code change needs a schema change: numbered idempotent
  migrations, grants, local apply, tests, and the no-edit-after-prod rule.'
tags:
- database
- migration
- runbook
- agent-context
---
# Planning a Migration Change

Wikantik's database migrations live in `bin/db/migrations/`. The convention is small and strict: numbered files, idempotent DDL, one schema concern per file, and never edit after production. The migrations are the only schema definition — there is no other DDL anywhere, not even for tests.

## When to use this runbook

Before any commit that adds, drops, renames, or alters a database object.

## Context

`bin/db/migrate.sh` runs the unapplied `V*.sql` files in order against the configured database, each inside a single transaction (`psql --single-transaction`), and records the version in the `schema_migrations` table. It runs on every `bin/deploy-local.sh` and `bin/redeploy.sh`, and the production container's entrypoint runs it before Tomcat starts and refuses to start on failure. Nothing ever reverses a migration, so the history is append-only: a bad migration is fixed with a follow-up, and rolling the application image back does not roll the schema back.

The `:app_user` psql variable is bound by the migrate script from `DB_APP_USER` — using it in `GRANT` statements keeps migrations portable between environments where the application role has a different name.

Tests read the same files: `PostgresTestDb` (the `wikantik-jdbc` test-jar) applies every migration to a throwaway pgvector container, so a test that needs a table is a reason to write the migration first. Never hand-write a `CREATE TABLE` in a test; `TestSchemaSingleSourceTest` fails the build if you do.

## Walkthrough

The frontmatter `steps` are the canonical procedure. Local apply + re-apply is the cheap way to catch idempotence bugs before they hit production, and running the test class that needs the table proves the migration and the code agree.

## Changing an existing column or table

Because the previous release's code may still be running against the schema (during a deploy, or after an image rollback), prefer additive migrations: add the new column or table in one migration, ship the code that uses it, and drop or rename the old object in a later migration once nothing reads it. Do not rename and drop in the same release that stops using the object.

## Pitfalls

The frontmatter `pitfalls` capture the failure modes worth memorising. The "editing after prod" rule is the most expensive to violate — it splits the migration history between "what production has applied" and "what the source tree says was applied", and that divergence is extremely painful to recover from.
