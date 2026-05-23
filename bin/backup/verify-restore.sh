#!/usr/bin/env bash
#
# verify-restore.sh — prove a backup snapshot is restorable.
#
# Spins up a throwaway PostgreSQL (pgvector) container, loads db.sql from a
# snapshot, extracts pages.tar.gz to a temp dir, asserts core-table row counts
# and that the page count matches backup-status.json, then tears everything
# down. Touches no real database or page tree.
#
# Usage:
#   verify-restore.sh /path/to/backups/daily/2026-05-23   # explicit snapshot
#   verify-restore.sh /path/to/backups/daily              # uses LATEST
#   verify-restore.sh --help
#
# Exit status: 0 if the snapshot restores and verifies; non-zero otherwise.
#
# Requires: docker, and a snapshot dir containing db.sql, pages.tar.gz,
# checksums.sha256, and (optionally) backup-status.json.
set -euo pipefail

case "${1:-}" in
    -h|--help)
        awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
        exit 0
        ;;
esac

ARG="${1:?Usage: verify-restore.sh /path/to/snapshot-or-tier-dir}"
PG_IMAGE="${VERIFY_PG_IMAGE:-pgvector/pgvector:pg18}"
CONTAINER="wikantik-verify-restore-$$"
WORKDIR="$(mktemp -d)"

cleanup() {
    docker rm -f "${CONTAINER}" >/dev/null 2>&1 || true
    rm -rf "${WORKDIR}" || true
}
trap cleanup EXIT

# Resolve snapshot: if ARG has a LATEST file, follow it; else treat ARG as the snapshot.
if [ -f "${ARG}/LATEST" ]; then
    SNAP="${ARG}/$(cat "${ARG}/LATEST")"
else
    SNAP="${ARG}"
fi

for f in db.sql pages.tar.gz checksums.sha256; do
    [ -f "${SNAP}/${f}" ] || { echo "ERROR: ${SNAP}/${f} not found"; exit 1; }
done
echo "Verifying snapshot: ${SNAP}"

echo "Step 1/4: checksum verification"
( cd "${SNAP}" && sha256sum -c checksums.sha256 ) || { echo "ERROR: checksum mismatch"; exit 1; }

echo "Step 2/4: starting throwaway ${PG_IMAGE}"
docker run -d --name "${CONTAINER}" \
    -e POSTGRES_PASSWORD=verify -e POSTGRES_DB=wikantik_verify -e POSTGRES_USER=verify \
    "${PG_IMAGE}" >/dev/null
# Wait for readiness.
for _ in $(seq 1 30); do
    if docker exec "${CONTAINER}" pg_isready -U verify -d wikantik_verify >/dev/null 2>&1; then
        break
    fi
    sleep 2
done
docker exec "${CONTAINER}" pg_isready -U verify -d wikantik_verify >/dev/null 2>&1 \
    || { echo "ERROR: ephemeral PG never became ready"; exit 1; }

echo "Step 3/4: loading db.sql + asserting core tables"
docker exec "${CONTAINER}" psql -U verify -d wikantik_verify -v ON_ERROR_STOP=1 \
    -c "CREATE EXTENSION IF NOT EXISTS vector;" -c "CREATE EXTENSION IF NOT EXISTS pgcrypto;" \
    >/dev/null 2>&1 || true
if ! docker exec -i "${CONTAINER}" psql -U verify -d wikantik_verify -q \
        < "${SNAP}/db.sql" > "${WORKDIR}/load.log" 2>&1; then
    echo "ERROR: db.sql failed to load — last lines:"; tail -20 "${WORKDIR}/load.log"; exit 1
fi
for tbl in users kg_nodes page_canonical_ids; do
    CNT=$(docker exec "${CONTAINER}" psql -U verify -d wikantik_verify -t -A \
        -c "SELECT COUNT(*) FROM ${tbl};" 2>/dev/null || echo "MISSING")
    [ "${CNT}" = "MISSING" ] && { echo "ERROR: table ${tbl} missing after restore"; exit 1; }
    echo "    ${tbl}: ${CNT} row(s)"
done

echo "Step 4/4: extracting pages + checking count"
mkdir -p "${WORKDIR}/pages"
tar -xzf "${SNAP}/pages.tar.gz" -C "${WORKDIR}/pages"
ACTUAL_PAGES=$(find "${WORKDIR}/pages" -name '*.md' | wc -l | tr -d ' ')
echo "    extracted ${ACTUAL_PAGES} .md file(s)"
if [ -f "${SNAP}/backup-status.json" ]; then
    EXPECTED=$(grep -o '"page_count": *[0-9]*' "${SNAP}/backup-status.json" | grep -o '[0-9]*')
    if [ -n "${EXPECTED}" ] && [ "${ACTUAL_PAGES}" != "${EXPECTED}" ]; then
        echo "ERROR: page count ${ACTUAL_PAGES} != manifest ${EXPECTED}"; exit 1
    fi
    echo "    matches manifest page_count=${EXPECTED}"
fi

echo ""
echo "RESTORE VERIFICATION PASSED for ${SNAP}"
