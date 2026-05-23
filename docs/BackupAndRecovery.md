# Backup & Recovery

This document covers the full backup and recovery architecture for Wikantik: the 3-2-1
topology, how to run or verify a restore, and the exact monitoring signals and alert
expressions to configure in the jakemon repo. It reflects the live docker1 + NAS deployment.

---

## 1. Overview & 3-2-1 model

Three independent copies of Wikantik's data exist at all times:

- **Copy 1 — live production data on docker1.** The running PostgreSQL database (named volume
  `repo_pgdata`) and the page tree (bind-mounted from `WIKANTIK_PAGES_DIR`). What the
  application reads and writes every second.

- **Copy 2 — tiered local snapshots on docker1.** The `backup` sidecar (a `postgres:18-alpine`
  container running `crond`) fires `docker/backup/backup.sh` on a three-tier schedule. Each run
  produces a self-contained timestamped directory under `${BACKUP_DIR}` on the host (default
  `/home/jakefear/wikantik/backups`) with a full PostgreSQL dump, a page-tree tarball, a SHA-256
  checksum manifest, and a JSON status manifest. Retention pruning runs at the end of each run:
  30 days daily, 12 weeks weekly, 12 months monthly.

- **Copy 3 — off-box archive on the UGREEN DXP4800 Plus NAS.** The NAS runs
  `bin/backup/nas-pull.sh` daily (systemd timer), pulling every docker1 snapshot via rsync over
  SSH. It independently verifies checksums after transfer, prunes to a longer retention tail
  (90 days daily / ~6 months weekly / ~1 year monthly), and writes a Prometheus textfile
  heartbeat the NAS's jakemon Alloy agent scrapes.

### Trust model: the NAS always pulls

The NAS initiates every transfer. docker1 never holds credentials that can write to or delete
from the NAS. The NAS presents a single restricted SSH key to docker1; the `authorized_keys`
entry confines that key to a **read-only** rsync of the backup directory and nothing else
(§3). A compromised or ransomwared docker1 therefore cannot reach the off-box archive, encrypt
it, or delete it — the trust runs one way only. This read-only pull is the **primary**
ransomware defense (see §4 on the NAS-side immutability situation).

---

## 2. What runs where

### 2.1 docker1 — the `backup` sidecar

**Schedule** (`docker/backup/crontab`):

| Tier | Time | Retention on docker1 |
|------|------|----------------------|
| Daily | 02:00 every day | 30 days |
| Weekly | 03:00 every Sunday | 12 weeks |
| Monthly | 04:00 on the 1st | 12 months |

Each `docker/backup/backup.sh` run produces:

```
${BACKUP_DIR}/                 # host: /home/jakefear/wikantik/backups
  daily/
    2026-05-23/
      db.sql                 # pg_dump --no-owner --no-privileges of the full schema
      pages.tar.gz           # wiki page tree: .md, .properties, attachments
      checksums.sha256       # SHA-256 of db.sql and pages.tar.gz
      backup-status.json     # tier, date, finished_at, db_bytes, pages_bytes, page_count, exit_status
    LATEST                   # plain-text file naming the newest dated dir
  weekly/   …
  monthly/  …
```

`LATEST` lets the NAS pull and `bin/backup/verify-restore.sh` find the newest snapshot without
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
host, **outside** `${BACKUP_DIR}` — they do not ride along in the NAS pull. The docker1 Alloy
agent's textfile collector scrapes that dir (§7). If the dir is unset or unwritable the backup
logs a warning and completes normally — metrics are best-effort and never block the backup.

### 2.2 DXP4800 Plus — the NAS pull

`bin/backup/nas-pull.sh` runs daily at 05:00 via a systemd timer (§4) — a few hours after
docker1's 02:00 daily run. Per run:

