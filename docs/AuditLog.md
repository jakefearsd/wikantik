# Audit Log

Wikantik records authentication, authorization, content, and administrative
events in a tamper-evident audit log. The log is append-only at the database
level and uses a SHA-256 hash chain so any modification or deletion of committed
rows is detectable.

The implementation lives in:
- `AdminAuditResource` — REST endpoints at `/admin/audit`
- `AdminAuditPage.jsx` — admin UI
- `AuditService`, `JdbcAuditRepository`, `AuditChainHasher` — Java tier
- `V036__audit_log.sql`, `V037__audit_log_detail_text.sql` — schema migrations
- `bin/db/audit-retention.sh`, `bin/db/audit-retention-install-timer.sh` — retention tooling

## What gets logged

The `AuditEventListener` translates Wikantik's `WikiEvent` stream into audit
entries. Events are grouped into five categories (`AuditCategory` enum):

| Category | Event types recorded |
|---|---|
| `AUTHN` | `login.ok`, `login.failed`, `logout`, `session.expired` |
| `AUTHZ` | `access.denied` |
| `CONTENT` | Page save, page delete |
| `ADMIN` | `group.member.add`, `group.member.remove`, `profile.save`, user lifecycle (lock/unlock), API key issuance (`apikey.issue`), SCIM operations (`scim.user.create`, `scim.user.update`, `scim.group.create`, `scim.group.update`, `scim.group.delete`) |

Each audit entry carries: `seq`, `created_at`, `event_time`, `category`,
`event_type`, `actor_id`, `actor_principal`, `actor_type`, `target_type`,
`target_id`, `target_label`, `outcome` (`SUCCESS`, `FAILURE`, or `DENIED`),
`source_ip`, `user_agent`, `correlation_id`, `detail`, `prev_hash`, `row_hash`.

## Hash-chain integrity model

Each row carries two hash fields:

- **`prev_hash`** — the `row_hash` of the immediately preceding row (or a
  genesis constant for the first row).
- **`row_hash`** — SHA-256 of the row's canonical fields concatenated with
  `prev_hash`. Computed in `AuditChainHasher`.

This forms a linked chain: modifying any row changes its `row_hash`, which
invalidates every subsequent `prev_hash`, making tampering immediately detectable
on the next verify run.

Writes are serialized by a PostgreSQL advisory transaction lock
(`pg_advisory_xact_lock`) to prevent concurrent inserts from breaking the chain
order. The app role (`wikantik`) is granted only `INSERT` and `SELECT` on
`audit_log`; `UPDATE` and `DELETE` are explicitly revoked by `V036`:

```sql
GRANT  SELECT, INSERT ON audit_log TO :app_user;
REVOKE UPDATE, DELETE ON audit_log FROM :app_user;
```

The `detail` column was changed from `JSONB` to `TEXT` in `V037` because
PostgreSQL JSONB reformats JSON on storage, which would have broken hash
verification (the hash is computed over the raw string at write time).

After a partition is dropped by the retention job (see below), `verifyChain`
re-anchors on the oldest surviving row — purged history is only verifiable
from the archived dump files.

## Schema

The `audit_log` table is partitioned by `created_at` (`RANGE` partitioning,
monthly partitions). `V036` creates three initial partitions
(`audit_log_2026_06`, `audit_log_2026_07`, `audit_log_2026_08`). The writer
also calls `ensurePartition` before every batch insert, so a missing partition
is created dynamically at runtime as a safety net.

The monotonic chain order comes from the sequence `audit_log_seq`.

## Admin UI

Navigate to **Admin → Audit Log** (`/admin/audit` in the React SPA). The page
requires the `Admin` role.

### Searching

Set one or more filters and click **Search**:

| Filter | Description |
|---|---|
| Actor | Login name of the acting user or system (e.g. `admin`, `scim`) |
| Category | `authn`, `authz`, `content`, `admin`, or blank for all |
| Event type | Exact event type string (e.g. `login.failed`, `apikey.issue`) |
| Target | Target entity id (e.g. a page name or user login) |
| Outcome | `success`, `failure`, or `denied` |
| From / To | Date-time range filter on `created_at` |

Results are returned newest-first, up to 1000 entries per page (default 100).
Use the `beforeSeq` cursor parameter in the REST API for pagination beyond the
default limit.

