# Backup & Recovery

This document is for operators of the container deployment. It covers the backup and
recovery architecture for Wikantik: the 3-2-1 topology, how to run or verify a restore, and the
monitoring signals and alert expressions to configure in the jakemon repo. It is written for any
deployment of the container stack; values specific to the wikantik.com deployment
(docker1 + a UGREEN NAS) appear only in blocks labelled "Example: the wikantik.com
deployment". The compose files and scripts under `docker/backup/` and `bin/backup/`
are the source of truth for defaults. For the container stack itself see
[DockerDeployment.md](DockerDeployment.md).

---

## 1. Overview & 3-2-1 model

Three independent copies of Wikantik's data exist at all times:

- **Copy 1 — live production data on the app host.** The running PostgreSQL database (named volume
  `pgdata`, which compose prefixes with the project name, for example `repo_pgdata`) and the page tree (bind-mounted from `WIKANTIK_PAGES_DIR`). What the
  application reads and writes every second.

- **Copy 2 — tiered local snapshots on the app host.** The `backup` sidecar (a `postgres:18-alpine`
  container running `crond`) fires `docker/backup/backup.sh` on a three-tier schedule. Each run
  produces a self-contained timestamped directory under `${BACKUP_DIR}` on the host (`BACKUP_DIR`; the
  `docker-compose.prod.yml` default is `/srv/wikantik/backups`; set `BACKUP_DIR` in `.env` to change it) with a full PostgreSQL dump, a page-tree tarball, a SHA-256
  checksum manifest, and a JSON status manifest. Retention pruning runs at the end of each run:
  30 days daily, 12 weeks weekly, 12 months monthly.

- **Copy 3 — off-box archive on a separate pull host.** The pull host runs
  `bin/backup/nas-pull.sh` daily (systemd timer), pulling every snapshot via rsync over
  SSH. It independently verifies checksums after transfer, prunes to a longer retention tail
  (`NAS_RETAIN_DAILY_DAYS`/`NAS_RETAIN_WEEKLY_DAYS`/`NAS_RETAIN_MONTHLY_DAYS`, defaults 90 / 183 /
  365 days), and writes a Prometheus textfile heartbeat (`TEXTFILE_DIR`) for a metrics agent to
  scrape. `NAS_DEST` is the archive directory on the pull host; if it sits inside a cloud-synced
  folder you get a fourth, off-site copy for free.

> **Example: the wikantik.com deployment (docker1 + UGREEN DXP4800 Plus NAS).** The app host is
> `docker1`, the pull host is the NAS, and `NAS_DEST` is
> `/volume1/@home/jakefear/GoogleDrive/wikantik-backups`, inside the NAS's Google Drive sync
> folder. That adds the fourth, off-site copy; mind the Drive quota (§4).

### Trust model: the pull host always pulls

The pull host initiates every transfer. The app host never holds credentials that can write to or
delete from the pull host. The pull host presents a single restricted SSH key to the app host; the
`authorized_keys` entry confines that key to a **read-only** rsync of the backup directory and
nothing else (§3). A compromised or ransomwared app host therefore cannot reach the off-box
archive, encrypt it, or delete it — the trust runs one way only. This read-only pull is the
**primary** ransomware defense (see §4 on pull-host immutability).

---

## 2. What runs where

### 2.1 App host — the `backup` sidecar

**Schedule** (`docker/backup/crontab`):

| Tier | Time | Retention on the app host |
|------|------|----------------------|
| Daily | 02:00 every day | 30 days |
| Weekly | 03:00 every Sunday | 12 weeks |
| Monthly | 04:00 on the 1st | 12 months |

Each `docker/backup/backup.sh` run produces:

