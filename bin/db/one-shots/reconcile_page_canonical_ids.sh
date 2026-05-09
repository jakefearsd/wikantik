#!/bin/bash
# One-shot data reconciliation: fix slug/canonical_id mismatch in page_canonical_ids.
#
# Two pages had their canonical_id change in frontmatter (the source of truth) but
# the old canonical_id persisted in the DB.  This causes a unique-constraint violation
# on current_slug during every structural-index rebuild at Tomcat startup.
#
# What this script does (per stale row, in a single transaction):
#   1. DELETE page_verification rows that reference the stale canonical_id.
#   2. DELETE page_slug_history rows that reference the stale canonical_id.
#   3. DELETE the stale row in page_canonical_ids.
#
# The next Tomcat startup will INSERT fresh rows using the frontmatter canonical_ids.
#
# Idempotent — if the stale rows are already gone, the DELETEs match nothing.
#
# Usage:
#   bash bin/db/one-shots/reconcile_page_canonical_ids.sh
#
# Environment variables (defaults match bin/db/migrate.sh convention):
#   PGHOST       localhost
#   PGPORT       5432
#   PGUSER       jspwiki
#   PGPASSWORD   (required)
#   DB_NAME      jspwiki
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
DB_NAME="${DB_NAME:-jspwiki}"

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

run_psql() {
    psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$DB_NAME" -v ON_ERROR_STOP=1 "$@"
}

echo "=== reconcile_page_canonical_ids.sh ==="
echo "Target: ${PGUSER}@${PGHOST}:${PGPORT}/${DB_NAME}"
echo ""

# ---------------------------------------------------------------------------
# Stale rows to fix.  Format: slug | stale_db_canonical_id
# (frontmatter canonical_ids are listed for documentation only — the correct
# IDs will be written by the next structural-index rebuild).
# ---------------------------------------------------------------------------
#
#  Slug                              | Stale DB canonical_id           | Frontmatter canonical_id (target)
#  CloudPlatformsHub                 | 01KZHC6PVU4SBQM9R0F3T7K8Z7      | 01KQEKBKX2QVEW6716V6Y8N5QT
#  NPCompleteAndNPHardComputability  | 01KQ0P44SXS1M8RCKWS201C8J7      | 01KQ96DZZ4YMZ0T1Y6Z5JMD0J0

fix_stale_row() {
    local slug="$1"
    local stale_id="$2"

    echo "--- Processing slug='${slug}' stale_canonical_id='${stale_id}'"

    local result
    result="$(run_psql -t -A <<SQL
DO \$\$
DECLARE
    v_count_pci  INTEGER;
    v_count_pv   INTEGER;
    v_count_psh  INTEGER;
BEGIN
    -- Check whether the stale row still exists (idempotency guard)
    SELECT COUNT(*) INTO v_count_pci
    FROM page_canonical_ids
    WHERE canonical_id = '${stale_id}' AND current_slug = '${slug}';

    IF v_count_pci = 0 THEN
        RAISE NOTICE 'SKIP: no stale row found for slug=% id=% (already reconciled)', '${slug}', '${stale_id}';
    ELSE
        -- Delete dependent page_verification rows
        DELETE FROM page_verification WHERE canonical_id = '${stale_id}';
        GET DIAGNOSTICS v_count_pv = ROW_COUNT;

        -- Delete dependent page_slug_history rows
        DELETE FROM page_slug_history WHERE canonical_id = '${stale_id}';
        GET DIAGNOSTICS v_count_psh = ROW_COUNT;

        -- Delete the stale canonical_ids row
        DELETE FROM page_canonical_ids WHERE canonical_id = '${stale_id}' AND current_slug = '${slug}';
        GET DIAGNOSTICS v_count_pci = ROW_COUNT;

        RAISE NOTICE 'DONE: deleted % page_canonical_ids, % page_verification, % page_slug_history rows for slug=% id=%',
            v_count_pci, v_count_pv, v_count_psh, '${slug}', '${stale_id}';
    END IF;
END;
\$\$;
SQL
)"
    echo "$result"
}

fix_stale_row "CloudPlatformsHub"                "01KZHC6PVU4SBQM9R0F3T7K8Z7"
fix_stale_row "NPCompleteAndNPHardComputability" "01KQ0P44SXS1M8RCKWS201C8J7"

echo ""
echo "=== Reconciliation complete. Run twice to confirm idempotency. ==="
echo "    Restart Tomcat so the structural-index rebuild inserts fresh rows."
