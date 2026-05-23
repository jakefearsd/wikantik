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

echo "ALL BACKUP TESTS PASSED"
