#!/usr/bin/env bash
# seed-loadtest-data.sh — idempotently provision a Wikantik target for load testing.
#
# What it does:
#   1. Creates a 'testbot' admin user (if absent) using the password from
#      test.properties, hashed with CryptoUtil's {SHA-256} SSHA scheme.
#   2. Inserts an API key into api_keys with scope 'all' (good for both
#      MCP and tools endpoints) using test.api.key from test.properties.
#      The DB stores only the SHA-256 hex hash of the plaintext token.
#      Both inserts are ON CONFLICT DO NOTHING so re-runs are safe.
#   3. Prints the loadtest.env values to copy/paste.
#
# Usage:
#   loadtest/seed-loadtest-data.sh [options]
#
# Options:
#   -h, --help            Show this help and exit.
#   --db-host HOST        PostgreSQL host (default: $PGHOST or localhost).
#   --db-port PORT        PostgreSQL port (default: $PGPORT or 5432).
#   --db-name NAME        Database name    (default: $POSTGRES_DB or wikantik).
#   --db-user USER        PostgreSQL user  (default: $POSTGRES_USER or wikantik).
#   --db-password PASS    PostgreSQL password (default: $POSTGRES_PASSWORD from .env).
#   --props FILE          Path to test.properties (default: repo-root test.properties).
#   --env-file FILE       Path to .env for DB defaults (default: repo-root .env).
#   --docker-project NAME Run psql via 'docker compose -p NAME exec db psql' instead
#                         of connecting directly (use when the DB port is not published
#                         to the host, e.g. the local dev compose stack).
#
# Environment variables (override flag defaults):
#   PGHOST, PGPORT, POSTGRES_DB, POSTGRES_USER, POSTGRES_PASSWORD
#
# Example (local dev container, DB not published — most common):
#   loadtest/seed-loadtest-data.sh --docker-project jspwiki
#
# Example (direct host/port connection):
#   loadtest/seed-loadtest-data.sh --db-host 10.0.0.5 --db-port 15432
#
# Secrets note: this script READS passwords from test.properties and .env.
# It does NOT embed them. Never run with --db-password on a shared terminal.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

# ── defaults ────────────────────────────────────────────────────────────────
DB_HOST="${PGHOST:-localhost}"
DB_PORT="${PGPORT:-5432}"
DB_NAME="${POSTGRES_DB:-wikantik}"
DB_USER="${POSTGRES_USER:-wikantik}"
DB_PASSWORD="${POSTGRES_PASSWORD:-}"
PROPS_FILE="${REPO_ROOT}/test.properties"
ENV_FILE="${REPO_ROOT}/.env"
DOCKER_PROJECT=""

# ── argument parsing ─────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
    case "$1" in
        -h|--help)
            grep '^#' "${BASH_SOURCE[0]}" | grep -v '^#!/' | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        --db-host)        DB_HOST="$2";        shift 2 ;;
        --db-port)        DB_PORT="$2";        shift 2 ;;
        --db-name)        DB_NAME="$2";        shift 2 ;;
        --db-user)        DB_USER="$2";        shift 2 ;;
        --db-password)    DB_PASSWORD="$2";    shift 2 ;;
        --props)          PROPS_FILE="$2";     shift 2 ;;
        --env-file)       ENV_FILE="$2";       shift 2 ;;
        --docker-project) DOCKER_PROJECT="$2"; shift 2 ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
done

# ── load .env for DB credentials if not already set ──────────────────────────
if [[ -f "${ENV_FILE}" ]]; then
    if [[ -z "${DB_PASSWORD}" ]]; then
        _pw=$(grep '^POSTGRES_PASSWORD=' "${ENV_FILE}" | head -1 | cut -d= -f2- | tr -d "'\"")
        [[ -n "${_pw}" ]] && DB_PASSWORD="${_pw}"
    fi
    if [[ "${DB_USER}" == "wikantik" ]]; then
        _u=$(grep '^POSTGRES_USER=' "${ENV_FILE}" | head -1 | cut -d= -f2- | tr -d "'\"")
        [[ -n "${_u}" ]] && DB_USER="${_u}"
    fi
    if [[ "${DB_NAME}" == "wikantik" ]]; then
        _n=$(grep '^POSTGRES_DB=' "${ENV_FILE}" | head -1 | cut -d= -f2- | tr -d "'\"")
        [[ -n "${_n}" ]] && DB_NAME="${_n}"
    fi
