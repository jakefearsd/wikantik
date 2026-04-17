#!/bin/bash
# Wikantik fresh-install database bootstrap.
#
# Creates a brand-new Wikantik database and application user, then runs
# migrate.sh to populate the schema. Idempotent: safe to run against an
# already-bootstrapped database (create-database and create-user steps
# skip if their target already exists).
#
# Usage:
#   ./install-fresh.sh
#
# Environment variables (with defaults):
#   DB_NAME              wikantik        target database
#   DB_APP_USER          jspwiki         application role (created if absent)
#   DB_APP_PASSWORD      ChangeMe123!    password set on the application role
#   DB_MIGRATE_USER      migrate         migration role (created if absent)
#   DB_MIGRATE_PASSWORD                  password for the migration role.
#                                        Required when bootstrapping the
#                                        migrate role here; if unset, this
#                                        script skips create-migrate-user
#                                        and runs migrate.sh as PGUSER,
#                                        leaving tables owned by PGUSER —
#                                        the operator must run
#                                        create-migrate-user.sh afterwards.
#   PGHOST               localhost
#   PGPORT               5432
#   PGUSER               postgres        superuser that runs the bootstrap
#   PGPASSWORD                           optional (or rely on peer/trust auth)
#
# This script MUST be run by a PostgreSQL superuser because it creates
# the database, creates the application role, and installs the pgvector
# extension.
set -euo pipefail

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

DB_NAME="${DB_NAME:-wikantik}"
DB_APP_USER="${DB_APP_USER:-jspwiki}"
DB_APP_PASSWORD="${DB_APP_PASSWORD:-ChangeMe123!}"
export PGHOST="${PGHOST:-localhost}"
export PGPORT="${PGPORT:-5432}"
export PGUSER="${PGUSER:-postgres}"

RED="\033[0;31m"
GREEN="\033[0;32m"
YELLOW="\033[1;33m"
NC="\033[0m"

print_ok()   { echo -e "${GREEN}✓${NC} $*"; }
print_warn() { echo -e "${YELLOW}!${NC} $*"; }
print_err()  { echo -e "${RED}✗${NC} $*" >&2; }

super_psql() {
    psql --no-psqlrc --quiet --tuples-only --no-align -v ON_ERROR_STOP=1 "$@"
}

# 1. Create the database if it does not already exist.
db_exists=$(super_psql -d postgres -c \
    "SELECT 1 FROM pg_database WHERE datname = '${DB_NAME}';")
if [[ "${db_exists// /}" == "1" ]]; then
    print_ok "Database ${DB_NAME} already exists"
else
    super_psql -d postgres -c "CREATE DATABASE \"${DB_NAME}\";"
    print_ok "Created database ${DB_NAME}"
fi

# 2. Create the application role if it does not already exist.
role_exists=$(super_psql -d postgres -c \
    "SELECT 1 FROM pg_roles WHERE rolname = '${DB_APP_USER}';")
if [[ "${role_exists// /}" == "1" ]]; then
    print_ok "Role ${DB_APP_USER} already exists"
else
    super_psql -d postgres -c \
        "CREATE USER \"${DB_APP_USER}\" WITH ENCRYPTED PASSWORD '${DB_APP_PASSWORD}' NOCREATEDB NOCREATEROLE;"
    print_ok "Created role ${DB_APP_USER}"
    print_warn "Set DB_APP_PASSWORD env var before running in production — a default was used."
fi

# 3. Grant schema-level CONNECT and USAGE so per-table grants (applied by
#    migrations) are effective.
super_psql -d "${DB_NAME}" -c "GRANT CONNECT ON DATABASE \"${DB_NAME}\" TO \"${DB_APP_USER}\";"
super_psql -d "${DB_NAME}" -c "GRANT USAGE   ON SCHEMA public           TO \"${DB_APP_USER}\";"
print_ok "Granted CONNECT and USAGE on public to ${DB_APP_USER}"

# 4. Run migrations. migrate.sh will install pgvector inside V004 and
#    create every table. Runs as PGUSER (typically postgres) so the
#    superuser can create extensions — tables land owned by PGUSER.
DB_NAME="${DB_NAME}" DB_APP_USER="${DB_APP_USER}" PGUSER="${PGUSER}" \
    "${SCRIPT_DIR}/migrate.sh"

# 5. If a migrate password was provided, create the migrate role and
#    transfer ownership of public-schema objects to it. This makes
#    subsequent `migrate.sh` runs (as the migrate role) able to ALTER
#    the tables we just created. Without this, later migrations that
#    ALTER existing tables will fail with "must be owner of table X".
if [[ -n "${DB_MIGRATE_PASSWORD:-}" ]]; then
    echo
    echo "Bootstrapping migrate role and transferring ownership…"
    DB_NAME="${DB_NAME}" DB_APP_USER="${DB_APP_USER}" \
        "${SCRIPT_DIR}/create-migrate-user.sh"
else
    print_warn "DB_MIGRATE_PASSWORD not set — skipping create-migrate-user."
    print_warn "Run create-migrate-user.sh manually, or future ALTER-based migrations will fail."
fi

echo
print_ok "Fresh install complete"
echo "  Database:       ${DB_NAME}"
echo "  Application DB user: ${DB_APP_USER}"
echo "  Put the same credentials in tomcat/.../Catalina/localhost/ROOT.xml"
