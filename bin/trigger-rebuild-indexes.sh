#!/bin/bash
#
# trigger-rebuild-indexes.sh — kick off the local wiki's async rebuild of
# Lucene + kg_content_chunks. Prereq for bin/run-embedding-experiment.sh.
#
# Two-step: POST /api/auth/login obtains a JSESSIONID cookie, then
# POST /admin/content/rebuild-indexes uses that cookie. HTTP Basic auth is
# not wired at the container level, so cookie-based session auth is the
# only path.
#
# Usage:
#   bin/trigger-rebuild-indexes.sh                 # kick off rebuild
#   bin/trigger-rebuild-indexes.sh status          # poll progress
#   bin/trigger-rebuild-indexes.sh --help          # show this help
#
# Configuration:
#   WIKI_URL      base URL (default http://localhost:8080)
#   credentials   read from test.properties at the repo root
#                 (test.user.login + test.user.password). See CLAUDE.md
#                 > Manual Testing Credentials.

set -euo pipefail

case "${1:-}" in
    -h|--help)
        awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
        exit 0
        ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEST_PROPS="${REPO_ROOT}/test.properties"

if [[ ! -f "${TEST_PROPS}" ]]; then
    echo "Missing ${TEST_PROPS} — see CLAUDE.md > Manual Testing Credentials" >&2
    exit 2
fi

WIKI_URL="${WIKI_URL:-http://localhost:8080}"
WIKI_USER=$(grep '^test.user.login=' "${TEST_PROPS}" | cut -d= -f2)
WIKI_PASSWORD=$(grep '^test.user.password=' "${TEST_PROPS}" | cut -d= -f2)

if [[ -z "${WIKI_USER}" || -z "${WIKI_PASSWORD}" ]]; then
    echo "test.properties missing test.user.login / test.user.password" >&2
    exit 2
fi

COOKIE_JAR="$(mktemp)"
trap 'rm -f "${COOKIE_JAR}" /tmp/login.json' EXIT

# `status` subcommand polls index-status. Login still required because the
# admin endpoint is auth-gated.
if [[ "${1:-}" == "status" ]]; then
    LOGIN_CODE=$(curl -sS -c "${COOKIE_JAR}" \
        -H "Content-Type: application/json" \
        -d "{\"username\":\"${WIKI_USER}\",\"password\":\"${WIKI_PASSWORD}\"}" \
        -o /tmp/login.json -w "%{http_code}" \
        "${WIKI_URL}/api/auth/login")
    if [[ "${LOGIN_CODE}" != "200" ]]; then
        echo "Login failed (HTTP ${LOGIN_CODE}):" >&2
        cat /tmp/login.json >&2; echo >&2
        exit 1
    fi
    curl -sS -b "${COOKIE_JAR}" "${WIKI_URL}/admin/content/index-status"
    echo
    exit 0
fi

echo "POST ${WIKI_URL}/api/auth/login"
LOGIN_CODE=$(curl -sS -c "${COOKIE_JAR}" \
    -H "Content-Type: application/json" \
    -d "{\"username\":\"${WIKI_USER}\",\"password\":\"${WIKI_PASSWORD}\"}" \
    -o /tmp/login.json -w "%{http_code}" \
    "${WIKI_URL}/api/auth/login")
if [[ "${LOGIN_CODE}" != "200" ]]; then
    echo "Login failed (HTTP ${LOGIN_CODE}):" >&2
    cat /tmp/login.json >&2; echo >&2
    exit 1
fi
echo "  login OK"

echo "POST ${WIKI_URL}/admin/content/rebuild-indexes"
curl -sS -b "${COOKIE_JAR}" \
     -X POST "${WIKI_URL}/admin/content/rebuild-indexes" \
     -w "\nHTTP %{http_code}\n"

echo
echo "Rebuild is async. Poll status with:"
echo "  bin/trigger-rebuild-indexes.sh status"
