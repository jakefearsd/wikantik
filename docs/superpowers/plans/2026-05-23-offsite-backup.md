# Off-Box Backup & Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Capture page-tree + PostgreSQL backups nightly on `docker1`, archive them off-box onto a UGREEN DXP4800 Plus NAS via a NAS-initiated pull, verify integrity, prove restorability, and surface success/failure in jakemon.

**Architecture:** The existing `backup` sidecar keeps making consistent tiered snapshots on `docker1`. We add a status manifest + Prometheus textfile metrics to each run, fix `restore.sh` to do a complete-schema restore, add a runnable ephemeral restore drill, and ship a NAS-side rsync-over-SSH pull script (run as a scheduled container on the NAS) that verifies checksums and emits an off-box heartbeat. The NAS holds a read-only SSH key into the backup dir only — a compromised `docker1` cannot touch the archive.

**Tech Stack:** POSIX `sh` (sidecar scripts), `bash` (host scripts + tests), `pg_dump`/`psql`, Docker, rsync-over-SSH, Prometheus textfile-collector format, Loki/Pushgateway HTTP push.

**Spec:** `docs/superpowers/specs/2026-05-23-offsite-backup-design.md`

**Sequencing note:** Tasks 1–8 are all `docker1`/repo-side and need nothing from the NAS. The live key-install, NAS pull-container scheduling, Btrfs snapshot config, and first end-to-end pull happen *after the user confirms the NAS is back up* — captured as Task 9 (manual runbook, not code).

**Conventions to follow:**
- Every script under `bin/` and `docker/` answers `-h`/`--help` from its header docstring via:
  `awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"`
- Sidecar scripts (`docker/backup/*.sh`) are POSIX `sh` with `set -eu`. Host scripts (`bin/**`) are `bash` with `set -euo pipefail`.
- Never swallow errors silently — `echo` a warning with context (matches CLAUDE.md "no empty catch" rule in shell form).
- Stage files by exact name in commits — never `git add -A`.
- Tests live in `bin/tests/` as bash scripts using `fail()`/`ok()` helpers and tmpdir sandboxes (pattern: `bin/tests/test-remote.sh`).
- End every commit message with the `Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>` trailer.

---

## File Structure

- **Modify** `docker/backup/backup.sh` — add status manifest, `LATEST` pointer, and Prometheus textfile metrics emission (Tasks 1, 2).
- **Modify** `docker/backup/restore.sh` — full-schema restore instead of 4-table restore (Task 3).
- **Create** `bin/backup/verify-restore.sh` — ephemeral restore drill (Task 4).
- **Create** `bin/backup/nas-pull.sh` — NAS-side rsync pull + checksum verify + heartbeat + tier prune (Task 5).
- **Create** `bin/backup/nas-pull.env.example` — documented config for the pull (Task 5).
- **Modify** `docker-compose.prod.yml` — pass `BACKUP_METRICS_DIR` to the sidecar (Task 6).
- **Modify** `.env.example` — document new backup env vars (Task 6).
- **Create** `bin/tests/test-backup.sh` — assertions for manifest/metrics emission and `nas-pull.sh` dry-run (Tasks 1, 2, 5).
- **Create** `docs/BackupAndRecovery.md` — operator guide; cross-link from `docs/WikantikOperations.md`, `docs/DockerDeployment.md` (Task 7).

`bin/backup/` is a new directory. `verify-restore.sh` and `nas-pull.sh` are host/NAS bash scripts (not sidecar `sh`).

---

## Task 1: Backup status manifest + LATEST pointer

**Files:**
- Modify: `docker/backup/backup.sh`
- Create: `bin/tests/test-backup.sh`

- [ ] **Step 1: Write the failing test**

Create `bin/tests/test-backup.sh`:

```bash
#!/usr/bin/env bash
# Tests for the backup tooling: backup.sh manifest/metrics emission and
# nas-pull.sh dry-run. Pure filesystem + grep style — no real PG, no real
# ssh. Stubs pg_dump/psql/rsync/curl on PATH so the scripts run offline.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

TMPS=()
cleanup() { local d; for d in "${TMPS[@]+"${TMPS[@]}"}"; do [[ -d "$d" ]] && rm -rf "$d"; done; return 0; }
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
ok()   { echo "ok: $*"; }

# Build a sandbox with stub pg_dump/psql on PATH and fake pages + backups dirs.
# Echoes the sandbox root.
make_backup_sandbox() {
    local tmp; tmp="$(mktemp -d)"; TMPS+=("${tmp}")
    mkdir -p "${tmp}/bin" "${tmp}/pages/sub" "${tmp}/backups" "${tmp}/metrics"
    cat > "${tmp}/bin/pg_dump" <<'STUB'
#!/bin/sh
echo "-- fake dump"; echo "CREATE TABLE users (id int);"
STUB
    cat > "${tmp}/bin/psql" <<'STUB'
#!/bin/sh
echo 0
STUB
    chmod +x "${tmp}/bin/pg_dump" "${tmp}/bin/psql"
    echo "# page" > "${tmp}/pages/Main.md"
    echo "# page2" > "${tmp}/pages/sub/Other.md"
    echo "${tmp}"
}

# run_backup SANDBOX TIER — runs backup.sh with stubs + temp dirs wired in.
run_backup() {
    local sb="$1" tier="$2"
    PATH="${sb}/bin:${PATH}" \
    POSTGRES_HOST=fakehost POSTGRES_USER=fakeuser POSTGRES_DB=fakedb PGPASSWORD=x \
    BACKUP_ROOT="${sb}/backups" PAGES_DIR="${sb}/pages" \
    BACKUP_METRICS_DIR="${sb}/metrics" \
        sh docker/backup/backup.sh "${tier}"
}

test_manifest_written() {
    local sb; sb="$(make_backup_sandbox)"
    run_backup "${sb}" daily >/dev/null 2>&1 || fail "backup.sh exited non-zero"
    local day; day="$(date +%Y-%m-%d)"
    local dir="${sb}/backups/daily/${day}"
    [[ -f "${dir}/backup-status.json" ]] || fail "backup-status.json not written"
    grep -q '"tier": "daily"' "${dir}/backup-status.json" || fail "tier missing from manifest"
    grep -q '"exit_status": 0' "${dir}/backup-status.json" || fail "exit_status missing from manifest"
    grep -q '"page_count": 2' "${dir}/backup-status.json" || fail "page_count wrong in manifest"
    [[ -f "${sb}/backups/daily/LATEST" ]] || fail "LATEST pointer not written"
    grep -q "${day}" "${sb}/backups/daily/LATEST" || fail "LATEST does not point at today"
    ok "manifest + LATEST written"
}

test_manifest_written

echo "ALL BACKUP TESTS PASSED"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bash bin/tests/test-backup.sh`
Expected: FAIL — `backup.sh` ignores `BACKUP_ROOT`/`PAGES_DIR`/`BACKUP_METRICS_DIR` (hardcodes `/backups` and `/var/wikantik/pages`) and writes no `backup-status.json`/`LATEST`. The stubbed run will not produce the expected files.

