# Audit Log

This page is for administrators who review the audit log, verify its integrity and
manage its retention. Wikantik records authentication, authorization, content, and administrative
events in a tamper-evident audit log. The log is append-only at the database
level and uses a SHA-256 hash chain, so changing or removing a row inside the log
is detectable (see [what verification cannot see](#hash-chain-integrity-model)).

The implementation lives in:
- `AdminAuditResource` — REST endpoints at `/admin/audit`
- `AdminAuditPage.jsx` — admin UI
- `AuditService`, `JdbcAuditRepository`, `AuditChainHasher` — Java tier
- `V036__audit_log.sql`, `V037__audit_log_detail_text.sql`, `V059__audit_log_partitions_through_2028.sql` — schema migrations
- `bin/db/audit-retention.sh`, `bin/db/audit-retention-install-timer.sh` — retention tooling

## What gets logged

`AuditEventListener` translates Wikantik's `WikiEvent` stream (login/logout,
access-denied, page save/delete/rename, group and profile changes) into audit
entries; other event types — API keys, user lifecycle, policy grants,
connectors, SCIM, and opt-in page reads — are recorded directly by their
owning REST/SCIM resource via `AuditService.record()`. Either way, every
entry lands in one of five categories (`AuditCategory` enum):

| Category | Event types recorded |
|---|---|
| `AUTHN` | `login.ok`, `login.failed`, `logout`, `session.expired` |
| `AUTHZ` | `access.denied` |
| `CONTENT` | `page.save`, `page.delete`, `page.rename` |
| `ADMIN` | `group.member.add`, `group.member.remove`, `profile.save`, user lifecycle (`user.deactivate`, `user.reactivate`), API key events (`apikey.issue` from both the admin and self-service surfaces; `apikey.rotate` and `apikey.revoke` from the self-service surface only), policy grant changes (`policy.grant.update`, which is also what a grant *creation* records, and `policy.grant.delete`), connector mutations (`connector.create`, `connector.update`, `connector.delete`, `connector.import`, `connector.credential.set`, `connector.credential.delete`), SCIM operations (`scim.user.create`, `scim.user.update`, `scim.group.create`, `scim.group.update`, `scim.group.delete`) |
| `READ` | `page.read` — opt-in only (default: not recorded); a page's views are logged when its frontmatter sets `audit_reads: true` or its cluster is listed in `wikantik.audit.readClusters` (blank by default) |

Entries are not written inline. `AuditService.record()` puts each one on an in-memory
queue (capacity 10,000) and a single background thread, `audit-writer`, persists them in
batches of up to 256. If the queue is full the entry is dropped and the application log
shows `Audit queue full; dropped audit entry`; if a batch fails to persist (for example
the month's partition is missing and the application role cannot create it), that batch
is lost and the log shows `Audit writer failed to persist a batch`. Watch for both
messages: a lost entry leaves no gap in the chain, so verification cannot reveal it.

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

What verification cannot see: it recomputes each surviving row from its predecessor,
starting from the oldest row it finds, and nothing anchors the newest row. Rows removed
from the very beginning or the very end of the log, and entries that were never written
(see the queue behaviour above), leave a chain that still verifies.

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
(`audit_log_2026_06`, `audit_log_2026_07`, `audit_log_2026_08`) and `V059`
pre-creates every monthly partition from 2026-09 through 2028-12, because the
application role has no `CREATE` on schema `public`. The writer also calls
`ensurePartition` before every batch insert as a safety net, but that only
succeeds on deployments where the application role can create tables.

The monotonic chain order comes from the sequence `audit_log_seq`.

## Admin UI

Navigate to **Admin → Audit Log** (`/admin/audit` in the React SPA). The page
requires the `Admin` role.

### Searching

Set one or more filters and click **Search**:

| Filter | Description |
|---|---|
| Actor | Login name of the acting user or system (e.g. `admin`, `scim`) |
| Category | `authn`, `authz`, `content`, `admin`, `read`, or blank for all |
| Event type | Exact event type string (e.g. `login.failed`, `apikey.issue`) |
| Target | Target entity id (e.g. a page name or user login) |
| Outcome | `success`, `failure`, or `denied` |
| From / To | Date-time range filter on `created_at` |

Results are returned newest-first. The screen requests the default 100 entries and has
no paging control, so it shows only the newest 100 matches; narrow the filters, or call
the REST API (`limit` up to 1000 and the `beforeSeq` cursor) to go further back.

### Verify integrity

Click **Verify integrity** to call `GET /admin/audit/verify`. The endpoint
walks the full chain in `seq` order and reports either:

- `{ "ok": true }` — all row hashes verified.
- `{ "ok": false, "firstBrokenSeq": 42 }` — the chain is broken at seq 42.

The UI displays a green banner on success or a red banner with the broken
sequence number on failure.

### Export CSV

Click **Export CSV** or call `GET /admin/audit/export` directly. The download
contains the newest 1000 audit entries (no filter applied; the export is capped at 1000
rows, not the whole log) in CSV format with columns:

```
seq,created_at,event_time,category,event_type,actor,outcome,target,source_ip
```

The `actor` column is the actor's principal name.

The file is named `audit-log.csv`.

## REST endpoint reference

All `/admin/audit` endpoints require the `Admin` role (enforced by
`AdminAuthFilter`).

### `GET /admin/audit`

Query audit entries. All parameters are optional.

| Parameter | Type | Description |
|---|---|---|
| `actor` | string | Exact match on the `actor_id` column. The current writers record the acting login in `actor_principal` and leave `actor_id` empty, so this filter returns nothing for entries written by this code. To follow one account, filter by category, event type or target and read `actorPrincipal` from the result, or use the export's `actor` column |
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
curl -u "$ADMIN_LOGIN:$ADMIN_PASSWORD" \
  "https://wiki.example.com/admin/audit?category=AUTHN&outcome=FAILURE&limit=50"

# All SCIM user-create events (the actor is recorded as actorPrincipal "scim")
curl -u "$ADMIN_LOGIN:$ADMIN_PASSWORD" \
  "https://wiki.example.com/admin/audit?category=ADMIN&eventType=scim.user.create"
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

Streams the newest 1000 audit entries as a CSV file (`Content-Disposition: attachment;
filename=audit-log.csv`). No filter parameters are applied, and the export stops at
1000 rows, so it is not a complete archive of a larger log; the retention job's
partition dumps are the full record.

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
   EXISTS`), so the runtime `ensurePartition` safety net is never needed.

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
AUDIT_RETENTION_MONTHS=84 AUDIT_ARCHIVE_DIR=/srv/wikantik/backups/audit \
  PGPASSWORD=<pw> bin/db/audit-retention.sh
```

The script runs as the `migrate` role (`PGUSER=migrate` by default). The app
role (`wikantik`) is INSERT/SELECT-only and cannot drop partitions — separation
of privilege is preserved.

### Installing the systemd timer

`bin/db/audit-retention-install-timer.sh` installs a systemd service and timer
that run `audit-retention.sh` monthly (default schedule: `*-*-01 04:00:00`, the
first of each month at 04:00 in the host's local time zone, as systemd interprets `OnCalendar`).

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

`AUDIT_ARCHIVE_DIR` must point to a directory within `${BACKUP_DIR}` on the Docker
host (the `docker-compose.prod.yml` default is `/srv/wikantik/backups`; set `BACKUP_DIR`
in `.env` to change it) so that the NAS pull picks up the archived partition dumps
automatically.

The NAS pulls the host's backup directory daily via rsync (see
[BackupAndRecovery.md](BackupAndRecovery.md)). With the default backup directory,
setting `AUDIT_ARCHIVE_DIR=/srv/wikantik/backups/audit` ensures purged history
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
AUDIT_ARCHIVE_DIR=/srv/wikantik/backups/audit
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

`V059` pre-created monthly partitions only through December 2028. For any later month
that `audit-retention.sh` has not yet pre-created, the runtime `ensurePartition` code
in `JdbcAuditRepository` tries to create the missing partition on the first write. That works only when the application role may
`CREATE` in schema `public`; under the documented least-privilege posture it
fails with "permission denied" and audit writes stop. Run
`bin/db/audit-retention.sh` (or `bin/db/migrate.sh` with a newer migration) as the
privileged role well before 2029 so the partitions exist ahead of time.

## Related

- [ApiKeys.md](ApiKeys.md) — API key issuance is recorded as `ADMIN / apikey.issue`
- [ScimProvisioning.md](ScimProvisioning.md) — SCIM operations are recorded as `ADMIN` events
- [BackupAndRecovery.md](BackupAndRecovery.md) — `AUDIT_ARCHIVE_DIR` must live under the NAS-pulled backup tree
