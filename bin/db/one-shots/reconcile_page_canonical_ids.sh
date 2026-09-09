#!/bin/bash
# One-shot data reconciliation: fix slug/canonical_id mismatches in page_canonical_ids.
#
# Background: PageCanonicalIdsDao.upsert() refuses to write a page_canonical_ids
# row when the target slug is already claimed by a DIFFERENT (stale)
# canonical_id — typically because a page's frontmatter canonical_id changed
# after the DB row was first written. Rather than let that explode into a
# unique-constraint-violation stacktrace, it logs a clean WARN with an exact
# recovery hint (see PageCanonicalIdsDao.warnStaleSlugOwner/insertNew/
# updateExisting), repeated on every restart for as long as the stale row
# sits there:
#
#   ... To fix: run bin/db/one-shots/reconcile_page_canonical_ids.sh (or
#   manually DELETE the stale row from page_canonical_ids WHERE
#   canonical_id='<STALE_ID>' AND current_slug='<SLUG>').
#
# THIS SCRIPT IS DATA-DRIVEN, NOT HARDCODED: it parses that exact recovery
# hint out of a log (any file or stream containing those WARN lines —
# catalina.out, `docker compose logs wikantik`, an ssh'd copy from prod,
# etc.) to build its own work list of (stale canonical_id, slug) pairs. A
# fresh batch of conflicts next month needs no code change here — point it
# at a fresh log and it reconciles whatever it finds. (Deduplicating the
# WARN lines this way is also why the pair count is smaller than the raw
# WARN count: the same stale row logs a WARN on every restart until fixed —
# e.g. 110 raw WARNs from 22 distinct never-fixed pairs across ~5 restarts.)
#
# What this does, per (stale canonical_id, slug) pair found in the log, in
# one transaction per pair:
#   1. Re-check the row STILL exists with exactly that (canonical_id,
#      current_slug) pairing — the idempotency guard, and also the guard
#      against acting on a stale *log*: if that canonical_id has since
#      started legitimately owning a different slug, this DELETE (scoped to
#      the exact pair, never to the slug alone) simply won't match it.
#   2. DELETE page_verification rows that reference the stale canonical_id.
#   3. DELETE page_slug_history rows that reference the stale canonical_id.
#   4. DELETE the stale row in page_canonical_ids.
#
# The next Tomcat startup's structural-index rebuild INSERTs a fresh row
# using the (now-unblocked) frontmatter canonical_id.
#
# Idempotent — once a pair's row is gone, re-running (dry-run or --apply)
# finds and changes nothing for that pair: the guard in step 1 re-checks the
# exact (canonical_id, current_slug) row on every run, and the DELETEs
# themselves match nothing once their target is already gone.
#
# Usage:
#   bin/db/one-shots/reconcile_page_canonical_ids.sh [--log FILE] [--apply]
#
#   (no flags)   Dry-run (default): parse the log, print every
#                (stale canonical_id, slug) pair found and whatever
#                page_canonical_ids row currently matches it, write nothing.
#   --log FILE   Log to parse for the WARN lines above. Defaults to
#                tomcat/tomcat-11/logs/catalina.out (relative to this repo)
#                if present, else stdin — e.g.:
#                  ssh docker1 'docker compose -f ... logs wikantik' | \
#                      bin/db/one-shots/reconcile_page_canonical_ids.sh
#   --apply      Actually perform the DELETEs (default: report only).
#
# Environment variables (defaults match bin/db/migrate.sh convention):
#   PGHOST       localhost
#   PGPORT       5432
#   PGUSER       jspwiki
#   PGPASSWORD   (required)
#   DB_NAME      wikantik
set -euo pipefail

case "${1:-}" in
    -h|--help)
        awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
        exit 0
        ;;
esac

LOG_FILE=""
APPLY=0
while [ $# -gt 0 ]; do
    case "$1" in
        --log)     LOG_FILE="${2:-}"; shift 2 ;;
        --log=*)   LOG_FILE="${1#--log=}"; shift ;;
        --apply)   APPLY=1; shift ;;
        *) echo "Unknown argument: $1 (see --help)" >&2; exit 2 ;;
    esac
done

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

run_psql() {
    psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$DB_NAME" -v ON_ERROR_STOP=1 "$@"
}

# ---------------------------------------------------------------------------
# Locate a log source: --log FILE, else the local Tomcat log if this checkout
# has one deployed (bin/deploy-local.sh convention), else stdin. Preferring
# the local file over stdin (rather than sniffing `[ -t 0 ]`) keeps this
# deterministic under cron/CI/agent harnesses, where stdin is routinely
# non-tty even when nothing was deliberately piped in.
# ---------------------------------------------------------------------------
DEFAULT_LOG="$(dirname "$0")/../../../tomcat/tomcat-11/logs/catalina.out"

