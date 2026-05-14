#!/usr/bin/env bash
# Smoke tests for bin/remote.sh and the prod compose change.
# Pure dry-run + grep style — no real ssh, no real docker.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

fail() { echo "FAIL: $*" >&2; exit 1; }
ok()   { echo "ok: $*"; }

# --- compose: prod pages must bind-mount a host path ---
test_prod_pages_bind_mount() {
    local out
    out="$(WIKANTIK_PAGES_DIR=/srv/wikantik/pages BACKUP_DIR=/srv/wikantik/backups \
           docker compose -f docker-compose.yml -f docker-compose.prod.yml config 2>/dev/null)" \
        || fail "docker compose config rejected prod overlay"
    echo "${out}" | grep -E '^\s+source: /srv/wikantik/pages' >/dev/null \
        || fail "prod overlay did not expand WIKANTIK_PAGES_DIR into a bind mount"
    echo "${out}" | grep -E 'wikantik-pages' >/dev/null \
        && fail "prod overlay still references the wikantik-pages named volume"
    ok "prod compose binds host pages dir"
}

test_prod_pages_bind_mount
