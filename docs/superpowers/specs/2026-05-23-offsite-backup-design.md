# Off-Box Backup & Recovery Design

**Date:** 2026-05-23
**Status:** Approved (design); NAS-side steps deferred until the DXP4800 Plus
finishes its software upgrade and reboots.

## Goal

A fully wired-up production backup process that captures both the page tree
and the PostgreSQL database nightly, archives them off the `docker1` host onto
a UGREEN DXP4800 Plus NAS, verifies integrity, and surfaces success/failure in
the jakemon monitoring stack. Includes a *runnable* restore drill so the backup
is known-restorable, not just known-present.

## Topology & Trust Model

Classic 3-2-1:

- **Copy 1** — live DB + page tree on `docker1` (the running prod stack).
- **Copy 2** — nightly tiered snapshots on `docker1`, produced by the existing
  `backup` sidecar (daily/weekly/monthly, checksummed, pruned). Unchanged
  except for the additions below.
- **Copy 3** — off-box archive on the DXP4800 Plus, pulled nightly, with
  NAS-side Btrfs snapshots providing an immutable layer.

**Direction: NAS pulls.** The DXP4800 initiates a scheduled rsync-over-SSH pull
from `docker1`. `docker1` never holds credentials that can write to the NAS.
The NAS holds a single SSH key that can *only read* the backup directory on
`docker1` (enforced by a restricted `authorized_keys` entry running `rrsync` in
read-only mode). Consequences:

- A compromised or ransomwared `docker1` cannot reach into, encrypt, or delete
  the off-box archive.
- The NAS key cannot get a shell on `docker1`, cannot write anywhere, and is
  scoped to the backup tree only.

**Network:** docker1 and the NAS are on the same LAN; the NAS reaches docker1
directly by hostname/IP. No VPN/tunnel in scope.

## What already exists (and stays)

- `docker/backup/backup.sh` — `pg_dump` (full DB) + `tar` of the page tree into
  `/backups/<tier>/<YYYY-MM-DD>/{db.sql,pages.tar.gz,checksums.sha256}`, with
  per-tier pruning. The dump is already full-database; the page archive already
  includes attachments and `.properties`.
- `docker/backup/crontab` — daily 02:00, weekly Sun 03:00, monthly 1st 04:00.
- The `backup` sidecar in `docker-compose.prod.yml` (postgres:18-alpine running
  `crond`), mounting the pages dir and `${BACKUP_DIR}` read/write.
- `bin/container.sh backup|restore` wrappers.

## Components

### Component 1 — docker1 side (this repo)

**1a. Full-database restore (defect fix).**
`docker/backup/restore.sh` currently drops and reloads only
`users, roles, groups, group_members`, but the schema now has dozens of tables
(`kg_*`, `policy_grants`, `page_canonical_ids`, `content_chunk_embeddings`,
`schema_migrations`, …). Restoring the full `db.sql` on top of a populated DB
either errors on existing objects or silently leaves stale rows in the
unhandled tables. Fix: restore into a clean schema.

- Drop and recreate `public` (`DROP SCHEMA public CASCADE; CREATE SCHEMA public;`)
  with grants restored, then load `db.sql`. The dump is taken `--no-owner
  --no-privileges`, so a clean schema load is the safe, complete path.
- Re-create required extensions before the load if the dump does not (`vector`,
  `pgcrypto`) — `CREATE EXTENSION IF NOT EXISTS` is safe.
- Verify after load by counting rows in a representative set of tables
  (`users`, `kg_nodes`, `page_canonical_ids`) and fail loudly if the core table
  set is missing.
- Keep the existing checksum verification gate (it already refuses to proceed on
  a checksum mismatch — good).

**1b. Backup status manifest + LATEST pointer.**
After a successful run, `backup.sh` writes `backup-status.json` into the tier
directory and updates `/backups/<tier>/LATEST` (a file containing the latest
dated dirname). The status JSON carries: `tier`, `date`, ISO `finished_at`,
`db_bytes`, `pages_bytes`, `page_count`, `exit_status`. This lets the NAS-side
pull confirm it grabbed a *fresh, complete* snapshot rather than a half-written
one.

**1c. Prometheus textfile metrics (jakemon event "a": local backup ran).**
`backup.sh` writes a node-exporter textfile-collector `.prom` file into a
configurable dir (`BACKUP_METRICS_DIR`, default the dir docker1's Alloy agent
already scrapes). Metrics:

- `wikantik_backup_last_success_timestamp_seconds{tier="daily|weekly|monthly"}`
- `wikantik_backup_duration_seconds{tier=...}`
- `wikantik_backup_db_bytes{tier=...}`
- `wikantik_backup_pages_bytes{tier=...}`
- `wikantik_backup_last_exit_status{tier=...}` (0 = success)

Writes are atomic (temp file + `mv`) so a partial write is never scraped. If
`BACKUP_METRICS_DIR` is unset/unwritable, log a warning and continue — metrics
are best-effort and must never fail the backup itself.