if [ -n "${LOG_FILE}" ]; then
    [ -r "${LOG_FILE}" ] || { echo "ERROR: --log file not readable: ${LOG_FILE}" >&2; exit 1; }
elif [ -r "${DEFAULT_LOG}" ]; then
    LOG_FILE="${DEFAULT_LOG}"
else
    LOG_FILE="-"   # read stdin — a deliberate pipe, or nothing (reports "nothing to reconcile")
fi

echo "=== reconcile_page_canonical_ids.sh ==="
echo "Target: ${PGUSER}@${PGHOST}:${PGPORT}/${DB_NAME}"
echo "Log source: ${LOG_FILE}"
echo "Mode: $([ "${APPLY}" -eq 1 ] && echo 'APPLY (transactional, per pair)' || echo 'DRY-RUN (no writes)')"
echo ""

# ---------------------------------------------------------------------------
# Extract every distinct (stale_canonical_id, slug) pair from
# PageCanonicalIdsDao's recovery-hint WARN lines:
#   ... WHERE canonical_id='<ID>' AND current_slug='<SLUG>' ...
# ---------------------------------------------------------------------------
pairs="$(grep -aoE "WHERE canonical_id='[^']+' AND current_slug='[^']+'" "${LOG_FILE}" \
    | sed -E "s/WHERE canonical_id='([^']+)' AND current_slug='([^']+)'/\1|\2/" \
    | sort -u || true)"

if [ -z "${pairs}" ]; then
    echo "No 'stale slug owner' WARN lines found in the log source — nothing to reconcile."
    exit 0
fi

pair_count="$(printf '%s\n' "${pairs}" | grep -c '.')"
echo "Found ${pair_count} distinct (stale canonical_id, slug) pair(s) in the log:"
printf '%s\n' "${pairs}" | while IFS='|' read -r stale_id slug; do
    echo "  canonical_id=${stale_id}  current_slug=${slug}"
done
echo ""

# Defense in depth: canonical_id is a CHAR(26) ULID-shaped column and slugs
# are page names, so both are validated against a conservative charset
# before ever reaching string-interpolated SQL below — a pair that fails
# this is reported and skipped rather than risking a malformed/injectable
# statement.
valid_canonical_id() { [[ "$1" =~ ^[0-9A-Za-z]{26}$ ]]; }
valid_slug()          { [[ "$1" =~ ^[A-Za-z0-9_./-]+$ ]]; }

fix_stale_row() {
    local stale_id="$1"
    local slug="$2"

    echo "--- Processing slug='${slug}' stale_canonical_id='${stale_id}'"

    local result
    result="$(run_psql -t -A <<SQL
DO \$\$
DECLARE
    v_count_pci  INTEGER;
    v_count_pv   INTEGER;
    v_count_psh  INTEGER;
BEGIN
    -- Check whether the stale row still exists (idempotency guard, and the
    -- guard against acting on a stale log — see header comment)
    SELECT COUNT(*) INTO v_count_pci
    FROM page_canonical_ids
    WHERE canonical_id = '${stale_id}' AND current_slug = '${slug}';

    IF v_count_pci = 0 THEN
        RAISE NOTICE 'SKIP: no matching stale row for slug=% id=% (already reconciled, or this id/slug pairing has since changed)', '${slug}', '${stale_id}';
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

if [ "${APPLY}" -eq 0 ]; then
    echo "--- Current state of each pair (dry-run; no writes):"
    printf '%s\n' "${pairs}" | while IFS='|' read -r stale_id slug; do
        if ! valid_canonical_id "${stale_id}" || ! valid_slug "${slug}"; then
            echo "  SKIP (fails validation, will not be touched even with --apply): canonical_id='${stale_id}' current_slug='${slug}'"
            continue
        fi
        run_psql -c "SELECT canonical_id, current_slug, title, type, cluster, updated_at FROM page_canonical_ids WHERE canonical_id = '${stale_id}' AND current_slug = '${slug}';"
    done
    echo ""
    echo "Dry-run only. Re-run with --apply to commit."
    exit 0
fi

printf '%s\n' "${pairs}" | while IFS='|' read -r stale_id slug; do
    if ! valid_canonical_id "${stale_id}" || ! valid_slug "${slug}"; then
        echo "--- SKIP (fails validation): canonical_id='${stale_id}' current_slug='${slug}'"
        continue
    fi
    fix_stale_row "${stale_id}" "${slug}"
done

echo ""
echo "=== Reconciliation complete. Run again (dry-run) to confirm idempotency. ==="
echo "    Restart Tomcat so the structural-index rebuild inserts fresh rows."
