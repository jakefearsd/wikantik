#!/bin/sh
set -eu

# =================================================================
# Wikantik Restore Script
# =================================================================
#
# Restores both the PostgreSQL database AND wiki page files from
# a backup directory created by backup.sh.
#
# FULL RESTORE PROCEDURE:
#
#   1. Stop the wikantik container (prevents writes during restore):
#      docker compose -f docker-compose.yml -f docker-compose.prod.yml stop wikantik
#
#   2. List available backups:
#      ls ./backups/daily/
#
#   3. Run this restore script via the backup container:
#      docker compose -f docker-compose.yml -f docker-compose.prod.yml \
#        exec backup /usr/local/bin/restore.sh /backups/daily/2026-03-21
#
#   4. Start wikantik (Lucene search index rebuilds automatically):
#      docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d wikantik
#
#   5. Verify: browse to your wiki and confirm content is restored.
#
# WHAT GETS RESTORED:
#   - PostgreSQL tables: users, roles, groups, group_members
#   - Wiki pages: all .md files, .properties files, and attachments
#
# WHAT REBUILDS AUTOMATICALLY ON STARTUP:
#   - Lucene search index (from page files)
#   - Reference manager cache
#   - EhCache (in-memory)
#
# =================================================================

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

if [ ! -f "${BACKUP_PATH}/db.sql" ] || [ ! -f "${BACKUP_PATH}/pages.tar.gz" ]; then
    echo "ERROR: Backup directory is incomplete (missing db.sql or pages.tar.gz)"
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

# --- 2. Restore PostgreSQL database ---
echo ""
echo "Step 2/3: Restoring PostgreSQL database..."
echo "  Dropping existing tables..."
psql -h "${POSTGRES_HOST}" -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" \
    -c "DROP TABLE IF EXISTS group_members, groups, roles, users CASCADE;" \
    > /dev/null 2>&1

echo "  Loading backup..."
psql -h "${POSTGRES_HOST}" -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" \
    < "${BACKUP_PATH}/db.sql" > /dev/null 2>&1

# Verify by counting rows
USER_COUNT=$(psql -h "${POSTGRES_HOST}" -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" \
    -t -c "SELECT COUNT(*) FROM users;" 2>/dev/null | tr -d ' ')
echo "  Database restored (${USER_COUNT} user(s))"

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
