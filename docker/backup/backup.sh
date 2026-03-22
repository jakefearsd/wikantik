#!/bin/sh
set -eu

TIER="${1:-daily}"
DATE="$(date +%Y-%m-%d)"
BACKUP_PATH="/backups/${TIER}/${DATE}"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-30}"

echo "================================================================"
echo "[$(date)] Starting ${TIER} backup"
echo "================================================================"

mkdir -p "${BACKUP_PATH}"

# --- 1. Dump PostgreSQL database ---
echo "Dumping PostgreSQL database ${POSTGRES_DB}..."
if ! pg_dump -h "${POSTGRES_HOST}" -U "${POSTGRES_USER}" -d "${POSTGRES_DB}" \
    --no-owner --no-privileges > "${BACKUP_PATH}/db.sql"; then
    echo "ERROR: pg_dump failed!"
    exit 1
fi
DB_SIZE=$(wc -c < "${BACKUP_PATH}/db.sql")
echo "  db.sql: ${DB_SIZE} bytes"

# --- 2. Archive wiki pages (content + attachments + .properties) ---
echo "Archiving wiki pages..."
PAGE_COUNT=$(find /var/wikantik/pages -name '*.md' 2>/dev/null | wc -l)
tar -czf "${BACKUP_PATH}/pages.tar.gz" -C /var/wikantik/pages .
PAGES_SIZE=$(wc -c < "${BACKUP_PATH}/pages.tar.gz")
echo "  pages.tar.gz: ${PAGES_SIZE} bytes (${PAGE_COUNT} .md files)"

# --- 3. Create checksum manifest ---
cd "${BACKUP_PATH}"
sha256sum db.sql pages.tar.gz > checksums.sha256
echo "  checksums.sha256 written"

echo ""
echo "Backup written to ${BACKUP_PATH}"
ls -lh "${BACKUP_PATH}/"

# --- 4. Prune old backups ---
echo ""
echo "Pruning old ${TIER} backups..."
case "${TIER}" in
    daily)
        PRUNED=$(find /backups/daily -mindepth 1 -maxdepth 1 -type d -mtime "+${RETENTION_DAYS}" 2>/dev/null | wc -l)
        find /backups/daily -mindepth 1 -maxdepth 1 -type d -mtime "+${RETENTION_DAYS}" -exec rm -rf {} + 2>/dev/null || true
        echo "  Pruned ${PRUNED} daily backup(s) older than ${RETENTION_DAYS} days"
        ;;
    weekly)
        PRUNED=$(find /backups/weekly -mindepth 1 -maxdepth 1 -type d -mtime +84 2>/dev/null | wc -l)
        find /backups/weekly -mindepth 1 -maxdepth 1 -type d -mtime +84 -exec rm -rf {} + 2>/dev/null || true
        echo "  Pruned ${PRUNED} weekly backup(s) older than 12 weeks"
        ;;
    monthly)
        PRUNED=$(find /backups/monthly -mindepth 1 -maxdepth 1 -type d -mtime +365 2>/dev/null | wc -l)
        find /backups/monthly -mindepth 1 -maxdepth 1 -type d -mtime +365 -exec rm -rf {} + 2>/dev/null || true
        echo "  Pruned ${PRUNED} monthly backup(s) older than 12 months"
        ;;
esac

echo ""
echo "[$(date)] ${TIER} backup complete."
echo "================================================================"