- [ ] **Step 3: Make paths overridable + write manifest and LATEST**

In `docker/backup/backup.sh`, replace the hardcoded path assignments near the top (currently `BACKUP_PATH="/backups/${TIER}/${DATE}"`) with overridable roots, and add manifest + LATEST emission after the checksum step. Edit the relevant regions to read:

```sh
TIER="${1:-daily}"
DATE="$(date +%Y-%m-%d)"
BACKUP_ROOT="${BACKUP_ROOT:-/backups}"
PAGES_DIR="${PAGES_DIR:-/var/wikantik/pages}"
BACKUP_PATH="${BACKUP_ROOT}/${TIER}/${DATE}"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-30}"
```

Change the pages references from `/var/wikantik/pages` to `${PAGES_DIR}` in the
archive step (the `find` and the `tar -C`):

```sh
PAGE_COUNT=$(find "${PAGES_DIR}" -name '*.md' 2>/dev/null | wc -l)
tar -czf "${BACKUP_PATH}/pages.tar.gz" -C "${PAGES_DIR}" .
```

After the existing checksum step (`sha256sum db.sql pages.tar.gz > checksums.sha256`)
and before the prune step, add:

```sh
# --- Status manifest + LATEST pointer ---
FINISHED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
cat > "${BACKUP_PATH}/backup-status.json" <<JSON
{
  "tier": "${TIER}",
  "date": "${DATE}",
  "finished_at": "${FINISHED_AT}",
  "db_bytes": ${DB_SIZE},
  "pages_bytes": ${PAGES_SIZE},
  "page_count": ${PAGE_COUNT},
  "exit_status": 0
}
JSON
printf '%s\n' "${DATE}" > "${BACKUP_ROOT}/${TIER}/LATEST"
echo "  backup-status.json + LATEST written"
```

(`DB_SIZE`, `PAGES_SIZE`, `PAGE_COUNT` are already computed earlier in the script.)

- [ ] **Step 4: Run test to verify it passes**

Run: `bash bin/tests/test-backup.sh`
Expected: `ok: manifest + LATEST written` then `ALL BACKUP TESTS PASSED`.

- [ ] **Step 5: Commit**

```bash
git add docker/backup/backup.sh bin/tests/test-backup.sh
git commit -m "backup: write status manifest + LATEST pointer; make paths overridable

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 2: Prometheus textfile metrics from backup.sh

**Files:**
- Modify: `docker/backup/backup.sh`
- Modify: `bin/tests/test-backup.sh`

- [ ] **Step 1: Add the failing test**

In `bin/tests/test-backup.sh`, add this function and a call to it before the
final `echo "ALL BACKUP TESTS PASSED"`:

```bash
test_metrics_written() {
    local sb; sb="$(make_backup_sandbox)"
    run_backup "${sb}" weekly >/dev/null 2>&1 || fail "backup.sh exited non-zero"
    local prom="${sb}/metrics/wikantik_backup_weekly.prom"
    [[ -f "${prom}" ]] || fail "metrics .prom not written"
    grep -q 'wikantik_backup_last_success_timestamp_seconds{tier="weekly"}' "${prom}" \
        || fail "last_success metric missing"
    grep -q 'wikantik_backup_last_exit_status{tier="weekly"} 0' "${prom}" \
        || fail "exit_status metric missing/non-zero"
    grep -q 'wikantik_backup_db_bytes{tier="weekly"}' "${prom}" \
        || fail "db_bytes metric missing"
    # No leftover temp file (atomic write via mv).
    [[ -z "$(find "${sb}/metrics" -name '*.tmp' 2>/dev/null)" ]] \
        || fail "metrics temp file left behind (write not atomic)"
    ok "metrics .prom written atomically"
}