```
${BACKUP_DIR}/                 # host: BACKUP_DIR (default /srv/wikantik/backups)
  daily/
    2026-05-23/
      db.sql.gz              # gzip-compressed pg_dump --no-owner --no-privileges, full schema
      pages.tar.gz           # wiki page tree: .md, .properties, attachments
      checksums.sha256       # SHA-256 of db.sql.gz and pages.tar.gz
      backup-status.json     # tier, date, finished_at, db_bytes, pages_bytes, page_count, exit_status
    LATEST                   # plain-text file naming the newest dated dir
  weekly/   …
  monthly/  …
```

`LATEST` lets the pull host and `bin/backup/verify-restore.sh` find the newest snapshot without
listing and sorting.

**Metrics.** The sidecar writes Prometheus textfile metrics atomically (temp file then `mv`) to
`BACKUP_METRICS_DIR` (inside the container, default `/textfile`). `docker-compose.prod.yml`
bind-mounts the host's jakemon textfile-collector dir there:

```yaml
BACKUP_METRICS_DIR: ${BACKUP_METRICS_DIR:-/textfile}
volumes:
  - ${BACKUP_TEXTFILE_DIR:-/var/lib/jakemon/textfile}:/textfile
```

So the `.prom` files land at `/var/lib/jakemon/textfile/wikantik_backup_<tier>.prom` on the
host, **outside** `${BACKUP_DIR}` — they do not ride along in the off-box pull. The host's metrics agent
(Alloy, in the reference deployment) scrapes that dir (§7). If the dir is unset or unwritable the backup
logs a warning and completes normally — metrics are best-effort and never block the backup.

### 2.2 Pull host — `nas-pull.sh`

`bin/backup/nas-pull.sh` runs daily via a systemd timer (§4; default `ON_CALENDAR` is
`*-*-* 05:00:00`, a few hours after the 02:00 daily snapshot). Per run:

1. `rsync -rlptD --partial` over SSH from `${DOCKER1_USER}@${DOCKER1_HOST}:${REMOTE_SRC_PATH}` into
   `${NAS_DEST}/`, using the read-only key (`SSH_KEY`). (The `DOCKER1_*` variable names are historical;
   they name whichever host runs the backup sidecar.) The source path is **relative to the
   rrsync-locked root**: an empty `REMOTE_SRC_PATH` pulls the whole locked dir (§3). `-rlptD` (not
   `-a`) preserves recursion, symlinks, perms, times and devices but **not** owner/group, because some
   NAS rsync builds cannot setuid root on receive and `-o`/`-g` would fail the whole transfer.
2. Checksum verification of the newest snapshot in each tier (reads `LATEST`, runs
   `sha256sum -c`). A mismatch sets the run to exit non-zero with status `checksum_failed`.
3. Retention pruning: `NAS_RETAIN_DAILY_DAYS` (90), `NAS_RETAIN_WEEKLY_DAYS` (183),
   `NAS_RETAIN_MONTHLY_DAYS` (365).
4. Heartbeat (§7): writes `wikantik_backup_offsite.prom` to `TEXTFILE_DIR` for a metrics agent.
   `last_success` is preserved across a failed run so the freshness alert keeps climbing.
   `LOKI_URL`/`PUSHGATEWAY_URL` are optional remote-push alternatives.

> **Example: the wikantik.com deployment.** `DOCKER1_HOST=docker1.lan`, `TEXTFILE_DIR=/var/lib/jakemon/textfile`,
> scraped by the NAS's jakemon Alloy agent.

---

## 3. App host: read-only SSH key for the pull host

Performed once. Creates a restricted account on the app host whose key can only read-rsync the backup
directory. Placeholders: `<backup-user>` is the restricted account (`DOCKER1_USER` in `nas-pull.env`),
`<backup-dir>` is your `BACKUP_DIR` (also `DOCKER1_BACKUP_DIR`, documentary), `<backup-host>` is the
app host (`DOCKER1_HOST`), `<app-user-home>` is the home directory that contains `<backup-dir>` if it
lives under one.

**Step 1 — Generate an ed25519 keypair on the pull host:**