### Verify integrity

Click **Verify integrity** to call `GET /admin/audit/verify`. The endpoint
walks the full chain in `seq` order and reports either:

- `{ "ok": true }` — all row hashes verified.
- `{ "ok": false, "firstBrokenSeq": 42 }` — the chain is broken at seq 42.

The UI displays a green banner on success or a red banner with the broken
sequence number on failure.

### Export CSV

Click **Export CSV** or call `GET /admin/audit/export` directly. The download
contains all audit entries (no filter applied) in CSV format with columns:

```
seq,created_at,event_time,category,event_type,actor,outcome,target,source_ip
```

The file is named `audit-log.csv`.

## REST endpoint reference

All `/admin/audit` endpoints require the `Admin` role (enforced by
`AdminAuthFilter`).

### `GET /admin/audit`

Query audit entries. All parameters are optional.

| Parameter | Type | Description |
|---|---|---|
| `actor` | string | Filter by `actor_id` (exact match) |
| `category` | string | `AUTHN`, `AUTHZ`, `CONTENT`, `ADMIN`, or `READ` (case-insensitive) |
| `eventType` | string | Exact `event_type` match |
| `target` | string | Exact `target_id` match |
| `outcome` | string | `SUCCESS`, `FAILURE`, or `DENIED` (case-insensitive) |
| `from` | ISO 8601 | Lower bound on `created_at` (inclusive) |
| `to` | ISO 8601 | Upper bound on `created_at` (exclusive) |
| `limit` | int | Max rows to return; default 100, max 1000 |
| `beforeSeq` | long | Return rows with `seq < beforeSeq` (cursor pagination) |

Response: JSON array of entry objects, newest-first.

```bash
# Recent login failures
curl -u admin:admin \
  "https://wiki.example.com/admin/audit?category=AUTHN&outcome=FAILURE&limit=50"

# All admin actions by the SCIM provisioner
curl -u admin:admin \
  "https://wiki.example.com/admin/audit?category=ADMIN&actor=scim"
```

### `GET /admin/audit/verify`

Runs the full hash-chain verification. Returns:

```json
{ "ok": true }
```

or:

```json
{ "ok": false, "firstBrokenSeq": 42 }
```

### `GET /admin/audit/export`

Streams all audit entries as a CSV file (`Content-Disposition: attachment;
filename=audit-log.csv`). No filter parameters are applied — this is a
complete export.

## Retention and the systemd timer

### Defaults

| Variable | Default | Meaning |
|---|---|---|
| `AUDIT_RETENTION_MONTHS` | `84` (7 years) | Keep this many months; older partitions are archived then dropped |
| `AUDIT_PARTITION_LOOKAHEAD` | `3` | Months of future partitions to pre-create |
| `AUDIT_ARCHIVE_DIR` | *(required for drop phase)* | Directory the NAS backup captures; see below |
| `PGUSER` | `migrate` | Privileged role that can CREATE and DROP partitions |

### What the script does

`bin/db/audit-retention.sh` does two jobs on each run:

1. **Pre-creates** partitions for the current month through
   `AUDIT_PARTITION_LOOKAHEAD` months ahead (idempotent `CREATE TABLE IF NOT
   EXISTS`), so the runtime `ensurePartition` safety net rarely fires.

2. **Archives then drops** partitions older than `AUDIT_RETENTION_MONTHS`. For
   each over-age partition the script:
   - Runs `pg_dump --table=<partition>` into `${AUDIT_ARCHIVE_DIR}`.
   - Verifies the dump with `pg_restore --list`.
   - Only if verification passes, runs `DROP TABLE <partition>`.
   - A failed archive verify skips the drop entirely (logged, not dropped).

The drop phase is skipped if `AUDIT_RETENTION_MONTHS` is unset or less than 1
(guardrail against accidental mass-drop).

Flags: `--dry-run` (print what would happen, touch nothing), `--status` (list
partitions and the configured cutoff).

