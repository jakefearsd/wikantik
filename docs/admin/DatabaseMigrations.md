# Database Migrations

This guide is for operators who apply schema changes to a Wikantik PostgreSQL database
and for developers who add them. It covers how the migration runner works, how to apply
and check migrations, the role model for least-privilege production deployments, how to
add a migration, what the runner does not do, and the migration history.

The schema is defined only by the numbered files in
[`bin/db/migrations/`](../../bin/db/migrations/) (the conventions are in its
[README](../../bin/db/migrations/README.md)). The `postgresql*.ddl` snapshots were retired
on 2026-08-22, and the unit-test fixture `PostgresTestDb` applies the same files to a
throwaway pgvector container, so there is no second schema definition to keep in step.
For bootstrapping a server, see [PostgreSQL.md](PostgreSQL.md).

## How migrations run

`bin/db/migrate.sh` applies every `V*.sql` file in `bin/db/migrations/` that is not yet
recorded in the `schema_migrations` ledger (`version VARCHAR(64) PRIMARY KEY`,
`applied_at TIMESTAMP`), in version order. Each migration runs in its own transaction
(`psql --single-transaction`), and its version is recorded only after it succeeds; a
failure rolls that migration back and stops the run. Re-running against an up-to-date
database is a no-op.

| Variable | Default | Meaning |
|----------|---------|---------|
| `DB_NAME` | `wikantik` | Target database |
| `DB_APP_USER` | `wikantik` | Role granted DML access; exposed to migrations as the psql variable `:app_user` |
| `PGHOST` / `PGPORT` | `localhost` / `5432` | Connection |
| `PGUSER` | `migrate` | Role that runs the migrations. Set `PGUSER=postgres` if you have not provisioned the `migrate` role. |
| `PGPASSWORD` | unset | Optional; `~/.pgpass` works too |
| `DB_EXPORTER_PASSWORD` | unset | Passed to V031 as `:exporter_password`. When unset, V031 leaves the `wikantik_exporter` role `NOLOGIN`. |

Which role runs `migrate.sh` depends on how you deploy:

- **Bare metal:** `bin/deploy-local.sh` and `bin/redeploy.sh` run it automatically as the
  app role (`PGUSER=${POSTGRES_USER}` from `.env`, default `wikantik`), falling back to
  `PGUSER=postgres`. Neither hard-codes `PGUSER=migrate`.
- **Container:** `docker/entrypoint.sh` runs it on every start as `POSTGRES_USER` from
  `.env`, and never starts Tomcat against an out-of-date schema if it fails.
- **Manual or production:** run it yourself with `PGUSER=migrate` (the default).

## Apply and check migrations

```bash
bin/db/migrate.sh              # apply pending migrations
bin/db/migrate.sh --status     # list applied versions

# Production, with connection variables set
DB_NAME=wikantik PGHOST=db.example.com PGUSER=migrate PGPASSWORD='…' bin/db/migrate.sh
```

For a running container, without restarting Tomcat:

```bash
bin/container.sh -e prod migrate            # apply pending migrations
bin/container.sh -e prod migrate --status
bin/remote.sh migrate [--status]            # same, on the remote host over ssh
```

`bin/db/install-fresh.sh` is the fresh-database path: it creates the database and
application role, runs `migrate.sh`, and optionally provisions the `migrate` role. It
requires either `DB_MIGRATE_PASSWORD` or `--no-migrate-role`.

## Roles

Production deployments split database privileges so the application role never issues
DDL at runtime:

| Role | Used when | Privileges |
|------|-----------|------------|
| `postgres` (superuser) | Initial provisioning | Creates the database, the app role and the `vector` extension |
| `migrate` | Every manual or production migration run | `CREATE`/`USAGE` on schema `public`, `CREATEROLE`, `pg_monitor WITH ADMIN OPTION`, membership in the app role, and ownership of the public-schema tables |
| `wikantik` (app role) | Application runtime | `SELECT/INSERT/UPDATE/DELETE` on tables, `USAGE/SELECT` on sequences |
| `wikantik_exporter` | Metrics scraping (V031) | `pg_monitor` membership; `NOLOGIN` unless `DB_EXPORTER_PASSWORD` was set when V031 ran |

The `migrate` role needs `CREATEROLE` and `pg_monitor WITH ADMIN OPTION` because V031
creates `wikantik_exporter` and grants it `pg_monitor`. It needs table ownership so later
`ALTER TABLE` migrations succeed and so it can write the `schema_migrations` ledger.

### Provision the migrate role

Run as a PostgreSQL superuser. Either pass `DB_MIGRATE_PASSWORD` to `install-fresh.sh`
(which runs the provisioning step for you), or run the step on its own:

```bash
sudo -u postgres DB_NAME=wikantik DB_APP_USER=wikantik \
    DB_MIGRATE_PASSWORD='<strong-password>' \
    bin/db/create-migrate-user.sh
```

`create-migrate-user.sh` is idempotent: re-running it after a password rotation refreshes
the password and re-applies the grants. It requires `DB_MIGRATE_PASSWORD` and refuses to
set a default. It does not install extensions (the superuser installs `vector` once,
before V004) and does not create the database. It transfers ownership of existing
public-schema tables and sequences with a targeted loop, not a blanket `REASSIGN OWNED`.