```bash
ssh-keygen -t ed25519 -f ~/.ssh/wikantik_backup_pull -N "" -C wikantik-backup-pull@pullhost
```

The private key never leaves the pull host (point `SSH_KEY` at it). Copy `~/.ssh/wikantik_backup_pull.pub`.

**Step 2 — Create the restricted account and key entry on the app host** (as a sudoer):

```bash
# Restricted account (valid shell so sshd can run the forced command)
sudo useradd --system --create-home --home-dir /home/<backup-user> --shell /bin/sh <backup-user>

# Only needed if <backup-dir> sits under a home directory the account cannot traverse
# (for example mode 0750). Grant traverse-only (no listing):
sudo setfacl -m u:<backup-user>:--x <app-user-home>

# Install the pull host's public key, forced to read-only rsync of the backup dir only
sudo install -d -m 700 -o <backup-user> -g <backup-user> /home/<backup-user>/.ssh
echo 'command="rrsync -ro <backup-dir>",no-pty,no-agent-forwarding,no-port-forwarding,no-X11-forwarding ssh-ed25519 AAAA... wikantik-backup-pull@pullhost' \
  | sudo tee /home/<backup-user>/.ssh/authorized_keys >/dev/null
sudo chown <backup-user>:<backup-user> /home/<backup-user>/.ssh/authorized_keys
sudo chmod 600 /home/<backup-user>/.ssh/authorized_keys
```

Replace `AAAA...` with the public key from step 1.

`rrsync` ships with rsync (often `/usr/bin/rrsync`) and restricts the key to read-only rsync of
exactly the named directory — no shell, no write, no other paths. Because it treats the requested path
as relative to that locked root, the pull uses an **empty** source path; an absolute path would be
re-appended to the root and fail.

**Step 3 — Verify the trust boundary from the pull host:**

```bash
KEY=~/.ssh/wikantik_backup_pull
# Read-only list works (empty path = the locked root):
rsync --list-only -e "ssh -i $KEY" <backup-user>@<backup-host>:
# Shell is refused (forced command):
ssh -i $KEY <backup-user>@<backup-host> id        # -> "rrsync error: SSH_ORIGINAL_COMMAND does not run rsync"
# Write is refused (read-only):
rsync -e "ssh -i $KEY" /etc/hostname <backup-user>@<backup-host>:evil   # -> "sending to read-only server is not allowed"
```

All three behaviours above are the expected, correct result.

> **Example: the wikantik.com deployment.** `<backup-user>` = `backup-reader`, `<backup-host>` =
> `docker1.lan`, `<backup-dir>` = `/home/jakefear/wikantik/backups` (so `<app-user-home>` =
> `/home/jakefear`, which is mode 0750 and needs the `setfacl` line).

---

## 4. Pull host setup

### Layout

Choose a directory for the scripts and config (`<pull-dir>`, for example `~/wikantik-backup`), a path
for the private key (`SSH_KEY`), the archive directory (`NAS_DEST`) and the textfile directory
(`TEXTFILE_DIR`):

| Path | Purpose |
|------|---------|
| `<pull-dir>/` | `nas-pull.sh`, `nas-pull.env`, `nas-install-timer.sh` |
| `SSH_KEY` (e.g. `~/.ssh/wikantik_backup_pull`) | read-only pull key (private) |
| `NAS_DEST` | the off-box archive |
| `TEXTFILE_DIR` (e.g. `/var/lib/jakemon/textfile`) | heartbeat `.prom`, scraped by a metrics agent (mode 1777) |

```bash
mkdir -p "$NAS_DEST"
sudo mkdir -p "$TEXTFILE_DIR" && sudo chmod 1777 "$TEXTFILE_DIR"
```