```bash
# Check the current state
PGPASSWORD=<pw> bin/db/audit-retention.sh --status

# Dry-run to preview what would happen
AUDIT_RETENTION_MONTHS=84 AUDIT_ARCHIVE_DIR=/path/to/archive \
  PGPASSWORD=<pw> bin/db/audit-retention.sh --dry-run

# Run for real
AUDIT_RETENTION_MONTHS=84 AUDIT_ARCHIVE_DIR=/home/jakefear/wikantik/backups/audit \
  PGPASSWORD=<pw> bin/db/audit-retention.sh
```

The script runs as the `migrate` role (`PGUSER=migrate` by default). The app
role (`wikantik`) is INSERT/SELECT-only and cannot drop partitions — separation
of privilege is preserved.

### Installing the systemd timer

`bin/db/audit-retention-install-timer.sh` installs a systemd service and timer
that run `audit-retention.sh` monthly (default schedule: `*-*-01 04:00:00`, the
first of each month at 04:00 UTC).

**Prerequisites:**
1. Create the config file at `/etc/wikantik/audit-retention.env` with the env
   vars listed above (at minimum `PGPASSWORD` and `AUDIT_ARCHIVE_DIR`).
2. Run the installer:

```bash
./bin/db/audit-retention-install-timer.sh
```

The installer is idempotent. After install:

```bash
# Check the timer
systemctl list-timers wikantik-audit-retention.timer

# Run once immediately to test
sudo systemctl start wikantik-audit-retention.service

# Follow output
journalctl -u wikantik-audit-retention -f
```

To customize the schedule, set `ON_CALENDAR` before running the installer:

```bash
ON_CALENDAR="*-*-01 03:00:00" ./bin/db/audit-retention-install-timer.sh
```

### AUDIT_ARCHIVE_DIR and backups

`AUDIT_ARCHIVE_DIR` must point to a directory within `${BACKUP_DIR}` on docker1
(default `/home/jakefear/wikantik/backups`) so that the NAS pull picks up the
archived partition dumps automatically.

The NAS pulls docker1's backup directory daily via rsync (see
[BackupAndRecovery.md](BackupAndRecovery.md)). Setting
`AUDIT_ARCHIVE_DIR=/home/jakefear/wikantik/backups/audit` ensures purged history
is cold-stored off-box rather than lost — matching the design intent in the audit
retention design document.

Example `/etc/wikantik/audit-retention.env`:

```bash
DB_NAME=wikantik
PGHOST=localhost
PGPORT=5432
PGUSER=migrate
PGPASSWORD=<migrate-role-password>
AUDIT_RETENTION_MONTHS=84
AUDIT_PARTITION_LOOKAHEAD=3
AUDIT_ARCHIVE_DIR=/home/jakefear/wikantik/backups/audit
```

## Troubleshooting

**`GET /admin/audit/verify` returns `{ "ok": false, "firstBrokenSeq": N }`**

Row `N` or its predecessor was modified or deleted after the initial insert, or
the `detail` column was previously `JSONB` and `V037` was not yet applied (the
JSONB reformatting breaks hash computation). Confirm that `V037` has run
(`bin/db/migrate.sh --status`). If the chain breaks on a row before `V037` was
applied, the pre-migration rows are not verifiable.

**`GET /admin/audit` returns 503 "audit log unavailable"**

The `AuditService` did not initialize — either no datasource is configured, or
the engine failed to start. Check `catalina.out` for earlier errors.

**`audit-retention.sh` skips drop: "AUDIT_ARCHIVE_DIR not set"**

The `AUDIT_ARCHIVE_DIR` environment variable is not set in
`/etc/wikantik/audit-retention.env`. The pre-creation phase still runs; the
drop phase requires a valid archive directory.

**The partition for the current month is missing**

If `audit-retention.sh` has not yet run and the `V036` initial partitions
cover only June–August 2026, the runtime `ensurePartition` code in
`JdbcAuditRepository` creates the missing partition automatically on the first
write. No manual intervention is required; the retention script's
pre-creation step will keep this from recurring.

## Related

- [ApiKeys.md](ApiKeys.md) — API key issuance is recorded as `ADMIN / apikey.issue`
- [ScimProvisioning.md](ScimProvisioning.md) — SCIM operations are recorded as `ADMIN` events
- [BackupAndRecovery.md](BackupAndRecovery.md) — `AUDIT_ARCHIVE_DIR` must live under the NAS-pulled backup tree
