---
canonical_id: 01KQ4D6CH7MGBCGE5QSNEDS00C
title: Planning a Migration Change
type: runbook
cluster: agent-cookbook
audience: [agents, humans]
summary: What to touch when a code change implies a database schema change — DDL location, naming convention, idempotence requirement, grants, the deploy script's migration step, and the no-edit-after-prod rule.
tags:
  - database
  - migration
  - runbook
  - agent-context
runbook:
  when_to_use:
    - You are adding a column, table, index, or constraint
    - You are renaming a database object
    - You need to backfill data alongside a schema change
  inputs:
    - The intended schema change in plain SQL
    - The name of the next migration (`V<NNN>__description.sql`)
  steps:
    - Pick the next number in `bin/db/migrations/` — read the README there for the convention
    - Write idempotent DDL — every CREATE / ALTER uses `IF NOT EXISTS` / `IF EXISTS` so re-applying is a no-op
    - Use the `:app_user` psql variable for grants — never hard-code the application role
    - Run `DB_NAME=wikantik DB_APP_USER=jspwiki bin/db/migrate.sh` locally to apply
    - Run it a second time to confirm idempotence (no errors, no changes)
    - Run `bin/db/migrate.sh --status` to confirm the migration is registered
    - Add the matching commit alongside the code change — they should land together
  pitfalls:
    - Editing a migration after it has been applied to production — never; fix mistakes with a follow-up migration
    - Skipping the IF NOT EXISTS on a CREATE — re-applies will fail loudly
    - Forgetting to grant on the new table — the application role can't read/write what it doesn't own
    - Bundling unrelated DDL in one migration — each migration should answer one schema question
    - Hand-running DDL against the live database without a migration file — the migration history loses authority
  related_tools:
    - /admin/structural-conflicts
  references:
    - StructuralSpineDesign
    - BuildingAndDeployingLocally
---

# Planning a Migration Change

Wikantik's database migrations live in `bin/db/migrations/`. The
convention is small and strict: numbered files, idempotent DDL, one
schema concern per file, and never edit after production.

## When to use this runbook

Before any commit that adds, drops, renames, or alters a database object.

## Context

`bin/db/migrate.sh` runs the unapplied migrations in order against the
configured database. `bin/deploy-local.sh` calls it on every redeploy,
so locally-pending migrations apply automatically.

The `:app_user` psql variable is bound by the migrate script — using it
in `GRANT` statements keeps migrations portable between environments
where the application role has a different name.

## Walkthrough

The frontmatter `steps` are the canonical procedure. Local apply +
re-apply (steps 4–5) is the cheap way to catch idempotence bugs before
they hit production.

## Pitfalls

The frontmatter `pitfalls` capture the failure modes worth memorising.
The "editing after prod" rule is the most expensive to violate — it
splits the migration history between "what production has applied" and
"what the source tree says was applied", and that divergence is
extremely painful to recover from.
