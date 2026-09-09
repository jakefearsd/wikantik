#!/usr/bin/env bash
#
# smoke-wiki.sh — functional smoke test for a running Wikantik instance.
#
# Beyond a bare health check, this proves the instance actually serves restored
# content: health is UP, a page renders, the changes feed is populated, and
# search returns a hit. Intended for use after a deploy or a DR restore
# (bin/dr-restore.sh calls it), but runs standalone against any base URL.
#
# Public base URLs: /api/health is deliberately blocked from external IPs by
# InternalNetworkFilter (loopback/RFC1918 only), so a non-local BASE_URL gets a
# 403 there by design — that is "correctly firewalled", not a smoke failure.
# For a public BASE_URL, a 403 on health is reported and the run continues into
# the checks that are actually meaningful publicly (page render, changes feed,
# search). Any OTHER health failure (connection error, timeout, non-200/403,
# a 200 that isn't UP) still fails the run, public or not — and for a
# local/internal BASE_URL, health must report UP or the run fails, full stop.
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

# Host portion of BASE, stripped of scheme/credentials/port/path — used only
# to decide whether a 403 on /api/health is expected firewalling.
_host_of() {
    local url="$1"
    url="${url#*://}"
    url="${url#*@}"     # drop any user:pass@ prefix
    url="${url%%/*}"    # drop path
    if [[ "${url}" == \[*\]* ]]; then
        url="${url%%]*}]"     # bracketed IPv6, e.g. [::1]:8080 -> [::1]
    else
        url="${url%%:*}"      # drop :port
    fi
    echo "${url}"
}

# Loopback / RFC 1918 private ranges + common internal TLDs — mirrors the
# allowlist InternalNetworkFilter enforces for /api/health server-side, so
# "local" here means the same thing the filter means by it.
is_local_host() {
    case "$1" in
        localhost|127.*|::1|\[::1\])             return 0 ;;
        10.*|192.168.*)                          return 0 ;;
        172.1[6-9].*|172.2[0-9].*|172.3[01].*)   return 0 ;;
        *.localhost|*.internal|*.lan)            return 0 ;;
        *)                                        return 1 ;;
    esac
}

# Capture into a var, then grep — piping curl into `grep -q` trips pipefail when
# grep closes the pipe early (curl gets EPIPE / exit 23) on large responses.
check() {
    local what="$1" url="$2" pat="$3" body
    body="$(curl -fsS --max-time 15 "${url}")" || fail "${what}: request to ${url} failed"
    grep -qE "${pat}" <<<"${body}" || fail "${what}: pattern not found (${url})"
    echo "  ${what}: OK"
}

# Health gets its own check: unlike the others, a 403 is an EXPECTED response
# when BASE is a public host (InternalNetworkFilter working as designed), so
# it can't just fail() like check() does on any non-2xx/curl error.
check_health() {
    local url="${BASE}/api/health"
    local out http_code body
    out="$(curl -sS --max-time 15 -w $'\n%{http_code}' "${url}")" || fail "health: request to ${url} failed"
    http_code="${out##*$'\n'}"
    body="${out%$'\n'*}"

    if [[ "${http_code}" == "200" ]] && grep -qE '"status"[[:space:]]*:[[:space:]]*"UP"' <<<"${body}"; then
        echo "  health: OK"
        return
    fi

    if [[ "${http_code}" == "403" ]] && ! is_local_host "$(_host_of "${BASE}")"; then
        echo "  health: SKIPPED (HTTP 403 from a public host — InternalNetworkFilter correctly blocks /api/health from external IPs; not a smoke failure)"
        return
    fi

    fail "health: ${url} did not report UP (HTTP ${http_code})"
}

echo "Smoke-testing ${BASE}"

# 1. Health reports UP (or is correctly firewalled on a public host).
# 2. A page renders.  3. Change feed populated.
# 4. Search returns a hit (Lucene index built over restored pages).
check_health
check "page render"  "${BASE}/wiki/Main?format=md"         '#'
check "changes feed" "${BASE}/api/changes?since=2000-01-01" '"slug"'
check "search"       "${BASE}/api/search?q=wiki"           '"name"'

echo "SMOKE OK: ${BASE}"