test_metrics_written
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bash bin/tests/test-backup.sh`
Expected: FAIL — `metrics .prom not written` (no metrics emission exists yet).

- [ ] **Step 3: Emit metrics atomically**

In `docker/backup/backup.sh`, capture a start time near the top (right after
`BACKUP_PATH=` is set):

```sh
START_EPOCH="$(date +%s)"
```

Then, immediately after the manifest/LATEST block from Task 1, add:

```sh
# --- Prometheus textfile metrics (best-effort; never fail the backup) ---
METRICS_DIR="${BACKUP_METRICS_DIR:-}"
if [ -n "${METRICS_DIR}" ]; then
    if mkdir -p "${METRICS_DIR}" 2>/dev/null && [ -w "${METRICS_DIR}" ]; then
        NOW_EPOCH="$(date +%s)"
        DURATION=$(( NOW_EPOCH - START_EPOCH ))
        PROM="${METRICS_DIR}/wikantik_backup_${TIER}.prom"
        TMP="${PROM}.tmp"
        {
            echo "# HELP wikantik_backup_last_success_timestamp_seconds Unix time of last successful backup."
            echo "# TYPE wikantik_backup_last_success_timestamp_seconds gauge"
            echo "wikantik_backup_last_success_timestamp_seconds{tier=\"${TIER}\"} ${NOW_EPOCH}"
            echo "# HELP wikantik_backup_duration_seconds Wall-clock duration of the backup run."
            echo "# TYPE wikantik_backup_duration_seconds gauge"
            echo "wikantik_backup_duration_seconds{tier=\"${TIER}\"} ${DURATION}"
            echo "# HELP wikantik_backup_db_bytes Size of the pg_dump output in bytes."
            echo "# TYPE wikantik_backup_db_bytes gauge"
            echo "wikantik_backup_db_bytes{tier=\"${TIER}\"} ${DB_SIZE}"
            echo "# HELP wikantik_backup_pages_bytes Size of the pages tarball in bytes."
            echo "# TYPE wikantik_backup_pages_bytes gauge"
            echo "wikantik_backup_pages_bytes{tier=\"${TIER}\"} ${PAGES_SIZE}"
            echo "# HELP wikantik_backup_last_exit_status Exit status of last backup (0=success)."
            echo "# TYPE wikantik_backup_last_exit_status gauge"
            echo "wikantik_backup_last_exit_status{tier=\"${TIER}\"} 0"
        } > "${TMP}"
        mv -f "${TMP}" "${PROM}"
        echo "  metrics written to ${PROM}"
    else
        echo "  WARN: BACKUP_METRICS_DIR=${METRICS_DIR} not writable — skipping metrics"
    fi
fi
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bash bin/tests/test-backup.sh`
Expected: `ok: metrics .prom written atomically` and `ALL BACKUP TESTS PASSED`.

- [ ] **Step 5: Commit**

```bash
git add docker/backup/backup.sh bin/tests/test-backup.sh
git commit -m "backup: emit Prometheus textfile metrics per tier (atomic write)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 3: Full-schema restore (defect fix)

**Files:**
- Modify: `docker/backup/restore.sh`

**Context:** The current restore drops only `users, roles, groups, group_members`,
but a real DB has dozens of tables. Loading the full dump on top either errors on
existing objects or leaves stale rows in untouched tables. The dump is taken with
`--no-owner --no-privileges`, so a clean-schema reload is the complete, safe path.

- [ ] **Step 1: Replace the partial restore with a clean-schema reload**

In `docker/backup/restore.sh`, replace the entire "Step 2/3: Restoring
PostgreSQL database" block (the `DROP TABLE IF EXISTS group_members, groups,
roles, users CASCADE;` through the `USER_COUNT` verification) with:

```sh
# --- 2. Restore PostgreSQL database (full clean-schema reload) ---
echo ""
echo "Step 2/3: Restoring PostgreSQL database (full schema)..."
echo "  Resetting public schema..."
psql -h "${POSTGRES_HOST}" -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" \
    -v ON_ERROR_STOP=1 \
    -c "DROP SCHEMA IF EXISTS public CASCADE;" \
    -c "CREATE SCHEMA public;" \
    -c "GRANT ALL ON SCHEMA public TO \"${POSTGRES_USER}\";" \
    -c "GRANT ALL ON SCHEMA public TO public;" > /dev/null

echo "  Ensuring required extensions exist..."
psql -h "${POSTGRES_HOST}" -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" \
    -v ON_ERROR_STOP=1 \
    -c "CREATE EXTENSION IF NOT EXISTS vector;" \
    -c "CREATE EXTENSION IF NOT EXISTS pgcrypto;" > /dev/null 2>&1 || \
    echo "  WARN: extension creation reported an issue (dump may recreate them)"

echo "  Loading backup..."
if ! psql -h "${POSTGRES_HOST}" -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" \
    -v ON_ERROR_STOP=1 -q < "${BACKUP_PATH}/db.sql" > /tmp/restore.log 2>&1; then
    echo "  ERROR: restore failed — last lines of psql output:"
    tail -20 /tmp/restore.log
    exit 1
fi