Provisioning the role is optional for local development. It matters when you want to
exercise the same least-privilege path as production.

### What is and is not implemented

| Piece | Status |
|-------|--------|
| `migrate` role and `create-migrate-user.sh` | Implemented |
| `migrate.sh` defaulting to `PGUSER=migrate` | Implemented |
| `wikantik_exporter` monitoring role (V031) | Implemented |
| `migrate.sh --baseline <version>` (record migrations as applied without running them) | Not implemented; `migrate.sh` accepts only `--status` and `--help` and exits 2 on anything else |
| Checksum verification of applied migrations | Not implemented; `schema_migrations` has only `version` and `applied_at` |
| Secret-store integration for the `migrate` password | Not implemented; the password comes from `~/.pgpass` or `PGPASSWORD` on the deploy host |

Because there is no baseline flag, a database that predates the ledger is brought forward
by relying on migrations being idempotent, so running one against a database that already
matches it is a no-op. A migration that inserts seed data or drops a column without a
guard would break that assumption, so write every migration to be safe to re-run.

## Add a migration

Every commit that changes the database schema must add the next numbered migration.

1. Pick the next number: the highest `V*.sql` in `bin/db/migrations/` plus one.
2. Create `V<NNN>__<snake_case_description>.sql`. One migration is one logical change.
3. Write idempotent DDL: `CREATE TABLE IF NOT EXISTS`, `CREATE INDEX IF NOT EXISTS`,
   `ADD COLUMN IF NOT EXISTS`, `INSERT … ON CONFLICT DO NOTHING`, `INSERT … WHERE NOT EXISTS`.
4. Use the `:app_user` psql variable for grants; never hard-code a role name.
5. Document prerequisites (an extension, an earlier migration) at the top of the file.
6. Run `bin/db/migrate.sh` against a local database, then run it a second time and
   confirm it is a no-op.
7. Commit the migration in the same commit as the code that needs it.

Rules that follow from the ledger:

- **Never edit a migration after it has been applied outside local development.** Fix
  mistakes with a follow-up migration; the ledger assumes append-only history.
- **Keep migrations DDL-only and fast.** Long data backfills belong in application code
  or in a one-shot script, not in a migration that holds locks.
- **Data backfills are not versioned migrations.** One-off data fixups live in
  `bin/db/one-shots/` and are run by hand against the target database, never through
  `migrate.sh`. Review each before running; most are idempotent but modify data.
- **Test against real PostgreSQL.** A test that needs a table is a reason to write the
  migration first. Never hand-write `CREATE TABLE` in a test (`TestSchemaSingleSourceTest`
  in `wikantik-war` enforces this).

## Migration history

The migration directory is the source of truth; this table summarises it through V060.

