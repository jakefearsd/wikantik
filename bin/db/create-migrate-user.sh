#!/bin/bash
# Wikantik migration role bootstrap.
#
# Creates the dedicated `migrate` PostgreSQL role used by migrate.sh,
# sets its password, and grants it the privileges it needs to apply
# every current and future migration in this directory:
#   - LOGIN, NOSUPERUSER, NOCREATEDB, NOCREATEROLE
#   - CONNECT on the target database
#   - CREATE on schema public (so it can create new tables/sequences/indexes)
#   - USAGE on schema public
#   - Membership in the application role, so migrations that GRANT to the
#     app role have a grantor with the necessary privileges, and so any
#     tables already owned by the app role can be ALTERed by `migrate`
#   - Default-privileges rules: any future object created by `migrate`
#     automatically gets the right grants for the application role
#
# This script MUST be run by a PostgreSQL superuser. It is fully idempotent:
# rerunning it after a password rotation simply updates the password.
#
# Usage:
#   ./create-migrate-user.sh
#
# Environment variables (with defaults):
#   DB_NAME              wikantik     target database (must already exist)
#   DB_MIGRATE_USER      migrate      role to create
#   DB_MIGRATE_PASSWORD               REQUIRED — password to set on the role
#   DB_APP_USER          jspwiki      app role granted via default privileges
#   PGHOST               localhost
#   PGPORT               5432
#   PGUSER               postgres     superuser running this script
#   PGPASSWORD                        optional (or rely on peer/trust auth)
#
# Authentication: psql honours ~/.pgpass and PGPASSWORD as usual. Run this
# script as whichever OS user has a ~/.pgpass entry for a PostgreSQL
# superuser — no `sudo -u postgres` required if your local .pgpass already
# authenticates the postgres role over TCP.
#
# What this script does NOT do:
#   - install PostgreSQL extensions (pgvector, pgcrypto). Those must already
#     be installed by a superuser before V004 can be applied. Use:
#       psql -U postgres -d "$DB_NAME" -c \
#           'CREATE EXTENSION IF NOT EXISTS vector;'
#   - create the database itself. install-fresh.sh still owns that.
#
# This script DOES transfer ownership of existing user tables and sequences
# in the public schema from their current owner (postgres or jspwiki) to
# the migrate role, so that ALTER TABLE statements in later migrations
# succeed. Without this, an ALTER in a migration will fail with
# "must be owner of table X" when the table was originally created by a
# different role. The transfer is a targeted loop over public-schema
# objects — it does NOT use blanket REASSIGN OWNED, which would affect
# system objects too.
set -euo pipefail

DB_NAME="${DB_NAME:-jspwiki}"
DB_MIGRATE_USER="${DB_MIGRATE_USER:-migrate}"
DB_APP_USER="${DB_APP_USER:-jspwiki}"
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

if [[ -z "${DB_MIGRATE_PASSWORD:-}" ]]; then
    print_err "DB_MIGRATE_PASSWORD is required (no default — refusing to set a known password)."
    exit 2
fi

super_psql() {
    psql --no-psqlrc --quiet --tuples-only --no-align -v ON_ERROR_STOP=1 "$@"
}

if ! super_psql -d postgres -c 'SELECT 1;' >/dev/null 2>&1; then
    print_err "Could not connect as superuser ${PGUSER}@${PGHOST}:${PGPORT}"
    exit 1
fi

db_exists=$(super_psql -d postgres -c \
    "SELECT 1 FROM pg_database WHERE datname = '${DB_NAME}';")
if [[ "${db_exists// /}" != "1" ]]; then
    print_err "Database ${DB_NAME} does not exist — run install-fresh.sh first."
    exit 1
fi

role_exists=$(super_psql -d postgres -c \
    "SELECT 1 FROM pg_roles WHERE rolname = '${DB_MIGRATE_USER}';")

if [[ "${role_exists// /}" == "1" ]]; then
    super_psql -d postgres -c \
        "ALTER ROLE \"${DB_MIGRATE_USER}\" WITH LOGIN ENCRYPTED PASSWORD '${DB_MIGRATE_PASSWORD}' NOSUPERUSER NOCREATEDB NOCREATEROLE;"
    print_ok "Role ${DB_MIGRATE_USER} already existed — password and attributes refreshed"
