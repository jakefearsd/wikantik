# Production Database Workflow — Future-State Play

**Status:** Design. Not yet implemented.
**Goal:** Make production schema changes routine, auditable, and repeatable
without granting the application role privileges it should not have.
**Non-goal:** Replace the current `migrate.sh` with a full-featured migration
framework (Flyway, Liquibase, Sqitch). Those remain options if this play
hits its limits.

## Motivation

The April 2026 release exposed three friction points in the current workflow:

1. **One role does everything.** `jspwiki` is the app role, but it is also
   the role that ran `CREATE EXTENSION vector`, created tables, and held
   ownership. The principle of least privilege says the app role should
   never be able to issue DDL at runtime.
2. **No baseline.** `migrate.sh` has no way to say "this database already
   matches V005 — just record it as applied, don't run it." V002–V005 are
   idempotent by design, so re-running them happens to be safe today, but
   the moment a future migration includes `INSERT`s of seed data or a
   `DROP COLUMN`, idempotence stops saving us.
3. **Files and credentials on disk.** The migration scripts live in the
   same repo as the app and are applied from whichever host has the git
   checkout, with the DB password in an environment variable typed by a
   human. There is no shared "this is how prod gets updated" runbook.

None of these is on fire. All three will bite eventually.

## End state in one picture

```
          ┌────────────────────────┐
          │   Secret store         │   ← Vault / AWS SM / 1Password
          │   (migrate creds)      │
          └───────────┬────────────┘
                      │ short-lived pw
                      ▼
┌──────────────┐   run migrate.sh   ┌──────────────────────────┐
│ Deploy host  │ ─────────────────► │ PostgreSQL               │
│ or CI runner │                    │                          │
└──────────────┘                    │  roles:                  │
                                    │    postgres  (superuser) │
                                    │    migrate   (owns DDL)  │
                                    │    jspwiki   (DML only)  │
                                    │                          │
                                    │  extensions installed    │
                                    │  once by superuser       │
                                    └──────────────────────────┘
```

The human never types a password. `jspwiki` can't alter tables. The
superuser only gets used during initial provisioning.

## The five pieces of the play

### 1. Split the database roles

Add a dedicated `migrate` role between the superuser and the app role.

| Role        | Used when          | Privileges                             |
|-------------|--------------------|----------------------------------------|
| `postgres`  | Initial provision  | Superuser. Installs extensions only.   |
| `migrate`   | Every deploy       | `CREATE` on schema, owns all tables.   |
| `jspwiki`   | Application runtime| `SELECT/INSERT/UPDATE/DELETE` on its tables and `USAGE/SELECT` on sequences. No DDL. |

Concretely:

```sql
-- One-time, as superuser
CREATE ROLE migrate  WITH LOGIN PASSWORD :'migrate_password';
CREATE ROLE jspwiki  WITH LOGIN PASSWORD :'app_password';

GRANT CONNECT ON DATABASE wikantik TO migrate, jspwiki;

-- migrate becomes the owner of new objects
ALTER DEFAULT PRIVILEGES FOR ROLE migrate IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES    TO jspwiki;
ALTER DEFAULT PRIVILEGES FOR ROLE migrate IN SCHEMA public
    GRANT USAGE, SELECT                  ON SEQUENCES TO jspwiki;
```

Migrations then stop containing per-object `GRANT … TO :app_user`
boilerplate — the default-privilege rules do it automatically.

### 2. Pull extensions out of migrations

`V004` currently runs `CREATE EXTENSION IF NOT EXISTS vector`. The
`migrate` role won't have permission to do that, and we don't want it
to. Move extension installation into a one-time provisioning script:

```
bin/db/
├── provision.sh         # NEW — run once by a superuser
├── migrate.sh           # run every deploy by the migrate role
└── migrations/
```

`provision.sh` creates the database, the two roles, installs `vector`
and `pgcrypto`, and hands ownership of the public schema to the
`migrate` role. After that, superuser credentials are not needed again
for ordinary deploys.

`V004` gets simplified to drop the `CREATE EXTENSION` line. Document
the extension as a prerequisite in the migration's header comment.

### 3. Add a baseline command to `migrate.sh`

New flag: `migrate.sh --baseline <version>`. It inserts every migration
up to and including `<version>` into `schema_migrations` **without
executing any of them**. This is how an existing production DB catches
up to the migration system the first time, and how we handle any future
"this migration describes reality, don't run it again" situation.

```
$ ./migrate.sh --baseline V005
! baselining — the following will be recorded as applied without running:
    V001__schema_migrations
    V002__core_users_groups
    V003__policy_grants
    V004__knowledge_graph
    V005__hub_membership
Continue? [y/N]
```

The confirmation prompt is load-bearing — baselining the wrong version
silently skips real schema work.

### 4. Add a checksum column to `schema_migrations`

Edits to an already-applied migration are a footgun the current
tracker doesn't catch. Extend `schema_migrations`:

```sql
ALTER TABLE schema_migrations
    ADD COLUMN IF NOT EXISTS checksum      TEXT,
    ADD COLUMN IF NOT EXISTS applied_by    TEXT,
    ADD COLUMN IF NOT EXISTS execution_ms  INTEGER;
```

