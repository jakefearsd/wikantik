#!/bin/bash
# One-shot data repair: make every display name (users.full_name) unique and presentable.
#
# Background: display names are now what other users see instead of login names, so they must be
# unique (ignoring case and surrounding whitespace), non-blank, and free of '@'. Accounts created
# before that rule can share a name ("Jake Fear" on several accounts) or carry an email address.
#
# What it does (the logic is in dedupe_display_names.sql, next to this script):
#   * In each group of accounts whose display names match, the earliest-created account keeps its
#     name and the others become "<name> 2", "<name> 3", ... (the lowest numbers not already taken).
#   * A blank or '@'-containing display name becomes "Member N" (the lowest free N).
#   * Prints each change as old -> new DISPLAY names only: never a login name or an email address
#     (a blank or email-shaped old name is shown as "(blank)" / "(email-shaped)").
#   * Groups names the way the application does (case, surrounding and repeated whitespace and
#     no-break/fullwidth spacing all ignored), so "jake   FEAR" and "Jake Fear" are the same name.
#   * Gives every account with a NULL or blank users.uid a new random uid (the uid keys @-mentions; a
#     missing one is otherwise invented afresh on every read). A stored uid is never replaced; one that
#     cannot appear in a mention token (outside [A-Za-z0-9-]{1,64}) is only reported.
#   * Leaves users.wiki_name alone.
#
# Dry run by default: the changes are made inside a transaction that is ROLLED BACK, so the report
# is exactly what --apply will do (including any constraint error). --apply commits.
# Idempotent: once the data conforms, a run reports zero changes (report 2 lists stored uids that were
# reported but deliberately not changed, so it can stay non-empty).
#
# Usage:
#   bash bin/db/one-shots/dedupe_display_names.sh           # dry-run
#   bash bin/db/one-shots/dedupe_display_names.sh --apply   # commit
#
# Environment variables (defaults match bin/db/migrate.sh and the other one-shots):
#   PGHOST        localhost
#   PGPORT        5432
#   PGUSER        jspwiki
#   PGPASSWORD    (required - or read from tomcat ROOT.xml, as the other one-shots do)
#   DB_NAME       wikantik
#   PSQL_COMMAND  optional. When set, psql is run as "$PSQL_COMMAND <psql args>" and the PG* variables
#                 are ignored. For the production container run it through the remote wrapper:
#                   PSQL_COMMAND='bin/remote.sh psql --' bin/db/one-shots/dedupe_display_names.sh
set -euo pipefail

APPLY=0
for arg in "$@"; do
    case "$arg" in
        -h|--help)
            awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
            exit 0
            ;;
        --apply) APPLY=1 ;;
        *)
            echo "ERROR: unknown argument: $arg (use --apply or --help)" >&2
            exit 2
            ;;
    esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQL_FILE="${SCRIPT_DIR}/dedupe_display_names.sql"
if [ ! -f "$SQL_FILE" ]; then
    echo "ERROR: ${SQL_FILE} is missing." >&2
    exit 1
fi

# The SQL as one line: full-line comments dropped, newlines folded to spaces, so it can be passed as a
# single psql -c argument (which also survives bin/remote.sh's ssh quoting). A "--" left after the
# comment lines are removed would comment out the rest of the statement, so refuse rather than guess.
SQL_ONE_LINE="$(sed -e '/^[[:space:]]*--/d' "$SQL_FILE" | tr '\n' ' ')"
if [[ "$SQL_ONE_LINE" == *--* ]]; then
    echo "ERROR: ${SQL_FILE} has a trailing '--' comment; keep comments on their own lines." >&2
    exit 1
fi

PSQL_COMMAND="${PSQL_COMMAND:-}"
if [ -n "$PSQL_COMMAND" ]; then
    TARGET="via: ${PSQL_COMMAND}"
    run_psql() {
        # Word-splitting of PSQL_COMMAND is intended (it is a command plus its leading arguments).
        # shellcheck disable=SC2086
        $PSQL_COMMAND -X -q -v ON_ERROR_STOP=1 "$@"
    }
else
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
    TARGET="${PGUSER}@${PGHOST}:${PGPORT}/${DB_NAME}"
    run_psql() {
        psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$DB_NAME" -X -q -v ON_ERROR_STOP=1 "$@"
    }
fi

if [ "$APPLY" -eq 1 ]; then
    END_TRANSACTION="COMMIT"
    MODE="APPLY (committed)"
else
    END_TRANSACTION="ROLLBACK"
    MODE="DRY-RUN (rolled back, no writes)"
fi

echo "=== dedupe_display_names.sh ==="
echo "Target: ${TARGET}"
echo "Mode: ${MODE}"
echo ""
echo "--- Report 1: changes (reason | old display name | new display name)."
echo "    A uid backfill appears as one row giving a count, never an account."
echo "--- Report 2: stored uids that cannot appear in a mention token (display name | problem)."
echo "    Reported only: the uid itself is not changed and not printed."
echo ""

# One psql session so the transaction, the SET LOCAL and the temp report table all share a connection.
# ON_ERROR_STOP ends psql before END_TRANSACTION on any error, and the server then rolls back.
run_psql \
    -c "BEGIN" \
    -c "SET LOCAL wikantik.dedupe_apply = 'on'" \
    -c "$SQL_ONE_LINE" \
    -c "SELECT reason, old_display AS \"old display name\", new_display AS \"new display name\" FROM dedupe_display_names_report ORDER BY seq" \
    -c "SELECT display_name AS \"display name\", problem FROM dedupe_display_names_uid_report ORDER BY seq" \
    -c "$END_TRANSACTION"

echo ""
if [ "$APPLY" -eq 1 ]; then
    echo "=== Done. Run again without --apply to confirm idempotency (report 1 should be empty). ==="
else
    echo "=== Dry-run only. Re-run with --apply to commit. ==="
fi