fi

# When using docker-project mode the password is not needed for the psql call
# (it runs inside the container as the DB user). For direct connections it is
# required.
if [[ -z "${DOCKER_PROJECT}" && -z "${DB_PASSWORD}" ]]; then
    echo "ERROR: DB password not set. Supply --db-password, set POSTGRES_PASSWORD, --docker-project, or ensure .env has POSTGRES_PASSWORD." >&2
    exit 1
fi

# ── read test credentials ────────────────────────────────────────────────────
if [[ ! -f "${PROPS_FILE}" ]]; then
    echo "ERROR: test.properties not found at ${PROPS_FILE}" >&2
    echo "Create it with: test.user.login=testbot  test.user.password=<pw>  test.api.key=<key>" >&2
    exit 1
fi

_prop() { grep "^$1=" "${PROPS_FILE}" | head -1 | cut -d= -f2-; }

TESTBOT_LOGIN="$(_prop test.user.login)"
TESTBOT_PASS="$(_prop test.user.password)"
TESTBOT_EMAIL="$(_prop test.user.email)"
TESTBOT_FULLNAME="$(_prop test.user.fullName)"
API_KEY_PLAIN="$(_prop test.api.key)"

: "${TESTBOT_LOGIN:=testbot}"
: "${TESTBOT_PASS:?test.user.password not set in ${PROPS_FILE}}"
: "${TESTBOT_EMAIL:=testbot@localhost}"
: "${TESTBOT_FULLNAME:=Test Automation Bot}"
: "${API_KEY_PLAIN:?test.api.key not set in ${PROPS_FILE}}"

# ── locate wikantik-util jar (for CryptoUtil) ────────────────────────────────
UTIL_JAR=$(find "${REPO_ROOT}/wikantik-util/target" -name "wikantik-util-*.jar" \
             ! -name "*sources*" ! -name "*javadoc*" 2>/dev/null | head -1)
if [[ -z "${UTIL_JAR}" ]]; then
    echo "ERROR: wikantik-util jar not found under wikantik-util/target." >&2
    echo "Build it first: mvn package -pl wikantik-util -am -DskipTests -q" >&2
    exit 1
fi

# ── hash the testbot password via CryptoUtil {SHA-256} ───────────────────────
echo "Hashing testbot password..."
PW_HASH=$(java -cp "${UTIL_JAR}" com.wikantik.util.CryptoUtil --hash "${TESTBOT_PASS}" 2>/dev/null)
if [[ -z "${PW_HASH}" ]]; then
    echo "ERROR: CryptoUtil returned empty hash." >&2
    exit 1
fi