# Verify a representative spread of core tables loaded.
echo "  Verifying core tables..."
for tbl in users kg_nodes page_canonical_ids; do
    CNT=$(psql -h "${POSTGRES_HOST}" -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" \
        -t -A -c "SELECT COUNT(*) FROM ${tbl};" 2>/dev/null || echo "MISSING")
    if [ "${CNT}" = "MISSING" ]; then
        echo "  ERROR: expected table '${tbl}' is missing after restore!"
        exit 1
    fi
    echo "    ${tbl}: ${CNT} row(s)"
done
```

Also update the header comment block: change the "What gets restored" section
from the 4-table list to:

```sh
# What gets restored:
#   - The ENTIRE PostgreSQL schema (public schema is dropped and rebuilt
#     from the dump): users, roles, groups, policy_grants, all kg_* tables,
#     page_canonical_ids, embeddings, schema_migrations, everything.
#   - Wiki pages: all .md files, .properties files, and attachments
```

- [ ] **Step 2: Syntax-check the script**

Run: `sh -n docker/backup/restore.sh && echo "syntax OK"`
Expected: `syntax OK`

- [ ] **Step 3: Verify help still renders**

Run: `sh docker/backup/restore.sh --help | head -5`
Expected: the header docstring prints (no error).

- [ ] **Step 4: Commit**

```bash
git add docker/backup/restore.sh
git commit -m "backup: restore full schema, not just 4 tables (defect fix)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

(Functional verification of the restore is Task 4's drill, which exercises this
path end-to-end against a real ephemeral DB.)

---

## Task 4: Ephemeral restore drill (`verify-restore.sh`)

**Files:**
- Create: `bin/backup/verify-restore.sh`

**Context:** Proves the latest snapshot actually restores, against a throwaway
Postgres container, touching nothing real. This is the quarterly drill and the
functional test for Task 3. Uses `docker exec` so no host port is needed.

- [ ] **Step 1: Create the script**

Create `bin/backup/verify-restore.sh`:

```bash
#!/usr/bin/env bash
#
# verify-restore.sh — prove a backup snapshot is restorable.
#
# Spins up a throwaway PostgreSQL (pgvector) container, loads db.sql from a
# snapshot, extracts pages.tar.gz to a temp dir, asserts core-table row counts
# and that the page count matches backup-status.json, then tears everything
# down. Touches no real database or page tree.
#
# Usage:
#   verify-restore.sh /path/to/backups/daily/2026-05-23   # explicit snapshot
#   verify-restore.sh /path/to/backups/daily              # uses LATEST
#   verify-restore.sh --help
#
# Exit status: 0 if the snapshot restores and verifies; non-zero otherwise.
#
# Requires: docker, and a snapshot dir containing db.sql, pages.tar.gz,
# checksums.sha256, and (optionally) backup-status.json.
set -euo pipefail

case "${1:-}" in
    -h|--help)
        awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
        exit 0
        ;;
esac

ARG="${1:?Usage: verify-restore.sh /path/to/snapshot-or-tier-dir}"
PG_IMAGE="${VERIFY_PG_IMAGE:-pgvector/pgvector:pg18}"
CONTAINER="wikantik-verify-restore-$$"
WORKDIR="$(mktemp -d)"

cleanup() {
    docker rm -f "${CONTAINER}" >/dev/null 2>&1 || true
    rm -rf "${WORKDIR}" || true
}
trap cleanup EXIT

# Resolve snapshot: if ARG has a LATEST file, follow it; else treat ARG as the snapshot.
if [ -f "${ARG}/LATEST" ]; then
    SNAP="${ARG}/$(cat "${ARG}/LATEST")"
else
    SNAP="${ARG}"
fi

for f in db.sql pages.tar.gz checksums.sha256; do
    [ -f "${SNAP}/${f}" ] || { echo "ERROR: ${SNAP}/${f} not found"; exit 1; }
done
echo "Verifying snapshot: ${SNAP}"

echo "Step 1/4: checksum verification"
( cd "${SNAP}" && sha256sum -c checksums.sha256 ) || { echo "ERROR: checksum mismatch"; exit 1; }

echo "Step 2/4: starting throwaway ${PG_IMAGE}"
docker run -d --name "${CONTAINER}" \
    -e POSTGRES_PASSWORD=verify -e POSTGRES_DB=wikantik_verify -e POSTGRES_USER=verify \
    "${PG_IMAGE}" >/dev/null
# Wait for readiness.
for _ in $(seq 1 30); do
    if docker exec "${CONTAINER}" pg_isready -U verify -d wikantik_verify >/dev/null 2>&1; then
        break
    fi
    sleep 2
done
docker exec "${CONTAINER}" pg_isready -U verify -d wikantik_verify >/dev/null 2>&1 \
    || { echo "ERROR: ephemeral PG never became ready"; exit 1; }

echo "Step 3/4: loading db.sql + asserting core tables"
docker exec "${CONTAINER}" psql -U verify -d wikantik_verify -v ON_ERROR_STOP=1 \
    -c "CREATE EXTENSION IF NOT EXISTS vector;" -c "CREATE EXTENSION IF NOT EXISTS pgcrypto;" \
    >/dev/null 2>&1 || true
if ! docker exec -i "${CONTAINER}" psql -U verify -d wikantik_verify -q \
        < "${SNAP}/db.sql" > "${WORKDIR}/load.log" 2>&1; then
    echo "ERROR: db.sql failed to load — last lines:"; tail -20 "${WORKDIR}/load.log"; exit 1
fi
for tbl in users kg_nodes page_canonical_ids; do
    CNT=$(docker exec "${CONTAINER}" psql -U verify -d wikantik_verify -t -A \
        -c "SELECT COUNT(*) FROM ${tbl};" 2>/dev/null || echo "MISSING")
    [ "${CNT}" = "MISSING" ] && { echo "ERROR: table ${tbl} missing after restore"; exit 1; }
    echo "    ${tbl}: ${CNT} row(s)"
done

echo "Step 4/4: extracting pages + checking count"
mkdir -p "${WORKDIR}/pages"
tar -xzf "${SNAP}/pages.tar.gz" -C "${WORKDIR}/pages"
ACTUAL_PAGES=$(find "${WORKDIR}/pages" -name '*.md' | wc -l | tr -d ' ')
echo "    extracted ${ACTUAL_PAGES} .md file(s)"
if [ -f "${SNAP}/backup-status.json" ]; then
    EXPECTED=$(grep -o '"page_count": *[0-9]*' "${SNAP}/backup-status.json" | grep -o '[0-9]*')
    if [ -n "${EXPECTED}" ] && [ "${ACTUAL_PAGES}" != "${EXPECTED}" ]; then
        echo "ERROR: page count ${ACTUAL_PAGES} != manifest ${EXPECTED}"; exit 1
    fi
    echo "    matches manifest page_count=${EXPECTED}"
fi

echo ""
echo "RESTORE VERIFICATION PASSED for ${SNAP}"
```

- [ ] **Step 2: Make executable + syntax check**

Run: `chmod +x bin/backup/verify-restore.sh && bash -n bin/backup/verify-restore.sh && echo OK`
Expected: `OK`

- [ ] **Step 3: Verify help renders**

Run: `bin/backup/verify-restore.sh --help | head -3`
Expected: the docstring prints (`verify-restore.sh — prove a backup snapshot is restorable.`).

- [ ] **Step 4: Verify the missing-file guard**

Run: `bin/backup/verify-restore.sh /tmp/does-not-exist-$$ ; echo "exit=$?"`
Expected: `ERROR: /tmp/does-not-exist-.../db.sql not found` and `exit=1`.

- [ ] **Step 5: Commit**

```bash
git add bin/backup/verify-restore.sh
git commit -m "backup: add ephemeral restore-drill verifier

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 5: NAS-side pull script + config example

**Files:**
- Create: `bin/backup/nas-pull.sh`
- Create: `bin/backup/nas-pull.env.example`
- Modify: `bin/tests/test-backup.sh`

**Context:** Runs on the NAS (as a scheduled container once the NAS is up).
rsync-over-SSH pull from docker1's backup dir, checksum verify, off-box heartbeat,
and tier prune to the NAS retention (daily 90d / weekly 6mo / monthly 1y). Designed
to run offline-testably: `--dry-run` prints the rsync command without executing.

- [ ] **Step 1: Create the config example**

Create `bin/backup/nas-pull.env.example`:

```sh
# nas-pull.env — copy to nas-pull.env on the NAS and edit. Sourced by nas-pull.sh.
#
# --- Required: source (docker1) ---
DOCKER1_HOST=docker1
DOCKER1_USER=backup-reader
DOCKER1_BACKUP_DIR=/home/jakefear/wikantik/backups
SSH_KEY=/config/nas-backup-key          # read-only key installed on docker1

# --- Required: destination (NAS) ---
NAS_DEST=/volume1/wikantik-backups       # dedicated subvolume/share on the NAS

# --- NAS-side retention (days). Longer tail than docker1. ---
NAS_RETAIN_DAILY_DAYS=90
NAS_RETAIN_WEEKLY_DAYS=183               # ~6 months
NAS_RETAIN_MONTHLY_DAYS=365              # ~1 year

# --- Off-box heartbeat (pick one; leave both empty to disable) ---
# Loki push (recommended — single HTTP POST, nothing to install):
LOKI_URL=http://inference:3100/loki/api/v1/push
# Pushgateway alternative:
PUSHGATEWAY_URL=
```

- [ ] **Step 2: Add the failing dry-run test**

In `bin/tests/test-backup.sh`, add before the final pass line:

```bash
test_nas_pull_dryrun() {
    local tmp; tmp="$(mktemp -d)"; TMPS+=("${tmp}")
    cat > "${tmp}/nas-pull.env" <<EOF
DOCKER1_HOST=docker1.invalid
DOCKER1_USER=backup-reader
DOCKER1_BACKUP_DIR=/srv/backups
SSH_KEY=/tmp/nokey
NAS_DEST=${tmp}/dest
NAS_RETAIN_DAILY_DAYS=90
NAS_RETAIN_WEEKLY_DAYS=183
NAS_RETAIN_MONTHLY_DAYS=365
LOKI_URL=
PUSHGATEWAY_URL=
EOF
    mkdir -p "${tmp}/dest"
    local out
    out="$(bin/backup/nas-pull.sh --env "${tmp}/nas-pull.env" --dry-run 2>&1)" \
        || fail "nas-pull --dry-run exited non-zero: ${out}"
    grep -q "rsync" <<<"${out}" || fail "dry-run did not print rsync command"
    grep -q "backup-reader@docker1.invalid:/srv/backups/" <<<"${out}" \
        || fail "dry-run rsync source wrong"
    grep -q -- "--dry-run" <<<"${out}" || fail "dry-run flag not passed to rsync"
    ok "nas-pull dry-run prints expected rsync"
}

test_nas_pull_dryrun
```

- [ ] **Step 3: Run test to verify it fails**

Run: `bash bin/tests/test-backup.sh`
Expected: FAIL — `bin/backup/nas-pull.sh` does not exist yet.

- [ ] **Step 4: Create the pull script**

Create `bin/backup/nas-pull.sh`:

```bash
#!/usr/bin/env bash
#
# nas-pull.sh — pull Wikantik backups from docker1 onto the NAS, verify
# checksums, prune to NAS retention, and emit an off-box heartbeat.
#
# Runs on the DXP4800 Plus (typically inside a tiny scheduled container with
# rsync + openssh-client + curl). Pull model: the NAS reaches into docker1
# using a read-only SSH key. docker1 holds no NAS credentials.
#
# Usage:
#   nas-pull.sh [--env FILE] [--dry-run]
#     --env FILE   config file to source (default: ./nas-pull.env)
#     --dry-run    print the rsync command (with --dry-run) and skip prune/heartbeat
#   nas-pull.sh --help
#
# Config (see nas-pull.env.example): DOCKER1_HOST, DOCKER1_USER,
# DOCKER1_BACKUP_DIR, SSH_KEY, NAS_DEST, NAS_RETAIN_*_DAYS, LOKI_URL,
# PUSHGATEWAY_URL.
set -euo pipefail

case "${1:-}" in
    -h|--help)
        awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
        exit 0
        ;;