> **Example: the wikantik.com deployment.** `<pull-dir>` = `/home/jakefear/wikantik-backup/`,
> `NAS_DEST` = `/volume1/@home/jakefear/GoogleDrive/wikantik-backups` (inside the NAS's Google Drive
> sync folder, in the owner's home subtree so it needs no sudo), `TEXTFILE_DIR` = `/var/lib/jakemon/textfile`.
>
> **Drive footprint.** `db.sql` is gzip-compressed to `db.sql.gz`. Compression is modest here
> (~2.2x: 290 MB to 131 MB) because pgvector embeddings dominate the dump and are high-entropy. A daily
> snapshot is about 130 MB plus ~5 MB of pages; across 90 daily plus weekly and monthly tiers it trends
> toward ~15 GB. To trim: lower the retention variables or sync only the `weekly`/`monthly` tiers.

### Deploying the scripts

Copy `bin/backup/nas-pull.sh` and `bin/backup/nas-install-timer.sh` to `<pull-dir>` on the pull host
with any method it accepts:

```bash
# from the wikantik repo on the dev box
ssh <pull-host> 'cat > <pull-dir>/nas-pull.sh && chmod 755 <pull-dir>/nas-pull.sh' < bin/backup/nas-pull.sh
ssh <pull-host> 'cat > <pull-dir>/nas-install-timer.sh && chmod 755 <pull-dir>/nas-install-timer.sh' < bin/backup/nas-install-timer.sh
```

If you use `rsync` instead, pass `--chmod=F755`; a `F644` strips the execute bit.

> **Example: the wikantik.com deployment.** The NAS's rsync daemon is hardened and refuses incoming
> rsync (it cannot setuid root on receive), which is why the scripts are copied with plain `ssh`. This
> only affects pushing to the NAS; the pull from docker1 is unaffected. `<pull-host>` = `nas.lan`.

### Configuration

Copy `bin/backup/nas-pull.env.example` to `<pull-dir>/nas-pull.env` (mode 600) and set:

```
DOCKER1_HOST=<backup-host>
DOCKER1_USER=<backup-user>
DOCKER1_BACKUP_DIR=<backup-dir>        # documentary; matches the rrsync root
REMOTE_SRC_PATH=                       # empty: rrsync-relative (pull the whole root)
SSH_KEY=<path to the private key>
NAS_DEST=<archive dir on the pull host>
NAS_RETAIN_DAILY_DAYS=90
NAS_RETAIN_WEEKLY_DAYS=183
NAS_RETAIN_MONTHLY_DAYS=365
TEXTFILE_DIR=/var/lib/jakemon/textfile
LOKI_URL=
PUSHGATEWAY_URL=
```

Verify before scheduling: `./nas-pull.sh --env nas-pull.env --dry-run`, then a real run.

> **Example: the wikantik.com deployment.** `DOCKER1_HOST=docker1.lan`, `DOCKER1_USER=backup-reader`,
> `DOCKER1_BACKUP_DIR=/home/jakefear/wikantik/backups`,
> `SSH_KEY=/home/jakefear/.ssh/wikantik_backup_pull`,
> `NAS_DEST=/volume1/@home/jakefear/GoogleDrive/wikantik-backups`.

### Scheduling (systemd timer)

`bin/backup/nas-install-timer.sh` installs and enables a systemd timer (idempotent; prompts for sudo).
Set `ON_CALENDAR` to change the default `*-*-* 05:00:00`:

```bash
cd <pull-dir> && ./nas-install-timer.sh
# inspect:  systemctl list-timers wikantik-backup-pull.timer
# run now:  sudo systemctl start wikantik-backup-pull.service
# logs:     journalctl -u wikantik-backup-pull
```

The timer uses `Persistent=true` (a missed run after downtime catches up).

> **Example: the wikantik.com deployment.** The NAS locks down user crontabs, so a systemd timer is the
> only scheduling option there.

### Pull-host immutability — caveat

The defenses that exist regardless of filesystem are:

- The **read-only pull** (§3): the app host cannot write to or delete from the pull host — the main
  ransomware/blast-radius control.
- The **dated snapshot tree**: each day/week/month is a separate directory, so history exists on the
  pull host independent of the app host; only `nas-pull.sh`'s own retention prune removes old dirs.

What this does **not** give you is protection against a bug in `nas-pull.sh` or a pull-host compromise
overwriting or deleting the archive. For a true immutable layer, use a filesystem with snapshots (for
example Btrfs with scheduled snapshots, and point `NAS_DEST` at it) or add a second, append-only cold copy.

> **Example: the wikantik.com deployment.** The UGREEN DXP4800 Plus volume is ext4, not Btrfs, so UGOS
> scheduled snapshots are not available; adding a Btrfs volume or an append-only cold copy is a deferred
> open item (see the end of this doc).

---

## 5. Restore procedure

Full restores run via `docker/backup/restore.sh` inside the `backup` sidecar. The script drops
and rebuilds the `public` schema, loads the dump in full (all tables — users, roles, groups,
policy grants, all `kg_*` tables, page metadata, embeddings, schema migrations), verifies core
tables populated, then restores the page tree. Lucene and in-memory caches rebuild on startup.

> **Restoring to a *fresh* host?** Skip the manual sequence below and use
> `bin/dr-restore.sh` (§5.1) — it automates the whole stand-up. The manual sequence here is for
> restoring in place on an existing instance.

### 5.1 Disaster recovery to a fresh host — `bin/dr-restore.sh`

When the production host is lost (or you want to rehearse it), `bin/dr-restore.sh` stands up a
complete instance on another host from a backup snapshot, in one command. Run it from a
workstation that can ssh **both** the target and the snapshot host (the target usually cannot
reach the backup source itself):

```bash
# Pull the released image from GHCR (works even if the prod host is gone),
# restore the latest off-box snapshot onto a fresh host, smoke-test it:
bin/dr-restore.sh <target-host> --image-tag <tag> --snapshot-host <pull-host> --snapshot-path <tier-dir>

# Fast LAN path when a source host with the image is alive:
bin/dr-restore.sh <target-host> --from-host <source-host> --snapshot-host <pull-host> --snapshot-path <tier-dir>

# Rehearse without touching anything:
bin/dr-restore.sh <target-host> --dry-run
```

What it does (the drill, automated): stage compose + `docker/{db,backup}` + a generated `.env`
(fresh DB password) on the target → provision the image (GHCR pull shipped via `docker save|load`,
or `--from-host` save|load) → transfer the snapshot from `--snapshot-host` at `--snapshot-path`
(a dated snapshot dir, or a tier dir containing `LATEST`; the script's built-in defaults are the
wikantik.com NAS, so always pass both for your own setup) and **verify checksums on the target** →
`up -d db` → `restore.sh` in a one-off container → `up -d wikantik` → `bin/smoke-wiki.sh`.

Safety: it refuses to target the production host from `remote.env` (override with `--force`), and
`--dry-run` prints the full plan without changing anything. Teardown is printed on completion
(`docker compose … down -v`).

**Image vs dump version.** Migrations are additive, so a release image generally boots cleanly
against a *newer* dump (validated 2026-05-23: the `2.0.1` GHCR image ran a `2.0.2-SNAPSHOT` dump
and passed the full smoke test). The reverse is not guaranteed — don't restore a dump taken on an
*older* schema into a much newer image without checking. When in doubt, pick `--image-tag` to
match the version that produced the dump.

`bin/smoke-wiki.sh <base-url>` is the standalone functional check it ends with — health UP, a page
renders, the changes feed is populated, and search returns a hit. Useful after any deploy, not
just DR.

### 5.2 Manual in-place restore

Restore in place on an existing instance (e.g. roll back bad data):

1. **Stop the app** (prevent writes during restore):
   ```bash
   docker compose -f docker-compose.yml -f docker-compose.prod.yml stop wikantik
   ```
2. **Choose a snapshot:**
   ```bash
   ls <backup-dir>/daily/
   cat <backup-dir>/daily/LATEST
   ```
   If restoring from the NAS, copy the chosen snapshot back into `<backup-dir>` on the app host first
   (push it from the pull host over ssh, or pull it from the app host).
3. **Run the restore** in the sidecar (it verifies checksums first and aborts on mismatch):
   ```bash
   docker compose -f docker-compose.yml -f docker-compose.prod.yml \
       exec backup /usr/local/bin/restore.sh /backups/daily/2026-05-23
   ```
4. **Start the app:**
   ```bash
   docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d wikantik
   ```
   The search index rebuilds automatically (~30–60s).
5. **Verify:** browse the wiki; `GET /api/health` returns `status: UP`.

### Schema note

`restore.sh` issues `DROP SCHEMA public CASCADE; CREATE SCHEMA public;` with grants, then ensures
the `vector` and `pgcrypto` extensions exist before loading. The dump is `--no-owner
--no-privileges`, so a clean schema load is the safe, complete path — no stale rows in unhandled
tables. This mirrors the first-deploy init in [DockerDeployment.md section 6](DockerDeployment.md#6-initialising-the-database-from-an-existing-dump).

---

## 6. Verifying restorability

`bin/backup/verify-restore.sh` performs a non-destructive round-trip restore against a throwaway
ephemeral container, touching no live database or page tree.

```bash
# Latest snapshot in a tier (reads LATEST automatically):
bin/backup/verify-restore.sh <backup-dir>/daily
# Explicit snapshot:
bin/backup/verify-restore.sh <backup-dir>/daily/2026-05-23
# Override the pg image (match production's major version):
VERIFY_PG_IMAGE=pgvector/pgvector:pg18 bin/backup/verify-restore.sh /path/to/snap
```

It (1) gates on `sha256sum -c`, (2) spins up `pgvector/pgvector:pg18`, installs `vector` +
`pgcrypto`, loads the dump (`db.sql.gz`, or a legacy `db.sql`), (3) asserts `users`/`kg_nodes`/`page_canonical_ids` exist and that
`users` is non-empty, (4) extracts `pages.tar.gz` and compares the `.md` count to
`backup-status.json`, (5) tears down the container + temp dir via a `trap EXIT`.

Run it **quarterly** and after any PostgreSQL major-version bump (the bump changes the dump's
on-disk format — confirm it loads cleanly into the new image before you depend on it).

---

## 7. Monitoring (jakemon)

This repo emits signals; the alert rules live in the **jakemon** repo. Per project convention
there is no in-repo observability stack.

### jakemon dependency (one-time)

The textfile directory is `BACKUP_TEXTFILE_DIR` on the app host and `TEXTFILE_DIR` on the pull host
(default `/var/lib/jakemon/textfile`). Any Prometheus textfile collector pointed at that directory works;
the jakemon agent below is the Simple Agility reference setup.

Both signal types below are Prometheus **textfile** metrics. The jakemon universal agent config
(`agent/config.alloy`, in the **jakemon** repo — not this one) enables the textfile collector on
its `prometheus.exporter.unix`, reading the host's `/var/lib/jakemon/textfile` (via the agent's
`/host` mount):

```alloy
prometheus.exporter.unix "host" {
  ...
  textfile { directory = "/host/var/lib/jakemon/textfile" }
}
```

Producers (the backup sidecar, the pull host) drop `.prom` files there. Deploy the agent
change from the **jakemon** repo with `bin/deploy-agent.sh <host>` — which force-recreates the
agent so the new config loads (a plain `up -d` won't, since the config is bind-mounted and Alloy
doesn't auto-reload).
Hosts without a producer harmlessly report `node_textfile_scrape_error=1` until their dir exists.

### Signals emitted by this repo

**Local backup metrics** (`/var/lib/jakemon/textfile/wikantik_backup_<tier>.prom` on the app host):

| Metric | Type | Label | Meaning |
|--------|------|-------|---------|
| `wikantik_backup_last_success_timestamp_seconds` | gauge | `tier` | Unix time of last successful backup |
| `wikantik_backup_duration_seconds` | gauge | `tier` | Wall-clock seconds the backup took |
| `wikantik_backup_db_bytes` | gauge | `tier` | Bytes in `db.sql.gz` (compressed) for the last run |
| `wikantik_backup_pages_bytes` | gauge | `tier` | Bytes in `pages.tar.gz` for the last run |
| `wikantik_backup_last_exit_status` | gauge | `tier` | Exit status of the last run (0 = success) |

`tier` ∈ {`daily`, `weekly`, `monthly`}.

**Off-box heartbeat** (`/var/lib/jakemon/textfile/wikantik_backup_offsite.prom` on the NAS):

| Metric | Type | Meaning |
|--------|------|---------|
| `wikantik_backup_offsite_last_success_timestamp_seconds` | gauge | Unix time of last *verified* pull (preserved across a failed run) |
| `wikantik_backup_offsite_last_run_timestamp_seconds` | gauge | Unix time of last pull attempt |
| `wikantik_backup_offsite_last_exit_status` | gauge | 0 = success, 1 = checksum_failed |

(`LOKI_URL`/`PUSHGATEWAY_URL` in `nas-pull.env` are optional remote-push alternatives to the
textfile; the textfile is the deployed mechanism.)

### Alert expressions to add in the jakemon repo

26-hour windows so a brief delay or clock skew doesn't fire spuriously on a daily cadence:

**Daily backup missed:**
```promql
time() - max(wikantik_backup_last_success_timestamp_seconds{tier="daily"}) > 26*3600
```

**Backup reported failure (any tier):**
```promql
max(wikantik_backup_last_exit_status) != 0
```

**Off-box pull missed:**
```promql
time() - wikantik_backup_offsite_last_success_timestamp_seconds > 26*3600
```

**Off-box pull failed (checksum):**
```promql
wikantik_backup_offsite_last_exit_status != 0
```

Together these cover the full chain: local backup ran, local backup succeeded, off-box archive is
current, off-box archive is intact.

---

## Audit log retention

The tamper-evident `audit_log` is month-partitioned and kept indefinitely until you install the
retention job. The job (`bin/db/audit-retention.sh`, scheduled by
`bin/db/audit-retention-install-timer.sh`) pre-creates upcoming partitions and archives-then-drops
partitions older than `AUDIT_RETENTION_MONTHS` (default 84). Configuration, dry-run and timer
install are in [AuditLog.md](AuditLog.md#retention-and-the-systemd-timer).

> **AUDIT_ARCHIVE_DIR and the off-box pull.** The pull captures everything under
> `DOCKER1_BACKUP_DIR` (the rrsync-locked root, `<backup-dir>`). For dropped audit partitions to be
> cold-stored off-box, set `AUDIT_ARCHIVE_DIR` to a subdirectory of that root, for example
> `<backup-dir>/audit`. A path outside that tree is not picked up by the
> pull unless you also widen the `rrsync` root.

Restore an archived partition manually with
`pg_restore -d wikantik <archive-dir>/audit_log_YYYY_MM_<stamp>.dump`, then re-attach it if you need it
under the parent table:

```sql
ALTER TABLE audit_log ATTACH PARTITION audit_log_YYYY_MM
  FOR VALUES FROM ('YYYY-MM-01') TO ('<next-month>-01');
```

---

## Open item — pull-host immutability

The off-box archive has no immutable snapshot layer unless the pull host's filesystem provides one
(see §4). The read-only pull bounds the blast radius from the app host, but not from a pull-host
fault. Until you add snapshots or a second append-only copy, the dated snapshot tree and the read-only
pull are the operative protections.

> **Example: the wikantik.com deployment.** The NAS volume is ext4, so UGOS Btrfs snapshots are not
> available. Decide whether to add a Btrfs volume or a second append-only cold copy.
