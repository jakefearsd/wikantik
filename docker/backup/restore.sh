#!/bin/sh
#
# restore.sh — Wikantik restore driver. Restores both the PostgreSQL
# database AND the wiki page tree from a backup directory created by
# backup.sh.
#
# Usage:
#   restore.sh /backups/<tier>/<YYYY-MM-DD>     # restore that snapshot
#   restore.sh --help                           # show this help
#
# Full restore procedure (against a docker-compose stack):
#   1. Stop the wikantik container (prevents writes during restore):
#        docker compose -f docker-compose.yml -f docker-compose.prod.yml stop wikantik
#   2. List available backups:
#        ls ./backups/daily/
#   3. Run this script in the backup sidecar:
#        docker compose -f docker-compose.yml -f docker-compose.prod.yml \
#          exec backup /usr/local/bin/restore.sh /backups/daily/2026-03-21
#   4. Start wikantik (Lucene search index rebuilds automatically):
#        docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d wikantik
#   5. Verify: browse to your wiki and confirm content is restored.
#
# What gets restored:
#   - The ENTIRE PostgreSQL schema (public schema is dropped and rebuilt
#     from the dump): users, roles, groups, policy_grants, all kg_* tables,
#     page_canonical_ids, embeddings, schema_migrations, everything.
#   - Wiki pages: all .md files, .properties files, and attachments
#
# What rebuilds automatically on startup:
#   - Lucene search index (from page files)
#   - Reference manager cache
#   - EhCache (in-memory)

set -eu

case "${1:-}" in
    -h|--help)
        awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
        exit 0
        ;;
esac

BACKUP_PATH="${1:?Usage: restore.sh /backups/<tier>/<date>}"

if [ ! -d "${BACKUP_PATH}" ]; then
    echo "ERROR: Backup directory not found: ${BACKUP_PATH}"
    echo ""
    echo "Available backups:"
    for tier in daily weekly monthly; do
        if [ -d "/backups/${tier}" ]; then
            echo "  ${tier}:"
            ls -1 "/backups/${tier}/" 2>/dev/null | sed 's/^/    /'
        fi
    done
    exit 1
fi

# Accept either the compressed dump (current) or a legacy uncompressed db.sql.
if [ -f "${BACKUP_PATH}/db.sql.gz" ]; then
    DB_DUMP="db.sql.gz"
elif [ -f "${BACKUP_PATH}/db.sql" ]; then
    DB_DUMP="db.sql"
else
    DB_DUMP=""
fi
if [ -z "${DB_DUMP}" ] || [ ! -f "${BACKUP_PATH}/pages.tar.gz" ]; then
    echo "ERROR: Backup directory is incomplete (missing db.sql[.gz] or pages.tar.gz)"
    echo "Contents of ${BACKUP_PATH}:"
    ls -la "${BACKUP_PATH}/"
    exit 1
fi

echo "================================================================"
echo "Restoring from: ${BACKUP_PATH}"
echo "================================================================"

# --- 1. Verify checksums ---
echo ""
echo "Step 1/3: Verifying checksums..."
cd "${BACKUP_PATH}"
if ! sha256sum -c checksums.sha256; then
    echo ""
    echo "ERROR: Checksum verification failed! Backup may be corrupted."
    echo "Do NOT proceed with this backup."
    exit 1
fi
echo "  Checksums OK"

# --- 2. Restore PostgreSQL database (full clean-schema reload) ---
echo ""
echo "Step 2/3: Restoring PostgreSQL database (full schema)..."
echo "  Resetting public schema..."
psql -h "${POSTGRES_HOST}" -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" \
    -v ON_ERROR_STOP=1 \
    -c "DROP SCHEMA IF EXISTS public CASCADE;" \
    -c "CREATE SCHEMA public;" \
    -c "GRANT ALL ON SCHEMA public TO \"${POSTGRES_USER}\";" \
    -c "GRANT ALL ON SCHEMA public TO public;" > /dev/null

echo "  Ensuring required extensions exist..."
psql -h "${POSTGRES_HOST}" -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" \
    -v ON_ERROR_STOP=1 \
    -c "CREATE EXTENSION IF NOT EXISTS vector;" \
    -c "CREATE EXTENSION IF NOT EXISTS pgcrypto;" > /dev/null 2>&1 || \
    echo "  WARN: extension creation reported an issue (dump may recreate them)"

echo "  Loading backup (${DB_DUMP})..."
if [ "${DB_DUMP}" = "db.sql.gz" ]; then
    LOAD_CMD="gunzip -c ${BACKUP_PATH}/db.sql.gz"
else
    LOAD_CMD="cat ${BACKUP_PATH}/db.sql"
fi
if ! ${LOAD_CMD} | psql -h "${POSTGRES_HOST}" -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" \
    -v ON_ERROR_STOP=1 -q > /tmp/restore.log 2>&1; then
    echo "  ERROR: restore failed — last lines of psql output:"
    tail -20 /tmp/restore.log
    exit 1
fi

# Verify a representative spread of core tables loaded.
echo "  Verifying core tables..."
for tbl in users kg_nodes page_canonical_ids; do
    CNT=$(psql -h "${POSTGRES_HOST}" -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" \
        -t -A -c "SELECT COUNT(*) FROM ${tbl};" 2>/dev/null || echo "MISSING")
    if [ "${CNT}" = "MISSING" ]; then
        echo "  ERROR: expected table '${tbl}' is missing after restore!"
        exit 1
    fi
    # users must be non-empty — a well-formed but empty dump is a failed restore.
    if [ "${tbl}" = "users" ] && [ "${CNT}" -eq 0 ]; then
        echo "  ERROR: users table restored with 0 rows — dump is empty/incomplete!"
        exit 1
    fi
    echo "    ${tbl}: ${CNT} row(s)"
done

# --- 3. Restore wiki pages ---
echo ""
echo "Step 3/3: Restoring wiki pages..."
PAGE_COUNT_BEFORE=$(find /var/wikantik/pages -name '*.md' 2>/dev/null | wc -l)
echo "  Current pages: ${PAGE_COUNT_BEFORE}"

echo "  Clearing current pages..."
rm -rf /var/wikantik/pages/*

echo "  Extracting backup..."
tar -xzf "${BACKUP_PATH}/pages.tar.gz" -C /var/wikantik/pages

PAGE_COUNT_AFTER=$(find /var/wikantik/pages -name '*.md' 2>/dev/null | wc -l)
echo "  Restored pages: ${PAGE_COUNT_AFTER}"

echo ""
echo "================================================================"
echo "Restore complete!"
echo ""
echo "Next step: start the wikantik container:"
echo "  docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d wikantik"
echo ""
echo "The search index will rebuild automatically on startup (~30-60 seconds)."
echo "================================================================"