esac

ENV_FILE="./nas-pull.env"
DRY_RUN=0
while [ $# -gt 0 ]; do
    case "$1" in
        --env) ENV_FILE="$2"; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        *) echo "Unknown arg: $1" >&2; exit 2 ;;
    esac
done

[ -f "${ENV_FILE}" ] || { echo "ERROR: env file not found: ${ENV_FILE}" >&2; exit 1; }
# shellcheck disable=SC1090
. "${ENV_FILE}"

: "${DOCKER1_HOST:?set DOCKER1_HOST}"
: "${DOCKER1_USER:?set DOCKER1_USER}"
: "${DOCKER1_BACKUP_DIR:?set DOCKER1_BACKUP_DIR}"
: "${NAS_DEST:?set NAS_DEST}"
SSH_KEY="${SSH_KEY:-}"

SRC="${DOCKER1_USER}@${DOCKER1_HOST}:${DOCKER1_BACKUP_DIR}/"
SSH_CMD="ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new"
[ -n "${SSH_KEY}" ] && SSH_CMD="${SSH_CMD} -i ${SSH_KEY}"

RSYNC_OPTS=(-a --partial --human-readable -e "${SSH_CMD}")

if [ "${DRY_RUN}" -eq 1 ]; then
    echo "DRY RUN — would execute:"
    echo "rsync ${RSYNC_OPTS[*]} --dry-run ${SRC} ${NAS_DEST}/"
    exit 0