1. `rsync -rlptD --partial` over SSH from docker1 into `${NAS_DEST}/`, using the read-only key.
   The source path is **relative to the rrsync-locked root** (empty `REMOTE_SRC_PATH` pulls the
   whole locked dir — see §3). `-rlptD` (not `-a`) preserves recursion, symlinks, perms, times,
   and devices but **not** owner/group: the NAS's hardened rsync cannot setuid root on receive,
   so `-o`/`-g` would fail the whole transfer.
2. Checksum verification of the newest snapshot in each tier (reads `LATEST`, runs
   `sha256sum -c`). A mismatch sets the run to exit non-zero with status `checksum_failed`.
3. Retention pruning: daily 90d, weekly 183d (~6mo), monthly 365d (~1yr).
4. Heartbeat (§7): writes `wikantik_backup_offsite.prom` to `TEXTFILE_DIR`
   (`/var/lib/jakemon/textfile`) for the NAS Alloy agent. `last_success` is preserved across a
   failed run so the freshness alert keeps climbing. `LOKI_URL`/`PUSHGATEWAY_URL` remain optional
   remote-push alternatives.

---

## 3. docker1: read-only SSH key for the NAS

Performed once. Creates a restricted account whose key can only read-rsync the backup directory.

**Step 1 — Generate an ed25519 keypair on the NAS:**

```bash
ssh-keygen -t ed25519 -f ~/.ssh/wikantik_backup_pull -N "" -C wikantik-backup-pull@nas
```

The private key never leaves the NAS. Copy `~/.ssh/wikantik_backup_pull.pub`.

**Step 2 — Create the restricted account and key entry on docker1** (as a sudoer):

```bash
# Restricted account (valid shell so sshd can run the forced command)
sudo useradd --system --create-home --home-dir /home/backup-reader --shell /bin/sh backup-reader

# The only access blocker is jakefear's home (0750); everything below the
# backup dir is already world-readable. Grant traverse-only (no listing):
sudo setfacl -m u:backup-reader:--x /home/jakefear

# Install the NAS public key, forced to read-only rsync of the backup dir only
sudo install -d -m 700 -o backup-reader -g backup-reader /home/backup-reader/.ssh
echo 'command="rrsync -ro /home/jakefear/wikantik/backups",no-pty,no-agent-forwarding,no-port-forwarding,no-X11-forwarding ssh-ed25519 AAAA... wikantik-backup-pull@nas' \
  | sudo tee /home/backup-reader/.ssh/authorized_keys >/dev/null
sudo chown backup-reader:backup-reader /home/backup-reader/.ssh/authorized_keys
sudo chmod 600 /home/backup-reader/.ssh/authorized_keys
```

Replace `AAAA...` with the public key from step 1.

`rrsync` ships with rsync (`/usr/bin/rrsync` on docker1) and restricts the key to read-only
rsync of exactly the named directory — no shell, no write, no other paths. Because it treats the
requested path as relative to that locked root, the NAS pulls with an **empty** source path; an
absolute path would be re-appended to the root and fail.

**Step 3 — Verify the trust boundary from the NAS:**

```bash
KEY=~/.ssh/wikantik_backup_pull
# Read-only list works (empty path = the locked root):
rsync --list-only -e "ssh -i $KEY" backup-reader@docker1.lan:
# Shell is refused (forced command):
ssh -i $KEY backup-reader@docker1.lan id        # -> "rrsync error: SSH_ORIGINAL_COMMAND does not run rsync"
# Write is refused (read-only):
rsync -e "ssh -i $KEY" /etc/hostname backup-reader@docker1.lan:evil   # -> "sending to read-only server is not allowed"
```

All three behaviours above are the expected, correct result.

---

## 4. NAS setup

### Layout

| Path | Purpose | Owner |
|------|---------|-------|
| `/home/jakefear/wikantik-backup/` | `nas-pull.sh`, `nas-pull.env`, `nas-install-timer.sh` | jakefear |
| `~/.ssh/wikantik_backup_pull` | read-only pull key (private) | jakefear |
| `/volume1/wikantik-backups/` | the off-box archive (44T pool) | jakefear |
| `/var/lib/jakemon/textfile/` | heartbeat `.prom`, scraped by Alloy | 1777 |

