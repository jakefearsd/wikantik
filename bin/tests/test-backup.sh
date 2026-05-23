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
    local log="${sb}/backup.log"
    run_backup "${sb}" daily >"${log}" 2>&1 || fail "backup.sh exited non-zero: $(cat "${log}")"
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

# Full (non-dry-run) pull with rsync stubbed to a no-op and a valid fixture
# snapshot already in place — asserts the textfile heartbeat is written.
test_nas_pull_textfile_heartbeat() {
    local tmp; tmp="$(mktemp -d)"; TMPS+=("${tmp}")
    mkdir -p "${tmp}/bin" "${tmp}/dest/daily/2026-05-23" "${tmp}/textfile"
    ( cd "${tmp}/dest/daily/2026-05-23" \
        && echo db > db.sql && echo pg > pages.tar.gz \
        && sha256sum db.sql pages.tar.gz > checksums.sha256 )
    printf '2026-05-23\n' > "${tmp}/dest/daily/LATEST"
    cat > "${tmp}/bin/rsync" <<'STUB'
#!/bin/sh
exit 0
STUB
    chmod +x "${tmp}/bin/rsync"
    cat > "${tmp}/nas-pull.env" <<EOF
DOCKER1_HOST=docker1.invalid
DOCKER1_USER=backup-reader
DOCKER1_BACKUP_DIR=/srv/backups
SSH_KEY=
NAS_DEST=${tmp}/dest
NAS_RETAIN_DAILY_DAYS=90
NAS_RETAIN_WEEKLY_DAYS=183
NAS_RETAIN_MONTHLY_DAYS=365
TEXTFILE_DIR=${tmp}/textfile
LOKI_URL=
PUSHGATEWAY_URL=
EOF
    local out
    out="$(PATH="${tmp}/bin:${PATH}" bin/backup/nas-pull.sh --env "${tmp}/nas-pull.env" 2>&1)" \
        || fail "nas-pull (real run) exited non-zero: ${out}"
    local prom="${tmp}/textfile/wikantik_backup_offsite.prom"
    [[ -f "${prom}" ]] || fail "offsite heartbeat .prom not written"
    grep -qE "wikantik_backup_offsite_last_success_timestamp_seconds [1-9][0-9]+" "${prom}" \
        || fail "last_success timestamp not set on success"
    grep -q "wikantik_backup_offsite_last_exit_status 0" "${prom}" \
        || fail "exit_status not 0 on success"
    [[ -z "$(find "${tmp}/textfile" -name '*.tmp' 2>/dev/null)" ]] \
        || fail "heartbeat temp file left behind (write not atomic)"
    ok "nas-pull textfile heartbeat written on success"
}

test_nas_pull_textfile_heartbeat

echo "ALL BACKUP TESTS PASSED"