fi

echo "[$(date)] Pulling backups: ${SRC} -> ${NAS_DEST}/"
mkdir -p "${NAS_DEST}"
rsync "${RSYNC_OPTS[@]}" "${SRC}" "${NAS_DEST}/"

# --- Verify checksums of the newest snapshot in each tier ---
VERIFY_FAIL=0
for tier in daily weekly monthly; do
    latest_file="${NAS_DEST}/${tier}/LATEST"
    [ -f "${latest_file}" ] || continue
    snap="${NAS_DEST}/${tier}/$(cat "${latest_file}")"
    if [ -f "${snap}/checksums.sha256" ]; then
        if ( cd "${snap}" && sha256sum -c checksums.sha256 >/dev/null 2>&1 ); then
            echo "  ${tier}: checksum OK ($(basename "${snap}"))"
        else
            echo "  ERROR: ${tier} checksum verification FAILED for ${snap}" >&2
            VERIFY_FAIL=1
        fi
    fi
done

# --- Prune to NAS retention ---
prune_tier() {
    tier="$1"; days="$2"
    [ -d "${NAS_DEST}/${tier}" ] || return 0
    pruned=$(find "${NAS_DEST}/${tier}" -mindepth 1 -maxdepth 1 -type d -mtime "+${days}" 2>/dev/null | wc -l)
    find "${NAS_DEST}/${tier}" -mindepth 1 -maxdepth 1 -type d -mtime "+${days}" -exec rm -rf {} + 2>/dev/null || true
    echo "  pruned ${pruned} ${tier} snapshot(s) older than ${days}d"
}
prune_tier daily   "${NAS_RETAIN_DAILY_DAYS:-90}"
prune_tier weekly  "${NAS_RETAIN_WEEKLY_DAYS:-183}"
prune_tier monthly "${NAS_RETAIN_MONTHLY_DAYS:-365}"

# --- Off-box heartbeat ---
NOW_EPOCH="$(date +%s)"
STATUS="success"; [ "${VERIFY_FAIL}" -eq 1 ] && STATUS="checksum_failed"
if [ -n "${LOKI_URL:-}" ]; then
    NOW_NS="${NOW_EPOCH}000000000"
    curl -sf -X POST "${LOKI_URL}" -H 'Content-Type: application/json' \
        --data-binary "{\"streams\":[{\"stream\":{\"job\":\"wikantik_backup_offsite\",\"status\":\"${STATUS}\"},\"values\":[[\"${NOW_NS}\",\"offsite pull ${STATUS} dest=${NAS_DEST}\"]]}]}" \
        >/dev/null 2>&1 && echo "  heartbeat pushed to Loki (${STATUS})" \
        || echo "  WARN: Loki heartbeat push failed"