Create the two root-owned dirs once:

```bash
sudo mkdir -p /volume1/wikantik-backups && sudo chown jakefear:users /volume1/wikantik-backups
sudo mkdir -p /var/lib/jakemon/textfile && sudo chmod 1777 /var/lib/jakemon/textfile
```

### Deploying the scripts (note: NAS rsync-receive is hardened)

The DXP4800 Plus's rsync daemon is hardened and **refuses incoming rsync** (it cannot setuid
root on receive). This only affects *pushing to* the NAS — the pull *from* docker1 is
unaffected. Copy the scripts onto the NAS over plain ssh, not rsync:

```bash
# from the wikantik repo on the dev box
ssh nas.lan 'cat > ~/wikantik-backup/nas-pull.sh && chmod 755 ~/wikantik-backup/nas-pull.sh' < bin/backup/nas-pull.sh
ssh nas.lan 'cat > ~/wikantik-backup/nas-install-timer.sh && chmod 755 ~/wikantik-backup/nas-install-timer.sh' < bin/backup/nas-install-timer.sh
```

(When you do use `rsync` for scripts elsewhere, pass `--chmod=F755` — a `F644` strips the execute
bit and the file can't run.)

### Configuration

Copy `bin/backup/nas-pull.env.example` to `~/wikantik-backup/nas-pull.env` (mode 600) and set:

```
DOCKER1_HOST=docker1.lan
DOCKER1_USER=backup-reader
DOCKER1_BACKUP_DIR=/home/jakefear/wikantik/backups   # documentary; matches the rrsync root
REMOTE_SRC_PATH=                                     # empty: rrsync-relative (pull the whole root)
SSH_KEY=/home/jakefear/.ssh/wikantik_backup_pull
NAS_DEST=/volume1/wikantik-backups
NAS_RETAIN_DAILY_DAYS=90
NAS_RETAIN_WEEKLY_DAYS=183
NAS_RETAIN_MONTHLY_DAYS=365
TEXTFILE_DIR=/var/lib/jakemon/textfile
LOKI_URL=
PUSHGATEWAY_URL=
```

Verify before scheduling: `./nas-pull.sh --env nas-pull.env --dry-run`, then a real run.

### Scheduling (systemd timer)

The NAS locks down user crontabs, so scheduling uses a systemd timer. `bin/backup/nas-install-timer.sh`
installs and enables it (idempotent; prompts for sudo):

```bash
cd ~/wikantik-backup && ./nas-install-timer.sh
# inspect:  systemctl list-timers wikantik-backup-pull.timer
# run now:  sudo systemctl start wikantik-backup-pull.service
# logs:     journalctl -u wikantik-backup-pull
```

The timer fires daily at 05:00 with `Persistent=true` (a missed run after downtime catches up).

### NAS-side immutability — important caveat

The DXP4800 Plus volume here is **ext4, not Btrfs**, so UGOS scheduled *Btrfs snapshots* are not
available as an immutable layer. The defenses in place are:

- The **read-only pull** (§3): docker1 cannot write to or delete from the NAS — this is the main
  ransomware/blast-radius control and is fully effective regardless of filesystem.
- The **dated snapshot tree**: each day/week/month is a separate directory, so history exists on
  the NAS independent of docker1; only `nas-pull.sh`'s own retention prune removes old dirs.

What ext4 does **not** give you: protection against a bug in `nas-pull.sh` (or a NAS-side
compromise) overwriting/deleting the archive. If you want a true immutable layer, the options are
(a) convert/add a **Btrfs** volume on the NAS and point `NAS_DEST` at it, then enable UGOS
scheduled snapshots; or (b) add a second, append-only cold copy. This is deferred — see the
open item at the end of this doc.

---

## 5. Restore procedure

Full restores run via `docker/backup/restore.sh` inside the `backup` sidecar. The script drops
and rebuilds the `public` schema, loads the dump in full (all tables — users, roles, groups,
policy grants, all `kg_*` tables, page metadata, embeddings, schema migrations), verifies core
tables populated, then restores the page tree. Lucene and in-memory caches rebuild on startup.

### DR sequence

1. **Stop the app** (prevent writes during restore):
   ```bash
   docker compose -f docker-compose.yml -f docker-compose.prod.yml stop wikantik
   ```
2. **Choose a snapshot:**
   ```bash
   ls /home/jakefear/wikantik/backups/daily/
   cat /home/jakefear/wikantik/backups/daily/LATEST
   ```
   If restoring from the NAS, copy the chosen snapshot back to docker1 first (the NAS can push
   over ssh-cat, or pull it from the NAS to docker1).
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
tables. This mirrors the first-deploy init in `docs/DockerDeployment.md §6`.

---

## 6. Verifying restorability

`bin/backup/verify-restore.sh` performs a non-destructive round-trip restore against a throwaway
ephemeral container, touching no live database or page tree.

```bash
# Latest snapshot in a tier (reads LATEST automatically):
bin/backup/verify-restore.sh /home/jakefear/wikantik/backups/daily
# Explicit snapshot:
bin/backup/verify-restore.sh /home/jakefear/wikantik/backups/daily/2026-05-23
# Override the pg image (match production's major version):
VERIFY_PG_IMAGE=pgvector/pgvector:pg18 bin/backup/verify-restore.sh /path/to/snap
```

It (1) gates on `sha256sum -c`, (2) spins up `pgvector/pgvector:pg18`, installs `vector` +
`pgcrypto`, loads `db.sql`, (3) asserts `users`/`kg_nodes`/`page_canonical_ids` exist and that
`users` is non-empty, (4) extracts `pages.tar.gz` and compares the `.md` count to
`backup-status.json`, (5) tears down the container + temp dir via a `trap EXIT`.

Run it **quarterly** and after any PostgreSQL major-version bump (the bump changes the dump's
on-disk format — confirm it loads cleanly into the new image before you depend on it).

---

## 7. Monitoring (jakemon)

This repo emits signals; the alert rules live in the **jakemon** repo. Per project convention
there is no in-repo observability stack.

### jakemon dependency (one-time)

Both signal types below are Prometheus **textfile** metrics. The jakemon universal agent config
(`agent/config.alloy`) enables the textfile collector on its `prometheus.exporter.unix`, reading
the host's `/var/lib/jakemon/textfile` (via the agent's `/host` mount):