**1d. Restore drill (`bin/backup/verify-restore.sh`).**
A runnable, non-destructive proof that the latest snapshot restores. Spins up an
*ephemeral* throwaway Postgres container (pgvector image, random port, tmpfs),
loads the latest `db.sql`, extracts `pages.tar.gz` to a temp dir, asserts
core-table row counts > 0 and page count matches `backup-status.json`, then
tears the container down. Mirrors the `smoke-test` ergonomics. Exit non-zero on
any mismatch. This is the quarterly drill, automatable later.

### Component 2 — DXP4800 Plus side (version-controlled here; runs on the NAS)

**2a. `bin/backup/nas-pull.sh`.**
The off-box pull, designed to run on the NAS. Because Docker was just installed
on the NAS, the recommended execution is a tiny scheduled container (alpine +
rsync + openssh-client + curl) invoking this script, rather than UGOS Pro's
native scheduler — portable and version-controlled. Behaviour:

- `rsync -a --delete-delay` (or append-only; see retention) over SSH from
  `docker1:${REMOTE_BACKUP_DIR}/` into the NAS backup subvolume, using the
  read-only key.
- After transfer, read each tier's `LATEST`/`backup-status.json` and run
  `sha256sum -c checksums.sha256` on the newest snapshot. Fail if checksum
  verification fails or the latest snapshot is older than expected.
- On success, emit the off-box heartbeat (2c).
- Configuration via env (`DOCKER1_HOST`, `DOCKER1_BACKUP_DIR`, `NAS_DEST`,
  `SSH_KEY`, `LOKI_URL`/`PUSHGATEWAY_URL`), documented in an example env file.

**2b. NAS storage & immutability config (guidance, since storage is already set
up).**
- A dedicated shared folder / Btrfs subvolume for Wikantik backups (don't
  comingle with other NAS data).
- UGOS Pro **scheduled Btrfs snapshots** on that subvolume — an immutable layer
  the pull job cannot overwrite, defending against a bug or a poisoned source.
- A limited NAS user owning the pull container and the destination folder; not
  an admin account.
- The read-only SSH keypair: generated on the NAS, public half installed in the
  restricted `authorized_keys` on docker1 (Component 1 trust model).

**2c. Longer NAS retention than docker1.**
docker1 keeps the working set (daily 30d / weekly 12w / monthly 12mo). The NAS
keeps a longer tail using its capacity: daily 90d / weekly 6mo / monthly 1y.
Implemented either by append-only sync + a NAS-side prune mirroring `backup.sh`
tier logic, or by relying on Btrfs snapshot retention. (Pick during
implementation; default: append + prune script symmetric with `backup.sh`.)

### Component 3 — Monitoring (jakemon)

- **(a) Local backup** — textfile metrics (1c), scraped by docker1's Alloy.
- **(b) Off-box pull** — after a verified pull, the NAS posts a heartbeat.
  Recommendation: a **Loki push** (single HTTP POST of a structured log line;
  nothing to install on the NAS). Alternative: a Pushgateway gauge
  `wikantik_backup_offsite_last_success_timestamp_seconds`. The script supports
  whichever URL is configured.
- **Grafana alert rules** (authored in the **jakemon** repo, not here; this repo
  emits the signals and documents the exact rules to add):
  - No daily local backup success in 26h.
  - No off-box pull success in 26h.
  - Any checksum-verification failure (exit-status metric != 0 / error log line).

This repo's responsibility ends at emitting clean signals + documenting the
jakemon-side additions. Per project convention there is no in-repo
observability stack.

### Component 4 — Documentation

- New `docs/BackupAndRecovery.md`: topology diagram, the 3-2-1 model, the
  read-only-key setup on docker1, NAS setup steps (subvolume, snapshots, limited
  user, scheduled pull container), the full restore procedure, the
  `verify-restore.sh` drill, and a quarterly restore-test checklist.
- Cross-links from `docs/WikantikOperations.md` and `docs/DockerDeployment.md`.

## Sequencing (NAS upgrade in progress)

The NAS is mid-upgrade and will reboot; Docker and SSH were just installed.
Work order:

1. **Now (no NAS needed):** Component 1 (restore fix, status manifest, textfile
   metrics, verify-restore drill) and authoring of Component 2/3/4 scripts and
   docs. All testable on docker1 / locally.
2. **After NAS is ready (user will notify):** generate the read-only keypair,
   install the restricted `authorized_keys` on docker1, stand up the NAS pull
   container + Btrfs snapshot schedule, run a first end-to-end pull, confirm the
   jakemon heartbeat and alerts.

## Testing

- `restore.sh` full-restore: covered by `verify-restore.sh` round-trip against
  an ephemeral container (the drill *is* the test).
- `backup.sh` metrics/manifest emission: shell-level assertions that the
  `.prom`, `backup-status.json`, and `LATEST` files are written and atomic.
- `nas-pull.sh`: dry-run / `--check` mode plus a local loopback test (rsync from
  a fixture backup dir) before it ever runs on the NAS.
- No new DB schema → no migration. (Backup tooling is operational, not schema.)

## Out of scope

- Off-site (geographically remote) replication beyond the LAN-local NAS.
- Encrypting backups at rest beyond what the NAS volume provides (could be a
  follow-up: `age`/`gpg` on the artifacts before they leave docker1).
- Point-in-time recovery / WAL archiving (current model is nightly snapshots).
