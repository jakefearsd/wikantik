#!/bin/bash
# One-shot data repair: null out users.attributes where it is the empty string.
#
# Background (P5): JDBCUserDatabase.mapProfileRow guarded attributes only against
# NULL. An empty string ('') passes that guard, reaches
# Serializer.deserializeFromBase64(""), and Base64-decodes to a zero-length byte
# array that blows up ObjectInputStream with an EOFException reading the stream
# header. Login still succeeds (the code catches the IOException and continues
# with an empty attribute map) but AbstractUserDatabase logs an ERROR on every
# single authenticated request from an account whose row has this shape (e.g. a
# Basic-Auth service account that never had attributes written) — pure log noise.
#
# The code fix (mapProfileRow now treats a blank value the same as NULL — no
# parse attempt, no log) makes new rows written this way harmless. This script
# is the one-time cleanup for rows that already exist with attributes = ''.
# NULL and '' are equivalent for this column (both mean "no attributes"), so
# rewriting '' to NULL is purely cosmetic/preventive — it also means a rollback
# of the code fix wouldn't immediately reopen the noise for these specific rows.
#
# What this script does:
#   1. SELECT the login_names of affected rows (attributes = ''). Report them.
#   2. In --apply mode, UPDATE those rows to set attributes = NULL, in one
#      transaction.
#
# Idempotent — once attributes is NULL it no longer matches attributes = '', so
# a re-run (dry-run or --apply) finds and changes nothing.
#
# Usage:
#   bash bin/db/one-shots/null_out_empty_user_attributes.sh           # dry-run
#   bash bin/db/one-shots/null_out_empty_user_attributes.sh --apply   # commit
#
# Environment variables (defaults match bin/db/migrate.sh convention):
#   PGHOST       localhost
#   PGPORT       5432
#   PGUSER       jspwiki
#   PGPASSWORD   (required — or read from tomcat ROOT.xml, see below)
#   DB_NAME      wikantik
set -euo pipefail

case "${1:-}" in
    -h|--help)
        awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
        exit 0
        ;;
esac

PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-jspwiki}"
DB_NAME="${DB_NAME:-wikantik}"

if [ -z "${PGPASSWORD:-}" ]; then
    PGPASSWORD="$(grep -oE 'password="[^"]+"' \
        "$(dirname "$0")/../../../tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml" \
        2>/dev/null | head -1 | sed 's/password="//;s/"$//')" || true
fi

if [ -z "${PGPASSWORD:-}" ]; then
    echo "ERROR: PGPASSWORD is not set and could not be read from ROOT.xml." >&2
    exit 1
fi

export PGPASSWORD PGHOST PGPORT PGUSER

APPLY=0
if [ "${1:-}" == "--apply" ]; then
    APPLY=1
fi

run_psql() {
    psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$DB_NAME" -v ON_ERROR_STOP=1 "$@"
}

echo "=== null_out_empty_user_attributes.sh ==="
echo "Target: ${PGUSER}@${PGHOST}:${PGPORT}/${DB_NAME}"
echo "Mode: $([ $APPLY -eq 1 ] && echo 'APPLY (transactional)' || echo 'DRY-RUN (no writes)')"
echo ""

echo "--- Rows with attributes = '' (empty string):"
run_psql -c "SELECT login_name, uid FROM users WHERE attributes = '' ORDER BY login_name;"

if [ $APPLY -eq 0 ]; then
    echo ""
    echo "Dry-run only. Re-run with --apply to commit."
    exit 0
fi

result="$(run_psql -t -A <<'SQL'
DO $$
DECLARE
    v_count INTEGER;
BEGIN
    UPDATE users SET attributes = NULL WHERE attributes = '';
    GET DIAGNOSTICS v_count = ROW_COUNT;
    RAISE NOTICE 'DONE: nulled out attributes on % row(s) where attributes was the empty string', v_count;
END;
$$;
SQL
)"
echo "$result"

echo ""
echo "--- Post-check: rows still matching attributes = '' (should be none):"
run_psql -c "SELECT login_name, uid FROM users WHERE attributes = '';"

echo ""
echo "=== Done. Run again to confirm idempotency (dry-run should list zero rows). ==="
