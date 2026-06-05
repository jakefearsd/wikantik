# Production Database Workflow

**Status (2026-06-05):**

| Piece | Status |
|-------|--------|
| `migrate` role + `create-migrate-user.sh` | **SHIPPED** |
| `migrate.sh` runs as `PGUSER=migrate` | **SHIPPED** |
| V031 monitoring role (`wikantik_exporter`) | **SHIPPED** |
| `--baseline` flag for `migrate.sh` | **PENDING** |
| Checksum column on `schema_migrations` | **PENDING** |
| Secret-store wrapper (Vault / IAM / `.pgpass`) | **PENDING** |

The `migrate` role split and the V031 monitoring role are in production. The
`baseline`, `checksum`, and secret-store pieces from the original design remain
unimplemented.

**Goal:** Make production schema changes routine, auditable, and repeatable
without granting the application role (`jspwiki`) privileges it should not have.

---

## Motivation

The April 2026 release exposed three friction points in the current workflow:

1. **One role does everything.** `jspwiki` was the app role and previously
   also the role that created tables and held ownership. Least-privilege says
   the app role should never issue DDL at runtime. The `migrate` role split
   resolves this.
2. **No baseline.** `migrate.sh` has no way to say "this database already
   matches V005 — just record it as applied, don't run it." V002–V005 are
   idempotent by design, so re-running them happens to be safe today, but
   a future migration that contains `INSERT`s of seed data or a `DROP COLUMN`
   would not be.
3. **Credentials in a shell variable.** There is no shared secret-store
   integration yet; the `migrate` password lives in `.pgpass` on the deploy
   host.

The role split (item 1) is done. Items 2 and 3 are still open.

---

## End state in one picture

```
          ┌────────────────────────┐
          │   Secret store         │   ← Vault / AWS SM / 1Password (PENDING)
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
                                    │    wikantik_exporter     │
                                    │      (pg_monitor, V031)  │
                                    │                          │
                                    │  extensions installed    │
                                    │  once by superuser       │
                                    └──────────────────────────┘
```

---

## Role split — shipped

### Database roles

| Role | Used when | Privileges |
|------|-----------|------------|
| `postgres` | Initial provision | Superuser. Installs extensions only. |
| `migrate` | Every deploy | `CREATE` on schema, owns all tables. |
| `jspwiki` | Application runtime | `SELECT/INSERT/UPDATE/DELETE` on tables + `USAGE/SELECT` on sequences. No DDL. |
| `wikantik_exporter` | Prometheus scrape (V031) | `pg_monitor` membership. No login by default; `migrate.sh` sets a password if `exporter_password` is provided. |

### Bootstrapping the migrate role (one time per DB)

Run as a PostgreSQL superuser after `install-fresh.sh`:

```bash
DB_NAME=wikantik \
DB_MIGRATE_USER=migrate \
DB_MIGRATE_PASSWORD='<strong-password>' \
DB_APP_USER=jspwiki \
    bin/db/create-migrate-user.sh
```

`create-migrate-user.sh` is fully idempotent — re-running it after a password
rotation just refreshes the password and re-applies grants. `DB_NAME` defaults
to `wikantik` (matching `install-fresh.sh` and `migrate.sh`), so the explicit
`DB_NAME=wikantik` above is only needed when your database has a non-default
name.

### Every deploy

`bin/redeploy.sh` and `bin/deploy-local.sh` both invoke `migrate.sh` automatically.
The redeploy path uses `PGUSER=migrate`; if the migrate role is not yet provisioned,
it falls back to `PGUSER=postgres`.

Manual run:

```bash
DB_NAME=wikantik PGUSER=migrate bin/db/migrate.sh
```

---

## V031 monitoring role — shipped

`V031__monitoring_role.sql` creates the `wikantik_exporter` role with `pg_monitor`
membership so it can read `pg_stat_*` views without superuser access. The Prometheus
node exporter / postgres_exporter uses this role.

The migration requires that the `migrate` role holds `pg_monitor WITH ADMIN OPTION`
(set by `create-migrate-user.sh`) so it can in turn grant `pg_monitor` to
`wikantik_exporter`.

---

## Pending items (original design)

### Baseline flag for migrate.sh (PENDING)

Planned: `migrate.sh --baseline <version>` inserts every migration up to and
including `<version>` into `schema_migrations` without executing any SQL. This
is how an existing production DB catches up to the migration ledger the first
time.

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

### Checksum column on schema_migrations (PENDING)

Planned extension:

```sql
ALTER TABLE schema_migrations
    ADD COLUMN IF NOT EXISTS checksum      TEXT,
    ADD COLUMN IF NOT EXISTS applied_by    TEXT,
    ADD COLUMN IF NOT EXISTS execution_ms  INTEGER;
```

`migrate.sh` would compute `sha256sum` of each migration on disk and abort if an
already-applied migration's on-disk checksum does not match the stored value.

### Secret-store wrapper (PENDING)

Current state: the `migrate` password lives in `.pgpass` (or `PGPASSWORD` env var)
on the deploy host.

Planned options in order of preference:
1. **IAM auth** (RDS IAM token, GCP Cloud SQL IAM) — no stored password at all.
2. **Short-lived secret from Vault / AWS Secrets Manager / 1Password CLI**.
3. **`.pgpass` with `chmod 600`** on the deploy host — current low-tech baseline.

---

## Current operator runbook

### Initial provisioning (once per DB)

```bash
# 1. Create DB, app role, extensions, run all migrations
sudo -u postgres DB_NAME=wikantik DB_APP_USER=jspwiki \
    DB_APP_PASSWORD='<app-password>' \
    bin/db/install-fresh.sh

# 2. Create the migrate role and transfer ownership
DB_NAME=wikantik DB_MIGRATE_USER=migrate \
    DB_MIGRATE_PASSWORD='<migrate-password>' \
    DB_APP_USER=jspwiki \
    bin/db/create-migrate-user.sh
```

### Every deploy

```bash
# Handled automatically by bin/redeploy.sh or bin/deploy-local.sh.
# To run manually:
DB_NAME=wikantik PGUSER=migrate bin/db/migrate.sh
```

### Check migration status

```bash
DB_NAME=wikantik PGUSER=migrate bin/db/migrate.sh --status
```

---

## When to consider Flyway / Liquibase / Sqitch instead

The bash script is fine until one of these becomes true:

- You need **repeatable migrations** (idempotent views/functions reapplied on change).
- You want **dry-run / pending report** that also validates checksums without executing.
- You add a **second application** sharing the same DB and need coordinated schema history.
- The team grows past one developer and a human runbook stops scaling.

Until then, keeping `migrate.sh` means zero new dependencies and a script a newcomer
can read top-to-bottom in five minutes.

---

## Open questions

- Where do `migrate` role credentials live in the final state? Need a concrete choice
  (Vault? AWS Secrets Manager? host `.pgpass`?) before writing the wrapper.
- Do we need a separate **read-only** role for analytics / backup tooling? Probably
  yes — add `wikantik_ro` alongside `jspwiki` when a clean role audit happens.
- pg_dump / pg_restore authorization with the new role split — the backup role should
  be distinct from `migrate` so a compromised backup credential cannot mutate schema.