elif [ -n "${PUSHGATEWAY_URL:-}" ]; then
    printf 'wikantik_backup_offsite_last_success_timestamp_seconds %s\n' "${NOW_EPOCH}" \
        | curl -sf --data-binary @- "${PUSHGATEWAY_URL}/metrics/job/wikantik_backup_offsite" \
        >/dev/null 2>&1 && echo "  heartbeat pushed to Pushgateway" \
        || echo "  WARN: Pushgateway heartbeat push failed"
fi

[ "${VERIFY_FAIL}" -eq 1 ] && { echo "[$(date)] off-box pull completed WITH checksum failures" >&2; exit 1; }
echo "[$(date)] off-box pull complete."
```

- [ ] **Step 5: Make executable + run tests**

Run: `chmod +x bin/backup/nas-pull.sh && bash bin/tests/test-backup.sh`
Expected: `ok: nas-pull dry-run prints expected rsync` and `ALL BACKUP TESTS PASSED`.

- [ ] **Step 6: Commit**

```bash
git add bin/backup/nas-pull.sh bin/backup/nas-pull.env.example bin/tests/test-backup.sh
git commit -m "backup: add NAS-side pull script (rsync + checksum verify + heartbeat + prune)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 6: Wire BACKUP_METRICS_DIR into the prod sidecar + .env.example

**Files:**
- Modify: `docker-compose.prod.yml`
- Modify: `.env.example`

- [ ] **Step 1: Pass BACKUP_METRICS_DIR to the backup sidecar**

In `docker-compose.prod.yml`, in the `backup:` service `environment:` block (after
the `BACKUP_RETENTION_DAYS` line), add:

```yaml
      BACKUP_METRICS_DIR: ${BACKUP_METRICS_DIR:-/backups/metrics}
```

The metrics land under the already-mounted `${BACKUP_DIR}` host bind (as
`${BACKUP_DIR}/metrics/*.prom`), so no new mount is needed — docker1's jakemon
Alloy textfile collector watches that host path.

- [ ] **Step 2: Document the new vars in .env.example**

In `.env.example`, under the existing `# Backup` section (after `BACKUP_DIR=./backups`), add:

```sh
# Directory (inside the backup sidecar) for Prometheus textfile metrics.
# Lands at ${BACKUP_DIR}/metrics/*.prom on the host; point the jakemon Alloy
# textfile collector at that host path. Leave default unless relocating.
BACKUP_METRICS_DIR=/backups/metrics
```

- [ ] **Step 3: Validate compose still parses**

Run: `docker compose -f docker-compose.yml -f docker-compose.prod.yml config >/dev/null && echo "compose OK"`
Expected: `compose OK` (uses `.env`/`.env.example` defaults; the `${...:-default}` forms keep it valid even with vars unset).

- [ ] **Step 4: Commit**