`migrate.sh` computes `sha256sum` of each migration on disk. On every
run, it compares the stored checksum with the on-disk checksum for
already-applied versions. Mismatch aborts the run with a clear error
naming the file. Fix-forward only — the operator must write a new
migration, not edit history.

`applied_by` records the DB role and hostname. `execution_ms` helps
spot a migration that is slowing down as the table grows.

### 5. Credentials come from a secret, not a shell variable

Replace `PGPASSWORD='…' ./migrate.sh` with a pattern where the password
is fetched at run time from the org secret store and never lands in
shell history or the process list.

Suggested mechanisms in order of preference:

1. **IAM auth** (RDS IAM token, GCP Cloud SQL IAM). No stored password
   at all; the migrate process mints a 15-minute token.
2. **Short-lived secret from Vault / AWS Secrets Manager / 1Password
   CLI**, loaded via `op run --env-file`, `aws-vault exec`, or a
   Vault agent.
3. **`.pgpass` with `chmod 600`** on the deploy host, owned by the
   deploy user. Low-tech baseline if options 1–2 aren't set up yet.

`migrate.sh` itself doesn't need to care which of these is in use — it
just reads `PGPASSWORD` from the environment or relies on `.pgpass`.
The wrapper around it is what changes.

## Operator-facing runbook (end state)

### Initial provisioning (once per DB)

```bash
# On the DB host, as postgres superuser
sudo -u postgres \
    DB_NAME=wikantik \
    MIGRATE_PASSWORD='…' \
    APP_PASSWORD='…' \
    bin/db/provision.sh
```

Creates database, roles, extensions. Idempotent so it's safe to re-run
after a password rotation.

### Every deploy

```bash
# On the deploy host or CI runner
aws-vault exec wikantik-prod -- \
    DB_NAME=wikantik \
    PGUSER=migrate \
    PGHOST=db.example.com \
    bin/db/migrate.sh
```

The wrapper injects a short-lived `PGPASSWORD`. `migrate.sh`:

1. Reads `schema_migrations`.
2. Verifies checksums of already-applied migrations.
3. Applies any pending migration under `lock_timeout = 5s`,
   `statement_timeout = 5min`, in a single transaction.
4. Records version, checksum, duration, and `applied_by`.

### One-time baseline of an existing DB

```bash
# First use of the migrate role against a DB that already has the tables
bin/db/migrate.sh --baseline V005
```

## Implementation checklist

Rough order. Each step is independently shippable.

- [ ] **Provision.sh** — extract the existing `install-fresh.sh` role
      creation into a superuser-only script; stop granting DDL to
      `jspwiki`; install `vector` and `pgcrypto` here.
- [ ] **Split ownership** — add `migrate` role; transfer table
      ownership on existing DBs; set default privileges; drop per-table
      `GRANT … TO :app_user` from V002–V005 once default privileges
      are in place.
- [ ] **Baseline flag** — implement `migrate.sh --baseline <version>`
      with a confirm prompt and an idempotent insert.
- [ ] **Checksum + metadata** — add the columns, fill them on apply,
      verify them on every run, ship a migration that backfills
      checksums for already-applied versions by re-hashing the
      on-disk files.
- [ ] **Lock/statement timeouts** — prepend `SET lock_timeout`,
      `SET statement_timeout` to each migration transaction inside
      `migrate.sh` (not in every `.sql` file).
- [ ] **Drop `CREATE EXTENSION` from V004** — add a follow-up
      migration that comments its removal; update `migrations/README.md`
      to point at `provision.sh`.
- [ ] **Secret-store wrapper** — pick one of IAM / Vault / `.pgpass`,
      document it in this file, wire it into whatever CI/deploy
      mechanism you adopt.
- [ ] **Pre-migrate backup hook** — deploy script takes a snapshot or
      `pg_dump -Fc` before invoking `migrate.sh`, keeps the last N
      automatically.

## When to consider Flyway / Liquibase / Sqitch instead

The bash script is fine until one of these becomes true:

- You need **repeatable migrations** (idempotent views/functions that
  should be reapplied when changed — Flyway `R__` files).
- You want **dry-run / pending report** that also validates checksums
  without executing.
- You add a **second application** sharing the same DB and need
  coordinated schema history.
- You want **undo** support for DDL (rarely worth it in practice).
- The team grows past one developer and a human runbook stops scaling.

Until then, keeping `migrate.sh` means zero new dependencies and a
script a newcomer can read top-to-bottom in five minutes.

## Open questions for future-self

- Where do the `migrate` role credentials live today vs. at end state?
  Need a concrete choice (Vault? AWS Secrets Manager? host `.pgpass`?)
  before writing the wrapper.
- Do we need a separate **read-only** role for analytics / backup
  tooling? Probably yes; add `wikantik_ro` alongside `jspwiki` when
  split role work happens.
- pg_dump / pg_restore authorization with the new role split — the
  backup role should be distinct from `migrate` so a compromised
  backup credential can't mutate schema.
- How does `install-fresh.sh` evolve? Either delete it (provision.sh
  subsumes it) or keep it as a dev-only convenience that wraps
  provision.sh + a migrate.
