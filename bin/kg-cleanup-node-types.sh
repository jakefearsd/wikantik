#!/usr/bin/env bash
# kg-cleanup-node-types.sh — interactive one-shot to clean legacy
# node_type pollution from kg_nodes.
#
# WHY ONE-SHOT, NOT A MIGRATION:
#   Per docs/superpowers/specs/2026-05-14-kg-curation-operability-design.md
#   and the project memory feedback_no_data_backfill_in_migrations, data
#   fixups never land in bin/db/migrations/. They live here so the operator
#   runs them once per environment.
#
# Run AFTER the service-tier vocabulary gate ships
# (^[a-z][a-z0-9_-]{0,30}$). New writes are rejected at the boundary; this
# script normalizes the legacy data that predates the gate.
#
# USAGE:
#   bin/kg-cleanup-node-types.sh                 # uses ROOT.xml credentials
#   PGHOST=db.example.com PGUSER=postgres \
#     PGPASSWORD=... PGDATABASE=jspwiki bin/kg-cleanup-node-types.sh

set -euo pipefail

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    sed -n '2,18p' "$0" | sed 's/^# \?//'
    exit 0
fi

# Resolve DB credentials. Prefer env, fall back to local Tomcat ROOT.xml.
ROOTXML="tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml"
if [[ -z "${PGHOST:-}" ]]; then PGHOST="localhost"; fi
if [[ -z "${PGUSER:-}" ]]; then
    PGUSER=$(awk -F'"' '/username=/{print $2; exit}' "$ROOTXML" 2>/dev/null || echo "jspwiki")
fi
if [[ -z "${PGPASSWORD:-}" ]]; then
    PGPASSWORD=$(awk -F'"' '/password=/{print $2; exit}' "$ROOTXML" 2>/dev/null || echo "")
fi
if [[ -z "${PGDATABASE:-}" ]]; then PGDATABASE="jspwiki"; fi
export PGHOST PGUSER PGPASSWORD PGDATABASE

REGEX='^[a-z][a-z0-9_-]{0,30}$'

echo "=== Current node_type distribution ==="
psql -A -t -F'|' -c "SELECT node_type, COUNT(*) FROM kg_nodes GROUP BY node_type ORDER BY 2 DESC"

echo
echo "=== Non-matching types (vocabulary regex: $REGEX) ==="
# psql -t returns rows; trim whitespace; preserve empty-string rows.
mapfile -t BAD < <(psql -A -t -c \
    "SELECT COALESCE(node_type, '__NULL__') FROM kg_nodes GROUP BY node_type
     HAVING node_type IS NULL OR node_type !~ '$REGEX'")

if (( ${#BAD[@]} == 0 )); then
    echo "(none — corpus is clean)"
    exit 0
fi

for ROW in "${BAD[@]}"; do
    if [[ "$ROW" == "__NULL__" ]]; then
        CURRENT="(NULL)"
        WHERE="node_type IS NULL"
    elif [[ -z "$ROW" ]]; then
        CURRENT="(empty string)"
        WHERE="node_type = ''"
    else
        CURRENT="$ROW"
        # Escape single quotes for SQL
        ESCAPED=$(printf "%s" "$ROW" | sed "s/'/''/g")
        WHERE="node_type = '$ESCAPED'"
    fi

    COUNT=$(psql -A -t -c "SELECT COUNT(*) FROM kg_nodes WHERE $WHERE")
    echo
    echo "Polluted type: '$CURRENT'  ($COUNT rows)"
    read -r -p "  [r]ename to <input>, [d]elete rows, [s]kip ? " ACTION

    case "$ACTION" in
        r)
            read -r -p "  Rename to: " REPLACEMENT
            REPL_ESC=$(printf "%s" "$REPLACEMENT" | sed "s/'/''/g")
            psql -c "UPDATE kg_nodes SET node_type = '$REPL_ESC' WHERE $WHERE"
            ;;
        d)
            read -r -p "  Confirm delete $COUNT rows? (yes/no): " CONFIRM
            if [[ "$CONFIRM" == "yes" ]]; then
                psql -c "DELETE FROM kg_nodes WHERE $WHERE"
            else
                echo "  skipped (no confirmation)"
            fi
            ;;
        *)
            echo "  skipped"
            ;;
    esac
done

echo
echo "=== Post-cleanup distribution ==="
psql -A -t -F'|' -c "SELECT node_type, COUNT(*) FROM kg_nodes GROUP BY node_type ORDER BY 2 DESC"

echo
echo "Done. Verify with: bin/kg-cleanup-node-types.sh (should report no pollution)."
