#!/bin/sh
#
# backup.sh — Wikantik backup sidecar driver. Dumps PostgreSQL via pg_dump
# and archives the wiki page tree, then prunes outdated entries by tier.
#
# Runs inside the docker-compose `backup` container; expects pages/db
# volumes mounted and POSTGRES_* environment variables provided by compose.
#
# Usage:
#   backup.sh                 # default: daily backup
#   backup.sh daily           # alias
#   backup.sh weekly          # weekly tier (12-week retention)
#   backup.sh monthly         # monthly tier (12-month retention)
#   backup.sh --help          # show this help
#
# Environment variables (provided by docker-compose):
#   POSTGRES_HOST           PG host
#   POSTGRES_USER           PG user
#   POSTGRES_DB             PG database
#   PGPASSWORD              PG password
#   BACKUP_RETENTION_DAYS   daily-tier retention (default 30)
#
# Output:
#   /backups/${TIER}/YYYY-MM-DD/db.sql           pg_dump output
#   /backups/${TIER}/YYYY-MM-DD/pages.tar.gz     wiki page tarball
#   /backups/${TIER}/YYYY-MM-DD/checksums.sha256 SHA-256 manifest
#
# Pair with restore.sh for the inverse operation.

set -eu

case "${1:-}" in
    -h|--help)
        awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
        exit 0
        ;;
esac

TIER="${1:-daily}"
DATE="$(date +%Y-%m-%d)"
BACKUP_ROOT="${BACKUP_ROOT:-/backups}"
PAGES_DIR="${PAGES_DIR:-/var/wikantik/pages}"
BACKUP_PATH="${BACKUP_ROOT}/${TIER}/${DATE}"
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
PAGE_COUNT=$(find "${PAGES_DIR}" -name '*.md' 2>/dev/null | wc -l)
tar -czf "${BACKUP_PATH}/pages.tar.gz" -C "${PAGES_DIR}" .
PAGES_SIZE=$(wc -c < "${BACKUP_PATH}/pages.tar.gz")
echo "  pages.tar.gz: ${PAGES_SIZE} bytes (${PAGE_COUNT} .md files)"

# --- 3. Create checksum manifest ---
cd "${BACKUP_PATH}"
sha256sum db.sql pages.tar.gz > checksums.sha256
echo "  checksums.sha256 written"

# --- Status manifest + LATEST pointer ---
FINISHED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
cat > "${BACKUP_PATH}/backup-status.json" <<JSON
{
  "tier": "${TIER}",
  "date": "${DATE}",
  "finished_at": "${FINISHED_AT}",
  "db_bytes": ${DB_SIZE},
  "pages_bytes": ${PAGES_SIZE},
  "page_count": ${PAGE_COUNT},
  "exit_status": 0
}
JSON
printf '%s\n' "${DATE}" > "${BACKUP_ROOT}/${TIER}/LATEST"
echo "  backup-status.json + LATEST written"

echo ""
echo "Backup written to ${BACKUP_PATH}"
ls -lh "${BACKUP_PATH}/"

# --- 4. Prune old backups ---
echo ""
echo "Pruning old ${TIER} backups..."
case "${TIER}" in
    daily)
        PRUNED=$(find "${BACKUP_ROOT}/daily" -mindepth 1 -maxdepth 1 -type d -mtime "+${RETENTION_DAYS}" 2>/dev/null | wc -l)
        find "${BACKUP_ROOT}/daily" -mindepth 1 -maxdepth 1 -type d -mtime "+${RETENTION_DAYS}" -exec rm -rf {} + 2>/dev/null || true
        echo "  Pruned ${PRUNED} daily backup(s) older than ${RETENTION_DAYS} days"
        ;;
    weekly)
        PRUNED=$(find "${BACKUP_ROOT}/weekly" -mindepth 1 -maxdepth 1 -type d -mtime +84 2>/dev/null | wc -l)
        find "${BACKUP_ROOT}/weekly" -mindepth 1 -maxdepth 1 -type d -mtime +84 -exec rm -rf {} + 2>/dev/null || true
        echo "  Pruned ${PRUNED} weekly backup(s) older than 12 weeks"
        ;;
    monthly)
        PRUNED=$(find "${BACKUP_ROOT}/monthly" -mindepth 1 -maxdepth 1 -type d -mtime +365 2>/dev/null | wc -l)
        find "${BACKUP_ROOT}/monthly" -mindepth 1 -maxdepth 1 -type d -mtime +365 -exec rm -rf {} + 2>/dev/null || true
        echo "  Pruned ${PRUNED} monthly backup(s) older than 12 months"
        ;;
esac

echo ""
echo "[$(date)] ${TIER} backup complete."
echo "================================================================"