```alloy
prometheus.exporter.unix "host" {
  ...
  textfile { directory = "/host/var/lib/jakemon/textfile" }
}
```

Producers (docker1's backup sidecar, the NAS pull) drop `.prom` files there. Deploy the agent
change with `bin/deploy-agent.sh <host>` — which force-recreates the agent so the new config
loads (a plain `up -d` won't, since the config is bind-mounted and Alloy doesn't auto-reload).
Hosts without a producer harmlessly report `node_textfile_scrape_error=1` until their dir exists.

### Signals emitted by this repo

**Local backup metrics** (`/var/lib/jakemon/textfile/wikantik_backup_<tier>.prom` on docker1):

| Metric | Type | Label | Meaning |
|--------|------|-------|---------|
| `wikantik_backup_last_success_timestamp_seconds` | gauge | `tier` | Unix time of last successful backup |
| `wikantik_backup_duration_seconds` | gauge | `tier` | Wall-clock seconds the backup took |
| `wikantik_backup_db_bytes` | gauge | `tier` | Bytes in `db.sql` for the last run |
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

## Open item — NAS immutability on ext4

The off-box archive currently has no immutable snapshot layer because the NAS volume is ext4
(see §4). The read-only pull bounds the blast radius from docker1, but not from a NAS-side fault.
Decide whether to add a Btrfs volume (true UGOS snapshots) or a second append-only cold copy. Until
then, the dated snapshot tree + read-only pull are the operative protections.