| Migration | What it adds |
|-----------|--------------|
| V001 | `schema_migrations` ledger, which bootstraps the runner itself |
| V002 | Core auth: `users`, `roles`, `groups`, `group_members`, plus the default `admin` account and `Admin` group |
| V003 | `policy_grants`: database-backed authorisation |
| V004 | Knowledge Graph baseline: `kg_nodes`, `kg_edges`, `kg_proposals`, `kg_rejections`; installs the `vector` extension |
| V005 | `hub_centroids`, `hub_proposals` |
| V006, V007 | `hub_discovery_proposals` and status tracking |
| V008 | `kg_content_chunks`: page-passage chunking for retrieval |
| V009 | `content_chunk_embeddings`: Ollama-backed dense embeddings, stored as `BYTEA` little-endian float32 (V032 later adds the pgvector column alongside it) |
| V010 | `api_keys`: bearer-token auth for the MCP and tools servers |
| V011 | `chunk_entity_mentions`: joins KG nodes to chunks |
| V012 | Retire the legacy graph projector; replaced by direct `kg_edges` writes |
| V013 | `page_canonical_ids`, `page_slug_history`: rename-stable identifiers |
| V014 | `page_verification` and runbook tables |
| V015 | Deduplicate user profiles (one-time data fix) |
| V016 | `retrieval_query_sets`, `retrieval_queries`, `retrieval_runs`: retrieval-quality CI |
| V017 | Seeds the default retrieval query set |
| V018 | `kg_cluster_policy`, `kg_policy_audit`, `kg_excluded_pages`: KG inclusion policy |
| V019 | Drop the legacy `kg_embeddings` and `kg_content_embeddings` tables |
| V020 | `kg_proposals.signature`: dedupe column for the entity extractor |
| V021, V022 | `kg_node_embeddings` and `model_code` |
| V023 | Drop `page_relations`; typed relations frontmatter was retired in favour of `canonical_id`, cluster and tags |
| V024 | KG staged validation tables |
| V025 | KG judge timeout tracking |
| V026 | Seed additional retrieval query-set rows |
| V027 | `kg_edges_relationship_type_check`: restrict `relationship_type` to the allowed vocabulary |
| V028 | `kg_edge_audit`: audit trail for KG edge create, update and delete |
| V029 | Add the `CONFIRM` action to the `kg_edge_audit` CHECK |
| V030 | Allow `generalizes` in the `kg_edges` relationship-type CHECK |
| V031 | `wikantik_exporter` role for metrics scraping |
| V032 | `content_chunk_embeddings.embedding vector(1024)` and an HNSW index; the pgvector dual-write |
| V033 | `comment_threads`, `comments` |
| V034 | `page_owners`, `comment_mentions` |
| V035 | Seed the `agents` service account, the default owner of agent-authored pages |
| V036 | `audit_log`: tamper-evident hash-chained action record, monthly-partitioned |
| V037 | Widen `audit_log.detail` to `TEXT` |
| V038 | `drift_sweeps`, `drift_snapshot_counts`: the `/admin/drift` burn-down |
| V039 | `users.password_must_change`; flags the seeded default admin |
| V040 | `citations`: version-pinned, span-hashed citation edges from `cite://` markup |
| V041 | `retrieval_query_log`: append-only capture of real retrieval queries |
| V042 | `users.last_login` |
| V043 | Canonicalise the admin `AllPermission` policy grant |
| V044 | `briefing_log`: context-briefing telemetry |
| V045 | `bundle_eval_run`: scheduled bundle-eval results |
| V046 | `connector_sync_state`: per-connector cursor and hash state |
| V047 | `connector_credentials`: encrypted connector secret store |
| V048 | `connector_configs`: admin-managed connector definitions |
| V049 | `connector_sync_run`: per-run connector sync history |
| V050 | `search_visibility_snapshot`: Content Intelligence search-visibility facts |
| V051 | `retrieval_query_log` demand-signal columns |
| V052 | `content_change_log`: effect-measurement ledger for applied content changes |
| V053 | `content_opportunity_snooze` |
| V054 | `content_opportunity_seen` |
| V055 | `content_change_log` calibration columns |
| V056 | `imported_opportunity` |
| V057 | `expected_ctr_curve` |
| V058 | Widen the `api_keys.scope` CHECK to admit the `mcp_read` scope |
| V059 | Pre-create `audit_log` monthly partitions through 2028-12, so a least-privilege app role never has to create one |
| V060 | Add the `export` wiki permission to the `Authenticated` grant (only while that row still holds the stock actions) |

The other `.sql` files directly under `bin/db/` (`migration-1.0-to-1.1.sql`,
`cleanup-2026-04-30-stale-canonical-ids.sql`, `normalize-relationship-types.sql`) are
historical one-offs, not part of the ledger.

## Known unused index

`idx_kg_nodes_properties` (a GIN index on `kg_nodes.properties`, created by V004) is not used by application queries: `KgNodeRepository` searches with `LOWER( n.properties::text ) LIKE ?`, which bypasses a GIN index. Using it would need JSONB containment (`@>`); until then it only costs writes. The other V004 edge indexes are not flagged here: `idx_kg_edges_type` backs the `relationship_type` filter in `KgEdgeRepository`.

## Troubleshoot

| Symptom | Likely cause | Fix |
|---------|--------------|-----|
| `Could not connect to database … as migrate@…` | The `migrate` role does not exist yet, or connection variables are wrong | Provision it (above), or run with `PGUSER=postgres`; check `PGHOST`/`PGPORT`/`PGPASSWORD`/`DB_NAME` |
| `permission denied to create role` on V031; deploy aborts and Tomcat does not start | The role running `migrate.sh` lacks `CREATEROLE` | Run `bin/db/create-migrate-user.sh` as a superuser |
| `must have admin option on role "pg_monitor"` on V031 | `migrate` is not a `pg_monitor` admin | Same provisioning step; it grants `pg_monitor WITH ADMIN OPTION` |
| `permission denied for table schema_migrations` after a migration's DDL ran | `migrate` cannot write the ledger (table owned by another role) | Run `create-migrate-user.sh` (its ownership transfer covers `schema_migrations`), or as a superuser `GRANT INSERT,SELECT,UPDATE,DELETE ON schema_migrations TO migrate;` |
| `must be owner of table X` during an `ALTER` migration | The table is owned by a role other than the one running migrations | Run `create-migrate-user.sh`, or run `migrate.sh` as the table owner |
| `extension "vector" is not available` on V004 | pgvector is not installed on the server | See [PostgreSQL.md](PostgreSQL.md#install-pgvector) |
| A migration fails midway | The migration's transaction rolled back and nothing was recorded | Read the error, fix the cause, and re-run `migrate.sh`; the retry is safe |
| `Unknown argument: --baseline` (exit 2) | `--baseline` was never implemented | Only `--status` and `--help` exist |

## When to consider Flyway, Liquibase or Sqitch

The bash runner is enough until one of these becomes true: you need repeatable migrations
(views or functions reapplied on change), you want a pending report that also validates
checksums without executing, a second application shares the database and needs
coordinated history, or the team grows to the point where a human runbook stops scaling.
Until then the runner means no new dependency and a script a newcomer can read
top-to-bottom in a few minutes.