else
    super_psql -d postgres -c \
        "CREATE ROLE \"${DB_MIGRATE_USER}\" WITH LOGIN ENCRYPTED PASSWORD '${DB_MIGRATE_PASSWORD}' NOSUPERUSER NOCREATEDB NOCREATEROLE;"
    print_ok "Created role ${DB_MIGRATE_USER}"
fi

# Database-level: connect.
super_psql -d "${DB_NAME}" -c \
    "GRANT CONNECT ON DATABASE \"${DB_NAME}\" TO \"${DB_MIGRATE_USER}\";"

# Schema-level: usage + create (for new tables, sequences, indexes).
super_psql -d "${DB_NAME}" -c \
    "GRANT USAGE, CREATE ON SCHEMA public TO \"${DB_MIGRATE_USER}\";"

# Make the migrate role a member of the app role. This gives migrate the
# ability to ALTER pre-existing tables that the app role owns and provides
# a clean grantor for future GRANT statements aimed at the app role.
app_exists=$(super_psql -d postgres -c \
    "SELECT 1 FROM pg_roles WHERE rolname = '${DB_APP_USER}';")
if [[ "${app_exists// /}" == "1" ]]; then
    super_psql -d "${DB_NAME}" -c \
        "GRANT \"${DB_APP_USER}\" TO \"${DB_MIGRATE_USER}\";"
    print_ok "Granted ${DB_APP_USER} membership to ${DB_MIGRATE_USER}"
else
    print_warn "App role ${DB_APP_USER} not found — skipping role-membership grant"
fi

# Default privileges: any new table/sequence created by migrate is
# automatically usable by the app role.
super_psql -d "${DB_NAME}" -c \
    "ALTER DEFAULT PRIVILEGES FOR ROLE \"${DB_MIGRATE_USER}\" IN SCHEMA public
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO \"${DB_APP_USER}\";"
super_psql -d "${DB_NAME}" -c \
    "ALTER DEFAULT PRIVILEGES FOR ROLE \"${DB_MIGRATE_USER}\" IN SCHEMA public
        GRANT USAGE, SELECT ON SEQUENCES TO \"${DB_APP_USER}\";"
print_ok "Set default privileges so future objects are usable by ${DB_APP_USER}"

# Transfer ownership of existing user tables and sequences in the public
# schema so the migrate role can ALTER them in future migrations. Runs as
# the superuser already connected for this script, and only touches the
# public schema — system catalogs and extension-owned objects are left
# alone. Idempotent: already-migrate-owned objects are skipped by the
# ownership filter.
super_psql -d "${DB_NAME}" <<SQL
DO \$\$
DECLARE
    rec RECORD;
BEGIN
    FOR rec IN
        SELECT tablename FROM pg_tables
         WHERE schemaname = 'public'
           AND tableowner <> '${DB_MIGRATE_USER}'
           AND tableowner IN ('postgres', '${DB_APP_USER}')
    LOOP
        EXECUTE 'ALTER TABLE public.' || quote_ident(rec.tablename)
                || ' OWNER TO ${DB_MIGRATE_USER}';
    END LOOP;
    FOR rec IN
        SELECT sequence_name FROM information_schema.sequences
         WHERE sequence_schema = 'public'
    LOOP
        BEGIN
            EXECUTE 'ALTER SEQUENCE public.' || quote_ident(rec.sequence_name)
                    || ' OWNER TO ${DB_MIGRATE_USER}';
        EXCEPTION WHEN insufficient_privilege THEN
            NULL;
        END;
    END LOOP;
END
\$\$;
SQL
print_ok "Transferred public-schema ownership to ${DB_MIGRATE_USER}"

echo
print_ok "Migration role bootstrap complete"
echo "  Database:        ${DB_NAME}"
echo "  Migration user:  ${DB_MIGRATE_USER}"
echo "  App user:        ${DB_APP_USER}"
echo
echo "Next: run migrate.sh with PGUSER=${DB_MIGRATE_USER} and the matching PGPASSWORD."
