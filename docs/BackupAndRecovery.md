# Backup & Recovery

This document covers the full backup and recovery architecture for Wikantik, including the 3-2-1
topology, how to run or verify a restore, and the exact monitoring signals and alert expressions
to configure in the jakemon repo.

---

## 1. Overview & 3-2-1 model

Three independent copies of Wikantik's data exist at all times:

- **Copy 1 — live production data on docker1.** The running PostgreSQL database (named volume
  `<project>_pgdata`) and the page tree (bind-mounted from `WIKANTIK_PAGES_DIR` on the host).
  This is what the application reads and writes every second.

- **Copy 2 — tiered local snapshots on docker1.** The `backup` sidecar (a
  `postgres:18-alpine` container running `crond`) fires `docker/backup/backup.sh` on a three-tier
  schedule (daily / weekly / monthly). Each run produces a self-contained timestamped directory
  under `${BACKUP_DIR}` on the host (default `/home/jakefear/wikantik/backups`), containing a
  full PostgreSQL dump, a page-tree tarball, a SHA-256 checksum manifest, and a JSON status
  manifest. Tiered retention pruning runs at the end of each backup: 30 days for daily, 12 weeks
  for weekly, 12 months for monthly.

- **Copy 3 — off-box archive on the UGREEN DXP4800 Plus NAS.** The NAS runs `bin/backup/nas-pull.sh`
  daily in a small scheduled container, pulling every docker1 snapshot via rsync over SSH. It
  independently verifies checksums after the transfer, prunes to a longer retention tail (90 days
  daily / ~6 months weekly / ~1 year monthly), and emits a monitoring heartbeat so the jakemon
  stack knows the off-box copy is current. UGOS Pro Btrfs scheduled snapshots on the backup
  subvolume provide an immutable layer that the pull job cannot overwrite.

### Trust model: the NAS always pulls

The NAS initiates every data transfer. docker1 never holds credentials that can write to or
delete from the NAS. The NAS presents a single, restricted SSH key to docker1; the
`authorized_keys` entry on docker1 confines that key to a read-only rsync of the backup
directory and nothing else (see §3 for the exact setup). The practical consequence is that a
compromised or ransomwared docker1 cannot reach the off-box archive, encrypt it, or delete it —
the attack surface runs in one direction only.

---

## 2. What runs where

### 2.1 docker1 — the `backup` sidecar

**Schedule** (from `docker/backup/crontab`):

| Tier | Time | Retention on docker1 |
|------|------|----------------------|
| Daily | 02:00 every day | 30 days |
| Weekly | 03:00 every Sunday | 12 weeks |
| Monthly | 04:00 on the 1st of each month | 12 months |

Each run by `docker/backup/backup.sh` produces:

```
${BACKUP_DIR}/
  daily/
    2026-05-23/
      db.sql                 # pg_dump --no-owner --no-privileges of the full schema
      pages.tar.gz           # wiki page tree: .md, .properties, attachments
      checksums.sha256       # SHA-256 of db.sql and pages.tar.gz
      backup-status.json     # tier, date, finished_at, db_bytes, pages_bytes, page_count, exit_status
    LATEST                   # plain-text file containing the name of the newest dated dir
  weekly/
    …
  monthly/
    …
  metrics/
    wikantik_backup_daily.prom
    wikantik_backup_weekly.prom
    wikantik_backup_monthly.prom
```

The `LATEST` file makes it straightforward for the NAS pull and for `bin/backup/verify-restore.sh`
to locate the newest snapshot without listing and sorting the directory.