```bash
git add docker-compose.prod.yml .env.example
git commit -m "backup: surface BACKUP_METRICS_DIR to the prod sidecar + document it

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 7: Operator documentation

**Files:**
- Create: `docs/BackupAndRecovery.md`
- Modify: `docs/WikantikOperations.md`
- Modify: `docs/DockerDeployment.md`

- [ ] **Step 1: Write `docs/BackupAndRecovery.md`**

Create `docs/BackupAndRecovery.md` with these sections (write real prose, not
placeholders):

1. **Overview & 3-2-1 model** — live data (copy 1), docker1 nightly snapshots
   (copy 2), off-box NAS archive (copy 3). State the pull-model trust boundary:
   the NAS reaches into docker1 read-only; docker1 holds no NAS credentials.
2. **What runs where** — the `backup` sidecar (tiers/schedule from
   `docker/backup/crontab`: daily 02:00, weekly Sun 03:00, monthly 1st 04:00;
   retention 30d/12w/12mo on docker1); the NAS pull container (retention
   90d/6mo/1y). Note metrics land at `${BACKUP_DIR}/metrics/*.prom`.
3. **docker1: read-only SSH key for the NAS** — exact steps: generate the
   keypair on the NAS, then on docker1 add a restricted `authorized_keys` line
   for the `backup-reader` user:
   ```
   command="rrsync -ro /home/jakefear/wikantik/backups",no-pty,no-agent-forwarding,no-port-forwarding,no-X11-forwarding ssh-ed25519 AAAA... nas-backup
   ```
   Explain `rrsync` (ships with rsync; restricts the key to read-only rsync of
   that one dir) and how to install it if missing.
4. **NAS setup** — dedicated subvolume/share; the scheduled pull container
   (`alpine` + `rsync openssh-client curl`, runs `nas-pull.sh` on a daily
   schedule a few hours after docker1's 02:00 daily backup, e.g. 05:00);
   `nas-pull.env` from the example; enable UGOS Pro **Btrfs scheduled snapshots**
   on the backup subvolume for an immutable layer the pull cannot overwrite.
5. **Restore procedure** — full DR drill referencing `docker/backup/restore.sh`
   (now full-schema) and the manual sequence from CLAUDE.md's container gotchas
   (bring up `db` alone, restore, then start `wikantik`).
6. **Verifying restorability** — `bin/backup/verify-restore.sh <tier-dir>` against
   the latest pulled snapshot; recommend running it **quarterly** and after any
   PG major-version bump.
7. **Monitoring (jakemon)** — list the emitted signals
   (`wikantik_backup_last_success_timestamp_seconds{tier}`,
   `wikantik_backup_last_exit_status{tier}`, off-box heartbeat via Loki/Pushgateway)
   and the **exact Grafana alert rules to add in the jakemon repo**:
   - `time() - max(wikantik_backup_last_success_timestamp_seconds{tier="daily"}) > 26*3600` → "daily backup missed"
   - off-box: alert if no `wikantik_backup_offsite` Loki line / no
     `wikantik_backup_offsite_last_success_timestamp_seconds` update in 26h
   - `max(wikantik_backup_last_exit_status) != 0` → "backup reported failure"
   State explicitly that the alert rules live in the jakemon repo, not here.

- [ ] **Step 2: Cross-link from WikantikOperations.md**

In `docs/WikantikOperations.md`, add a line in the backup/operations area (or a
new short "## Backup & recovery" subsection) pointing to the new guide:

```markdown
For the full backup topology, off-box NAS archival, and restore drills, see
[BackupAndRecovery.md](BackupAndRecovery.md).
```

- [ ] **Step 3: Cross-link from DockerDeployment.md**

In `docs/DockerDeployment.md`, near the existing backup-sidecar mention, add:

```markdown
> Off-box archival to the DXP4800 Plus NAS and the full restore procedure are
> documented in [BackupAndRecovery.md](BackupAndRecovery.md).
```

- [ ] **Step 4: Commit**

```bash
git add docs/BackupAndRecovery.md docs/WikantikOperations.md docs/DockerDeployment.md
git commit -m "docs: backup & recovery guide (off-box NAS pull, restore drills, jakemon alerts)

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 8: Final verification

- [ ] **Step 1: Run the full backup test suite**

Run: `bash bin/tests/test-backup.sh`
Expected: all `ok:` lines and `ALL BACKUP TESTS PASSED`.

- [ ] **Step 2: Syntax-check every shell artifact**

Run:
```bash
sh -n docker/backup/backup.sh && sh -n docker/backup/restore.sh \
  && bash -n bin/backup/verify-restore.sh && bash -n bin/backup/nas-pull.sh \
  && echo "ALL SYNTAX OK"
```
Expected: `ALL SYNTAX OK`

- [ ] **Step 3: Confirm help on each new/changed script**

Run:
```bash
for s in docker/backup/backup.sh docker/backup/restore.sh \
         bin/backup/verify-restore.sh bin/backup/nas-pull.sh; do
  echo "== $s =="; "$s" --help 2>/dev/null | head -1 || sh "$s" --help | head -1
done
```
Expected: a docstring first line for each.

- [ ] **Step 4: Live drill (optional, needs Docker + a real snapshot)**

If a real backup snapshot exists locally (or after producing one), run:
`bin/backup/verify-restore.sh <path-to-tier-dir>` and expect
`RESTORE VERIFICATION PASSED`.

---

## Task 9: NAS bring-up (MANUAL — after user confirms NAS is back online)

**Not code. Execute interactively once the user says the NAS is ready.** Do NOT
attempt any of this before then.

- [ ] Generate an ed25519 keypair on the NAS (`ssh-keygen -t ed25519 -f /config/nas-backup-key -N ''`).
- [ ] Install the restricted `authorized_keys` line for `backup-reader` on docker1 (Task 7 §3). Verify `rrsync` is present on docker1.
- [ ] Confirm the read-only boundary: from the NAS, `rsync` pull succeeds; `ssh backup-reader@docker1` yields no shell; a write attempt is refused.
- [ ] Create the dedicated NAS backup subvolume/share; enable Btrfs scheduled snapshots on it.
- [ ] Copy `nas-pull.env.example` → `nas-pull.env` on the NAS; fill in real values.
- [ ] First manual run: `nas-pull.sh --env nas-pull.env --dry-run`, then a real run; confirm snapshots land and checksums verify.
- [ ] Schedule the pull container daily at ~05:00.
- [ ] Confirm the off-box heartbeat reaches jakemon (Loki line or Pushgateway gauge) and add the Grafana alert rules in the jakemon repo.
- [ ] Run `verify-restore.sh` against the first pulled snapshot end-to-end.

---

## Self-Review

**Spec coverage:**
- Component 1a (full restore) → Task 3 + functional drill Task 4. ✓
- Component 1b (manifest + LATEST) → Task 1. ✓
- Component 1c (textfile metrics) → Task 2 + wiring Task 6. ✓
- Component 1d (verify-restore drill) → Task 4. ✓
- Component 2a (nas-pull.sh) → Task 5. ✓
- Component 2b (NAS storage/immutability config) → Task 7 docs + Task 9 manual. ✓
- Component 2c (longer NAS retention 90d/6mo/1y) → Task 5 prune + env example. ✓
- Component 3 (monitoring: local metrics, off-box heartbeat, Grafana rules in jakemon) → Tasks 2, 5, 7. ✓
- Component 4 (docs + cross-links) → Task 7. ✓
- Sequencing (NAS deferred) → Task 9 + the plan's sequencing note. ✓

**Placeholder scan:** No TBD/TODO; every code step shows full content. The only
deferred-by-design item (NAS retention via append+prune vs Btrfs) is resolved
here as append+prune (Task 5). ✓

**Naming consistency:** `BACKUP_ROOT`, `PAGES_DIR`, `BACKUP_METRICS_DIR`,
`BACKUP_DIR` (host), `NAS_DEST`, `DOCKER1_*`, `NAS_RETAIN_*_DAYS`, metric names
`wikantik_backup_*`, and the `backup-status.json`/`LATEST` artifacts are used
identically across producing (Tasks 1,2), consuming (Tasks 4,5), and documenting
(Task 7) tasks. ✓
