#!/usr/bin/env bash
#
# smoke-wiki.sh — functional smoke test for a running Wikantik instance.
#
# Beyond a bare health check, this proves the instance actually serves restored
# content: health is UP, a page renders, the changes feed is populated, and
# search returns a hit. Intended for use after a deploy or a DR restore
# (bin/dr-restore.sh calls it), but runs standalone against any base URL.
#
# Usage:
#   smoke-wiki.sh [BASE_URL]          # default http://localhost:8080
#   smoke-wiki.sh --help
#
# Exit status: 0 if every check passes; non-zero on the first failure.
set -euo pipefail

case "${1:-}" in
    -h|--help)
        awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
        exit 0
        ;;
esac

BASE="${1:-http://localhost:8080}"
BASE="${BASE%/}"
fail() { echo "SMOKE FAIL: $*" >&2; exit 1; }
# Capture into a var, then grep — piping curl into `grep -q` trips pipefail when
# grep closes the pipe early (curl gets EPIPE / exit 23) on large responses.
check() {
    local what="$1" url="$2" pat="$3" body
    body="$(curl -fsS --max-time 15 "${url}")" || fail "${what}: request to ${url} failed"
    grep -qE "${pat}" <<<"${body}" || fail "${what}: pattern not found (${url})"
    echo "  ${what}: OK"
}

echo "Smoke-testing ${BASE}"

# 1. Health reports UP.  2. A page renders.  3. Change feed populated.
# 4. Search returns a hit (Lucene index built over restored pages).
check "health"       "${BASE}/api/health"                  '"status"[[:space:]]*:[[:space:]]*"UP"'
check "page render"  "${BASE}/wiki/Main?format=md"         '#'
check "changes feed" "${BASE}/api/changes?since=2000-01-01" '"slug"'
check "search"       "${BASE}/api/search?q=wiki"           '"name"'

echo "SMOKE OK: ${BASE}"