Prometheus textfile metrics are written atomically (temp file then `mv`) to
`${BACKUP_METRICS_DIR}` (set inside the `backup` sidecar environment to the host path that
jakemon's Alloy textfile collector already scrapes). If that directory is unset or unwritable,
the backup logs a warning and completes normally — metrics are best-effort and never block the
backup itself.

### 2.2 DXP4800 Plus — the NAS pull container

A small Alpine container (`apk add rsync openssh-client curl`) runs `bin/backup/nas-pull.sh`
daily at approximately 05:00 — a few hours after docker1's 02:00 daily run, giving the backup
time to finish before the pull begins.

**Behaviour per run:**

1. `rsync -a --partial` over SSH from `docker1:${DOCKER1_BACKUP_DIR}/` into `${NAS_DEST}/`,
   using the read-only SSH key. Only changed or new files transfer; previously-pulled snapshots
   are not re-transferred.
2. Checksum verification of the newest snapshot in each tier by reading its `LATEST` file and
   running `sha256sum -c checksums.sha256`. A mismatch causes the run to exit non-zero with
   status `checksum_failed`.
3. Retention pruning: daily 90 days, weekly 183 days (~6 months), monthly 365 days (~1 year).
4. Off-box heartbeat: on success or checksum failure, a structured log line is posted to Loki
   (`LOKI_URL`, recommended) with stream labels `{job="wikantik_backup_offsite",
   status="success|checksum_failed"}`; or a gauge is pushed to a Pushgateway
   (`PUSHGATEWAY_URL`) if that is preferred. Configure one or neither in `nas-pull.env`; if both
   are set, Loki takes precedence and Pushgateway is skipped.

---

## 3. docker1: read-only SSH key for the NAS

This step is performed once. It creates a restricted key on docker1 that lets the NAS read the
backup directory via rsync and nothing else.

**Step 1 — Generate an ed25519 keypair on the NAS** (inside the pull container or on a NAS
shell):

```bash
ssh-keygen -t ed25519 -f /config/nas-backup-key -N "" -C nas-backup
```

Copy the content of `/config/nas-backup-key.pub`.

**Step 2 — Add a restricted entry to `~/.ssh/authorized_keys` on docker1**, logged in as the
`backup-reader` user (create that OS user if it does not exist: `sudo useradd -m -s /bin/bash
backup-reader`):

```
command="rrsync -ro /home/jakefear/wikantik/backups",no-pty,no-agent-forwarding,no-port-forwarding,no-X11-forwarding ssh-ed25519 AAAA... nas-backup
```

Replace `AAAA...` with the full public key from step 1.

`rrsync` is a Perl wrapper that ships with rsync and restricts the key to read-only rsync of
exactly the specified directory tree — the key cannot list other directories, write anywhere, or
execute arbitrary commands. It is often found at `/usr/share/doc/rsync/support/rrsync` or
`/usr/bin/rrsync`; if it is not on `PATH`, copy it to `/usr/local/bin/rrsync` and make it
executable (`chmod +x`).

The pull mirrors the whole backup tree, so the docker1-local `metrics/` directory (the
Prometheus `.prom` textfiles) rides along to the NAS. That is harmless — the NAS never reads or
prunes it; only docker1's Alloy agent scrapes those files. Don't mistake it for a misconfiguration.

**Step 3 — Verify from the NAS** (dry-run before enabling the schedule):

```bash
rsync -a --dry-run -e "ssh -i /config/nas-backup-key -o BatchMode=yes" \
    backup-reader@docker1:/home/jakefear/wikantik/backups/ /volume1/wikantik-backups/
```

Expected output: a list of files to transfer with no errors and no password prompt.

---

## 4. NAS setup

### Dedicated subvolume

Create a dedicated shared folder / Btrfs subvolume on the DXP4800 Plus for Wikantik backups —
for example `wikantik-backups` mounted at `/volume1/wikantik-backups`. Do not store the
Wikantik archive inside a general-purpose share; keeping it isolated makes retention, snapshot
scheduling, and access control straightforward to audit.

A limited OS user on the NAS (not the admin account) should own the pull container, the
destination directory, and the SSH key file. Grant that user read-write access to
`/volume1/wikantik-backups` and nothing else.

### The pull container

Because Docker and SSH were recently installed on the DXP4800 Plus during its software upgrade,
the preferred execution method is a small scheduled container rather than the UGOS Pro native
task scheduler. This keeps the pull script version-controlled and portable.

Minimal image definition:

```dockerfile
FROM alpine:3.20
RUN apk add --no-cache rsync openssh-client curl bash
COPY bin/backup/nas-pull.sh /usr/local/bin/nas-pull.sh
RUN chmod +x /usr/local/bin/nas-pull.sh
CMD ["nas-pull.sh"]
```

Schedule it to run daily at 05:00 via the UGOS Pro container scheduler or a host crontab entry.
Mount the SSH private key and the env file as read-only volumes:

```
/config/nas-backup-key  → /config/nas-backup-key  (read-only)
/config/nas-pull.env    → /path/to/nas-pull.env    (read-only)
/volume1/wikantik-backups → /volume1/wikantik-backups (read-write)
```

### Configuration file

Copy `bin/backup/nas-pull.env.example` to `nas-pull.env` on the NAS (at `/config/nas-pull.env`
or another path you control) and fill in every value:

```
DOCKER1_HOST=docker1
DOCKER1_USER=backup-reader
DOCKER1_BACKUP_DIR=/home/jakefear/wikantik/backups
SSH_KEY=/config/nas-backup-key

NAS_DEST=/volume1/wikantik-backups

NAS_RETAIN_DAILY_DAYS=90
NAS_RETAIN_WEEKLY_DAYS=183
NAS_RETAIN_MONTHLY_DAYS=365

LOKI_URL=http://inference:3100/loki/api/v1/push
PUSHGATEWAY_URL=
```

Run with `--dry-run` to verify the rsync command before committing to a live transfer.

### Btrfs scheduled snapshots

Enable **scheduled Btrfs snapshots** on the `wikantik-backups` subvolume in UGOS Pro (Storage
Manager → Snapshot Replication, or equivalent). Configure daily snapshots retained for at least
30 days. These NAS-side snapshots are immutable from the perspective of the pull container: even
if `nas-pull.sh` has a bug that overwrites or deletes a file, the Btrfs snapshot layer preserves
the previous state and allows manual recovery without touching docker1.

---

## 5. Restore procedure

Full restores are performed by `docker/backup/restore.sh` running inside the `backup` sidecar.
The script drops and rebuilds the `public` schema, loads the dump in full (all tables — users,
roles, groups, policy grants, all `kg_*` tables, page metadata, embeddings, schema migrations),
verifies that core tables populated correctly, then restores the page tree. The Lucene search
index and in-memory caches rebuild automatically when the application starts.

### DR sequence

1. **Stop the application container** to prevent writes during the restore:
   ```bash
   docker compose -f docker-compose.yml -f docker-compose.prod.yml stop wikantik
   ```

2. **Choose a snapshot.** List available backups:
   ```bash
   ls /home/jakefear/wikantik/backups/daily/
   # The most recent date is also recorded in:
   cat /home/jakefear/wikantik/backups/daily/LATEST
   ```
   If restoring from the NAS, use `rsync` to copy the chosen snapshot back to docker1 first.

3. **Run the restore** in the backup sidecar:
   ```bash
   docker compose -f docker-compose.yml -f docker-compose.prod.yml \
       exec backup /usr/local/bin/restore.sh /backups/daily/2026-05-23
   ```
   The script verifies checksums before touching anything. If checksums fail it exits
   immediately without making any changes.

4. **Start the application:**
   ```bash
   docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d wikantik
   ```
   The Lucene search index rebuilds automatically on startup (typically 30–60 seconds).

5. **Verify:** browse to the wiki and confirm content; check `GET /api/health` returns
   `status: UP`.

### Schema note

`restore.sh` issues `DROP SCHEMA public CASCADE; CREATE SCHEMA public;` with grants, then
ensures the `vector` and `pgcrypto` extensions exist before loading the dump. This is the same
manual sequence documented in `docs/DockerDeployment.md §6` for first-deploy database
initialisation. The dump was taken with `--no-owner --no-privileges`, so a clean schema load is
the safe, complete path — there is no risk of stale rows in unhandled tables.

---

## 6. Verifying restorability

Knowing a backup exists is not enough; the backup must be proven loadable before a crisis.
`bin/backup/verify-restore.sh` performs a non-destructive round-trip restore against a throwaway
ephemeral container, touching no live database or page tree.

### Usage

```bash
# Verify the latest snapshot in a tier (reads LATEST automatically):
bin/backup/verify-restore.sh /home/jakefear/wikantik/backups/daily

# Verify an explicit snapshot:
bin/backup/verify-restore.sh /home/jakefear/wikantik/backups/daily/2026-05-23

# Override the Postgres image (must match production's pg major version):
VERIFY_PG_IMAGE=pgvector/pgvector:pg18 bin/backup/verify-restore.sh /path/to/snap
```

### What it checks

1. **Checksum gate** — `sha256sum -c checksums.sha256` must pass. Any corruption is caught
   here.
2. **Ephemeral container** — spins up `pgvector/pgvector:pg18` on a random port, waits for
   readiness, installs the `vector` and `pgcrypto` extensions, then loads `db.sql`.
3. **Core-table counts** — queries `users`, `kg_nodes`, and `page_canonical_ids`. If any table
   is absent or the query returns `MISSING`, the drill fails loudly.
4. **Page count** — extracts `pages.tar.gz` to a temp directory, counts `.md` files, and
   compares against `page_count` from `backup-status.json`. A mismatch exits non-zero.
5. **Cleanup** — the ephemeral container and temp directory are removed unconditionally via a
   `trap EXIT` handler, even on failure.

### Recommended schedule

Run `verify-restore.sh` quarterly, and after any PostgreSQL major-version bump. A version bump
changes the on-disk format of the dump, so confirming that `pg_dump` from the new version
produces a dump that loads cleanly into the new image is worth doing explicitly rather than
discovering the incompatibility during an actual disaster.

---

## 7. Monitoring (jakemon)

This repo emits signals; the alert rules live in the **jakemon** repo. Do not add Grafana alert
rules or Prometheus recording rules to this repository — per project convention, there is no
in-repo observability stack.

### Signals emitted by this repo

**Local backup metrics** (Prometheus textfile, scraped by docker1's Alloy textfile collector
from `${BACKUP_METRICS_DIR}/*.prom`):

| Metric | Type | Label | Meaning |
|--------|------|-------|---------|
| `wikantik_backup_last_success_timestamp_seconds` | gauge | `tier` | Unix timestamp of the last successful backup run |
| `wikantik_backup_duration_seconds` | gauge | `tier` | Wall-clock seconds the backup took |
| `wikantik_backup_db_bytes` | gauge | `tier` | Bytes in `db.sql` for the last run |
| `wikantik_backup_pages_bytes` | gauge | `tier` | Bytes in `pages.tar.gz` for the last run |
| `wikantik_backup_last_exit_status` | gauge | `tier` | Exit status of the last run (0 = success) |

`tier` is one of `daily`, `weekly`, or `monthly`. Each tier writes its own `.prom` file
atomically so a partial write is never scraped.

**Off-box heartbeat** (from `nas-pull.sh` after a verified pull):

- Via Loki (recommended): a log stream push to `{job="wikantik_backup_offsite", status="success"}`.
  A checksum failure posts `status="checksum_failed"` instead, and the script exits non-zero.
- Via Pushgateway (alternative): a gauge `wikantik_backup_offsite_last_success_timestamp_seconds`
  pushed to `job/wikantik_backup_offsite`.

### Alert expressions to add in the jakemon repo

Add these as Grafana alert rules (or Prometheus alerting rules) in the jakemon repository. The
window is set to 26 hours so a brief delay or clock skew does not cause a spurious alert on an
otherwise healthy daily cadence.

**Daily backup missed** (no successful local backup in 26 hours):
```promql
time() - max(wikantik_backup_last_success_timestamp_seconds{tier="daily"}) > 26*3600
```

**Backup reported failure** (any tier exited non-zero):
```promql
max(wikantik_backup_last_exit_status) != 0
```

**Off-box pull missed** — with Loki, alert when no log line with
`{job="wikantik_backup_offsite", status="success"}` appears in the last 26 hours using a Loki
alerting rule. With Pushgateway:
```promql
time() - wikantik_backup_offsite_last_success_timestamp_seconds > 26*3600
```

These three rules together cover the full signal chain: local backup ran, local backup
succeeded, off-box archive is current.
