#!/usr/bin/env bash
#
# nas-pull.sh — pull Wikantik backups from docker1 onto the NAS, verify
# checksums, prune to NAS retention, and emit an off-box heartbeat.
#
# Runs on the DXP4800 Plus (typically inside a tiny scheduled container with
# rsync + openssh-client + curl). Pull model: the NAS reaches into docker1
# using a read-only SSH key. docker1 holds no NAS credentials.
#
# Usage:
#   nas-pull.sh [--env FILE] [--dry-run]
#     --env FILE   config file to source (default: ./nas-pull.env)
#     --dry-run    print the rsync command (with --dry-run) and skip prune/heartbeat
#   nas-pull.sh --help
#
# Config (see nas-pull.env.example): DOCKER1_HOST, DOCKER1_USER,
# DOCKER1_BACKUP_DIR, SSH_KEY, NAS_DEST, NAS_RETAIN_*_DAYS, LOKI_URL,
# PUSHGATEWAY_URL.
set -euo pipefail

case "${1:-}" in
    -h|--help)
        awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
        exit 0
        ;;
esac

ENV_FILE="./nas-pull.env"
DRY_RUN=0
while [ $# -gt 0 ]; do
    case "$1" in
        --env) ENV_FILE="$2"; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        *) echo "Unknown arg: $1" >&2; exit 2 ;;
    esac
done

[ -f "${ENV_FILE}" ] || { echo "ERROR: env file not found: ${ENV_FILE}" >&2; exit 1; }
# shellcheck disable=SC1090
. "${ENV_FILE}"

: "${DOCKER1_HOST:?set DOCKER1_HOST}"
: "${DOCKER1_USER:?set DOCKER1_USER}"
: "${DOCKER1_BACKUP_DIR:?set DOCKER1_BACKUP_DIR}"
: "${NAS_DEST:?set NAS_DEST}"
SSH_KEY="${SSH_KEY:-}"

SRC="${DOCKER1_USER}@${DOCKER1_HOST}:${DOCKER1_BACKUP_DIR}/"
SSH_CMD="ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new"
[ -n "${SSH_KEY}" ] && SSH_CMD="${SSH_CMD} -i ${SSH_KEY}"

RSYNC_OPTS=(-a --partial --human-readable -e "${SSH_CMD}")

if [ "${DRY_RUN}" -eq 1 ]; then
    echo "DRY RUN — would execute:"
    echo "rsync ${RSYNC_OPTS[*]} --dry-run ${SRC} ${NAS_DEST}/"
    exit 0
fi

echo "[$(date)] Pulling backups: ${SRC} -> ${NAS_DEST}/"
mkdir -p "${NAS_DEST}"
rsync "${RSYNC_OPTS[@]}" "${SRC}" "${NAS_DEST}/"

# --- Verify checksums of the newest snapshot in each tier ---
VERIFY_FAIL=0
for tier in daily weekly monthly; do
    latest_file="${NAS_DEST}/${tier}/LATEST"
    [ -f "${latest_file}" ] || continue
    snap="${NAS_DEST}/${tier}/$(cat "${latest_file}")"
    if [ -f "${snap}/checksums.sha256" ]; then
        if ( cd "${snap}" && sha256sum -c checksums.sha256 >/dev/null 2>&1 ); then
            echo "  ${tier}: checksum OK ($(basename "${snap}"))"
        else
            echo "  ERROR: ${tier} checksum verification FAILED for ${snap}" >&2
            VERIFY_FAIL=1
        fi
    fi
done

# --- Prune to NAS retention ---
prune_tier() {
    tier="$1"; days="$2"
    [ -d "${NAS_DEST}/${tier}" ] || return 0
    pruned=$(find "${NAS_DEST}/${tier}" -mindepth 1 -maxdepth 1 -type d -mtime "+${days}" 2>/dev/null | wc -l)
    find "${NAS_DEST}/${tier}" -mindepth 1 -maxdepth 1 -type d -mtime "+${days}" -exec rm -rf {} + 2>/dev/null || true
    echo "  pruned ${pruned} ${tier} snapshot(s) older than ${days}d"
}
prune_tier daily   "${NAS_RETAIN_DAILY_DAYS:-90}"
prune_tier weekly  "${NAS_RETAIN_WEEKLY_DAYS:-183}"
prune_tier monthly "${NAS_RETAIN_MONTHLY_DAYS:-365}"

# --- Off-box heartbeat ---
NOW_EPOCH="$(date +%s)"
STATUS="success"; [ "${VERIFY_FAIL}" -eq 1 ] && STATUS="checksum_failed"
if [ -n "${LOKI_URL:-}" ]; then
    NOW_NS="${NOW_EPOCH}000000000"
    curl -sf -X POST "${LOKI_URL}" -H 'Content-Type: application/json' \
        --data-binary "{\"streams\":[{\"stream\":{\"job\":\"wikantik_backup_offsite\",\"status\":\"${STATUS}\"},\"values\":[[\"${NOW_NS}\",\"offsite pull ${STATUS} dest=${NAS_DEST}\"]]}]}" \
        >/dev/null 2>&1 && echo "  heartbeat pushed to Loki (${STATUS})" \
        || echo "  WARN: Loki heartbeat push failed"
elif [ -n "${PUSHGATEWAY_URL:-}" ]; then
    printf 'wikantik_backup_offsite_last_success_timestamp_seconds %s\n' "${NOW_EPOCH}" \
        | curl -sf --data-binary @- "${PUSHGATEWAY_URL}/metrics/job/wikantik_backup_offsite" \
        >/dev/null 2>&1 && echo "  heartbeat pushed to Pushgateway" \
        || echo "  WARN: Pushgateway heartbeat push failed"
fi

[ "${VERIFY_FAIL}" -eq 1 ] && { echo "[$(date)] off-box pull completed WITH checksum failures" >&2; exit 1; }
echo "[$(date)] off-box pull complete."
