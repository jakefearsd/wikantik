# Audit Retention-Purge Design

**Status:** approved 2026-06-03
**Builds on:** [AuditLogDesign](../../wikantik-pages/AuditLogDesign.md) — closes its "retention purge", "`ensurePartition` needs schema CREATE", and keep-forever open items.
**Scope:** a scheduled, out-of-band job that enforces a configured retention window on `audit_log` by archiving-then-dropping over-age monthly partitions, and pre-creates upcoming partitions. Default window **84 months (7 years)**, enabled by default.

## Key realization: no application code changes

The whole feature is a shell script + a systemd timer installer + config + docs + a
script test. Nothing in the Java tier changes, because:

- **Chain integrity survives a purge already.** `JdbcAuditRepository.verifyChain`
  reads rows `ORDER BY seq ASC` and seeds its hash anchor from the **first row it
  reads** (`if (first) prev = rs.getString("prev_hash")`). After old partitions are
  dropped, the oldest *surviving* row anchors the chain, and the remainder validates
  end-to-end. No code change. (Dropped rows are unverifiable by definition; their
  integrity lived in the archive at drop time.)
- **`ensurePartition` stays** as a runtime safety net; pre-creation just keeps it from
  ever firing in practice.
- **Locked grants are untouched** — the app role still cannot purge.

## Privileged role: reuse `migrate`

The app DB role is `INSERT`/`SELECT`-only and deliberately cannot drop anything.
Rather than invent a new role, the retention job runs as the **existing `migrate`
role** (`PGUSER=migrate`, created by `bin/db/create-migrate-user.sh`): it already has
`CREATE` on schema `public` (to pre-create partitions) and owns the partitions that
migrations created (so it can `DROP` them). Separation holds — the *app* role still
cannot purge.

## The script: `bin/db/audit-retention.sh`

Mirrors `bin/db/migrate.sh` conventions (env vars `DB_NAME`, `PGHOST`, `PGPORT`,
`PGUSER` [default `migrate`], `PGPASSWORD`; `--help`/`--status`/`--dry-run` flags;
`set -euo pipefail`). Each run does two jobs:

### 1. Pre-create upcoming partitions

For the current month through `AUDIT_PARTITION_LOOKAHEAD` months ahead (default 3),
`CREATE TABLE IF NOT EXISTS audit_log_YYYY_MM PARTITION OF audit_log FOR VALUES FROM
('<first>') TO ('<next-first>')` — idempotent, identical naming/range to V036.

### 2. Archive-then-drop over-age partitions

Compute the cutoff = first day of the month `AUDIT_RETENTION_MONTHS` ago. For each
existing partition whose entire range is **older than the cutoff** (discovered by
querying `pg_inherits`/`pg_class` for partitions of `audit_log` and parsing their
bounds, or by name `audit_log_YYYY_MM`):

1. `pg_dump --table=audit_log_YYYY_MM` to `${AUDIT_ARCHIVE_DIR}/audit_log_YYYY_MM_<UTCstamp>.dump`.
2. **Verify** the archive: file is non-empty AND `pg_restore --list <file>` parses
   without error.
3. Only then `DROP TABLE audit_log_YYYY_MM`.

Strict ordering: a crash between steps can never drop a partition that was not first
archived and verified. A partition that fails archive/verify is skipped (logged), not
dropped.

### Flags

- `--dry-run` — print the partitions that would be created and the partitions that
  would be archived+dropped; touch nothing.
- `--status` — list current partitions, the configured window, and the cutoff month.

## Config & defaults

| Env var | Default | Meaning |
|---|---|---|
| `AUDIT_RETENTION_MONTHS` | `84` (7 years) | keep this many months; older partitions are purged |
| `AUDIT_PARTITION_LOOKAHEAD` | `3` | months of future partitions to pre-create |
| `AUDIT_ARCHIVE_DIR` | (required for purge) | directory the off-box NAS backup captures |
| `PGUSER` | `migrate` | privileged role (CREATE + owns partitions) |

**Enabled by default at 84 months.** Because the audit log is new, nothing is near 7
years old, so enabling now drops nothing — the first real drop is ~7 years out.

**Guardrail:** the script refuses to run the drop phase if `AUDIT_RETENTION_MONTHS`
is unset or `< 1` (prevents a fat-finger mass-drop). Pre-creation still runs.

## Scheduling: `bin/db/audit-retention-install-timer.sh`

Mirrors `bin/backup/nas-install-timer.sh`: `tee`s a `wikantik-audit-retention.service`
(`ExecStart=<repo>/bin/db/audit-retention.sh`) and a `.timer`
(`OnCalendar` default **monthly**, e.g. `*-*-01 04:00:00` — first of the month),
`systemctl daemon-reload` + `enable --now`. Manual runs and the timer both call the
same script. Targets docker1, consistent with the backup timer.

## Archive destination

`AUDIT_ARCHIVE_DIR` is a directory the existing off-box NAS backup already pulls, so
purged history is cold-stored off-box rather than lost.

## Testing: `bin/tests/test-audit-retention.sh`

Mirrors `bin/tests/test-backup.sh` — pure filesystem + stubbed `psql`/`pg_dump`/
`pg_restore` on `PATH`, no real PostgreSQL (`set -euo pipefail`, `fail()` + `trap`
cleanup). The stubs record the SQL/commands the script issues so assertions can check
them. Cases:

1. **Pre-create:** the script issues `CREATE TABLE IF NOT EXISTS` for the current +
   lookahead months with correct names/ranges.
2. **Archive-then-drop ordering:** for a partition older than the window, the script
   calls `pg_dump` for that partition **before** `DROP TABLE`, and the `DROP` is
   issued only after a successful (stubbed) `pg_restore --list`.
3. **In-window untouched:** a partition inside the window is never dropped.
4. **`--dry-run`:** no `CREATE`/`DROP`/`pg_dump` with side effects are issued.
5. **Guardrail:** with `AUDIT_RETENTION_MONTHS=0` (or unset) the drop phase refuses.
6. **Archive-verify failure:** if the stub `pg_restore --list` fails, the partition is
   NOT dropped.

Wire `test-audit-retention.sh` into wherever `test-backup.sh` is invoked (CI / the
test runner) so it runs with the suite.

## Decisions (recorded)

1. **Reuse the `migrate` role** (CREATE + owns partitions) — no new role.
2. **Explicit `pg_dump`-per-partition archive, verified, before drop** — self-evidently
   safe; matches the v1 "archive-first" intent.
3. **Monthly `systemd` timer** mirroring the backup installer.
4. **Enabled by default at 84 months**, with a `< 1` guardrail.
5. **No application code** — `verifyChain` already anchors on the oldest surviving row.

## Out of scope / next

- A migration that *changes* the schema — none needed (partitions are created at
  runtime by the script/`ensurePartition`, not by a versioned migration).
- An admin UI / REST surface for retention — it's an operator job (script + timer),
  consistent with `migrate.sh` and the backup tooling.
- Per-category retention (different windows for `read` vs `admin`) — single window v1.
- Restoring an archived partition back into `audit_log` — documented as a manual
  `pg_restore` runbook step, not automated.