# ── compute SHA-256 of the API key (same algo as ApiKeyService.sha256Hex) ────
# The DB stores hex(sha256(plaintext_token)).  Both MCP and tools use scope
# 'all' so a single row covers both endpoints.
echo "Computing SHA-256 of API key..."
API_KEY_HASH=$(python3 -c "
import hashlib, sys
print(hashlib.sha256('${API_KEY_PLAIN}'.encode('utf-8')).hexdigest())
" 2>/dev/null)
if [[ -z "${API_KEY_HASH}" ]]; then
    # fall back to openssl if python3 unavailable
    API_KEY_HASH=$(printf '%s' "${API_KEY_PLAIN}" | openssl dgst -sha256 | awk '{print $2}')
fi

# ── psql helper ──────────────────────────────────────────────────────────────
# Routes to docker compose exec when --docker-project is set (DB port not
# published), otherwise connects directly via PGPASSWORD.
_psql() {
    if [[ -n "${DOCKER_PROJECT}" ]]; then
        docker compose -p "${DOCKER_PROJECT}" exec -T db \
            psql -U "${DB_USER}" -d "${DB_NAME}" "$@"
    else
        PGPASSWORD="${DB_PASSWORD}" psql -h "${DB_HOST}" -p "${DB_PORT}" \
            -U "${DB_USER}" -d "${DB_NAME}" --no-password "$@"
    fi
}

echo ""
echo "=== Seeding target: ${DB_USER}@${DB_HOST}:${DB_PORT}/${DB_NAME} ==="

# ── 1. Upsert testbot user ────────────────────────────────────────────────────
echo ""
echo "--- Step 1: testbot user ---"
_psql <<SQL
INSERT INTO users (uid, email, full_name, login_name, password, wiki_name, created, modified)
VALUES (
    '${TESTBOT_LOGIN}',
    '${TESTBOT_EMAIL}',
    '${TESTBOT_FULLNAME}',
    '${TESTBOT_LOGIN}',
    '${PW_HASH}',
    'TestBot',
    NOW(),
    NOW()
)
ON CONFLICT (login_name) DO UPDATE
    SET password = EXCLUDED.password,
        modified = NOW();
SQL

_psql <<SQL
INSERT INTO roles (login_name, role)
VALUES ('${TESTBOT_LOGIN}', 'Admin')
ON CONFLICT DO NOTHING;
SQL

TESTBOT_STATUS=$(_psql -t -c "SELECT login_name FROM users WHERE login_name='${TESTBOT_LOGIN}'" | tr -d '[:space:]')
if [[ "${TESTBOT_STATUS}" == "${TESTBOT_LOGIN}" ]]; then
    echo "  [OK] testbot user exists with Admin role."
else
    echo "  [WARN] Could not confirm testbot user after insert." >&2
fi

# ── 2. Upsert MCP/tools API key ───────────────────────────────────────────────
echo ""
echo "--- Step 2: API key (scope=all, principal=${TESTBOT_LOGIN}) ---"
_psql <<SQL
INSERT INTO api_keys (key_hash, principal_login, label, scope, created_at, created_by)
VALUES (
    '${API_KEY_HASH}',
    '${TESTBOT_LOGIN}',
    'loadtest-all-key',
    'all',
    NOW(),
    'seed-loadtest-data.sh'
)
ON CONFLICT (key_hash) DO NOTHING;
SQL

KEY_STATUS=$(_psql -t -c "SELECT id FROM api_keys WHERE key_hash='${API_KEY_HASH}' AND revoked_at IS NULL" | tr -d '[:space:]')
if [[ -n "${KEY_STATUS}" ]]; then
    echo "  [OK] API key seeded (id=${KEY_STATUS}, scope=all, principal=${TESTBOT_LOGIN})."
else
    echo "  [WARN] API key row not found after insert — it may already exist (revoked?)." >&2
fi

# ── 3. Print loadtest.env values ──────────────────────────────────────────────
echo ""
echo "================================================================="
echo "  Seeding complete. Add these values to loadtest/loadtest.env:"
echo "================================================================="
echo ""
echo "  BASE_URL=http://localhost:18080"
echo "  LOADTEST_ADMIN_USER=${TESTBOT_LOGIN}"
echo "  LOADTEST_ADMIN_PASS=${TESTBOT_PASS}"
echo "  LOADTEST_MCP_KEY=${API_KEY_PLAIN}"
echo "  LOADTEST_TOOLS_KEY=${API_KEY_PLAIN}"
echo ""
echo "================================================================="
echo ""
echo "Tip: run a quick smoke-check with:"
echo "  curl -s -o /dev/null -w '%{http_code}' \\"
echo "    -H 'Authorization: Bearer ${API_KEY_PLAIN}' \\"
echo "    http://localhost:18080/wikantik-admin-mcp"
