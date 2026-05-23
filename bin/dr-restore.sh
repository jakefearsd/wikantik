#!/usr/bin/env bash
#
# dr-restore.sh — stand up a Wikantik instance on a fresh host from a backup
# snapshot. Captures the full disaster-recovery drill as one command.
#
# Runs from your workstation (which must be able to ssh BOTH the target and the
# snapshot host — the target itself usually cannot reach the backup source).
# It stages the compose stack, gets the image, transfers a verified snapshot,
# brings up Postgres, runs the real docker/backup/restore.sh, starts the app,
# and smoke-tests it.
#
# Usage:
#   dr-restore.sh TARGET_HOST [options]
#
#   TARGET_HOST                ssh host/alias to restore onto. MUST NOT be prod.
#
# Options:
#   --image-tag TAG            pull ghcr.io/jakefearsd/wikantik:TAG on this box,
#                              ship it to the target (default: latest). Works
#                              even if the prod host is gone — true DR.
#   --from-host HOST           skip GHCR; docker save wikantik:latest on HOST
#                              and load it on the target (fast LAN path when a
#                              source host is alive).
#   --snapshot-host HOST       ssh host holding the snapshot (default: nas.lan).
#   --snapshot-path PATH       a dated snapshot dir, or a tier dir containing
#                              LATEST (default: the NAS Drive archive's daily/).
#   --base-url URL             smoke-test URL (default: http://TARGET_HOST:8080).
#   --force                    override the prod-host safety guard (dangerous).
#   --dry-run                  print the plan; change nothing.
#   -h, --help
#
# Environment:
#   WIKANTIK_IMAGE   registry image (default: ghcr.io/jakefearsd/wikantik)
#
# Teardown afterwards (on the target):
#   cd ~/wikantik/repo && docker compose -f docker-compose.yml \
#       -f docker-compose.prod.yml down -v
set -euo pipefail

case "${1:-}" in
    -h|--help)
        awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
        exit 0
        ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

die() { echo "dr-restore: $*" >&2; exit 1; }
log() { echo "[dr-restore] $*"; }

[ $# -ge 1 ] || die "missing TARGET_HOST (see --help)"
TARGET="$1"; shift

WIKANTIK_IMAGE="${WIKANTIK_IMAGE:-ghcr.io/jakefearsd/wikantik}"
IMAGE_TAG="latest"
FROM_HOST=""
SNAPSHOT_HOST="nas.lan"
SNAPSHOT_PATH="/volume1/@home/jakefear/GoogleDrive/wikantik-backups/daily"
BASE_URL=""
FORCE=0
DRY=0

while [ $# -gt 0 ]; do
    case "$1" in
        --image-tag)     IMAGE_TAG="$2"; shift 2 ;;
        --from-host)     FROM_HOST="$2"; shift 2 ;;
        --snapshot-host) SNAPSHOT_HOST="$2"; shift 2 ;;
        --snapshot-path) SNAPSHOT_PATH="$2"; shift 2 ;;
        --base-url)      BASE_URL="$2"; shift 2 ;;
        --force)         FORCE=1; shift ;;
        --dry-run)       DRY=1; shift ;;
        *) die "unknown arg: $1" ;;
    esac
done
BASE_URL="${BASE_URL:-http://${TARGET}:8080}"

# --- Safety: never restore over the production host ---
PROD_HOST=""
[ -f remote.env ] && PROD_HOST="$(grep -E '^REMOTE_HOST=' remote.env | cut -d= -f2 | tr -d '[:space:]')"
if [ -n "${PROD_HOST}" ]; then
    resolve() { getent hosts "$1" 2>/dev/null | awk '{print $1; exit}'; }
    t_ip="$(resolve "${TARGET}")"; p_ip="$(resolve "${PROD_HOST}")"
    if [ "${TARGET}" = "${PROD_HOST}" ] || { [ -n "${t_ip}" ] && [ "${t_ip}" = "${p_ip}" ]; }; then
        [ "${FORCE}" -eq 1 ] || die "TARGET '${TARGET}' is the production host (${PROD_HOST}). Refusing. Use --force only if you really mean it."
        log "WARNING: --force set; targeting the production host ${TARGET}"
    fi
fi

# Commands passed to run() contain shell pipes (docker save | ssh load), so they
# must go through a shell — eval on the joined string is intentional here.
# shellcheck disable=SC2294
run() { if [ "${DRY}" -eq 1 ]; then echo "  + $*"; else eval "$*"; fi; }
tssh() { if [ "${DRY}" -eq 1 ]; then echo "  + ssh ${TARGET} '$*'"; else ssh -o BatchMode=yes "${TARGET}" "$*"; fi; }

log "target=${TARGET}  image=${WIKANTIK_IMAGE}:${IMAGE_TAG}${FROM_HOST:+ (save|load from ${FROM_HOST})}"
log "snapshot=${SNAPSHOT_HOST}:${SNAPSHOT_PATH}  base-url=${BASE_URL}  dry-run=${DRY}"

# --- Resolve target home + paths ---
if [ "${DRY}" -eq 1 ]; then THOME="/home/jakefear"; else THOME="$(ssh -o BatchMode=yes "${TARGET}" 'echo $HOME')"; fi
RDIR="${THOME}/wikantik/repo"
PAGES_DIR="${THOME}/wikantik/pages"
BDIR="${THOME}/wikantik/backups"
COMPOSE="docker compose -f docker-compose.yml -f docker-compose.prod.yml"

# --- 1. Stage compose + scripts + .env on the target ---
log "1/6 staging compose stack on ${TARGET}:${RDIR}"
tssh "mkdir -p ${RDIR}/docker/backup ${RDIR}/docker/db ${PAGES_DIR} ${BDIR}"
if [ "${DRY}" -eq 1 ]; then
    echo "  + rsync compose + docker/{db,backup} -> ${TARGET}:${RDIR}"
else
    rsync -az --chmod=F644 docker-compose.yml docker-compose.prod.yml "${TARGET}:${RDIR}/"
    rsync -az --chmod=F644 docker/db/ "${TARGET}:${RDIR}/docker/db/"
    rsync -az --chmod=F755 docker/backup/backup.sh docker/backup/restore.sh "${TARGET}:${RDIR}/docker/backup/"
    rsync -az --chmod=F644 docker/backup/crontab "${TARGET}:${RDIR}/docker/backup/"
fi

if [ "${DRY}" -eq 1 ]; then PW="DRYRUNPASS"; else PW="$(openssl rand -hex 16)"; fi
log "1/6 writing target .env (fresh DB password)"
if [ "${DRY}" -eq 1 ]; then
    echo "  + write ${RDIR}/.env (POSTGRES_PASSWORD=<generated>, WIKANTIK_PAGES_DIR=${PAGES_DIR}, BACKUP_DIR=${BDIR})"
else
    # Heredoc expands client-side on purpose — ${PW}, ${PAGES_DIR}, ${BDIR} are
    # local values written into the target's .env.
    # shellcheck disable=SC2087
    ssh -o BatchMode=yes "${TARGET}" "cat > ${RDIR}/.env" <<EOF
POSTGRES_DB=wikantik
POSTGRES_USER=wikantik
POSTGRES_PASSWORD=${PW}
POSTGRES_HOST=localhost
POSTGRES_PORT=5432
WIKANTIK_BASE_URL=${BASE_URL%/}/
WIKANTIK_HOST_PORT=8080
WIKANTIK_PAGES_DIR=${PAGES_DIR}
BACKUP_DIR=${BDIR}
MCP_USERS=curator
DB_HOST_BIND=172.17.0.1
EOF
    ssh -o BatchMode=yes "${TARGET}" "chmod 600 ${RDIR}/.env"
fi

# --- 2. Get the wikantik image onto the target ---
log "2/6 provisioning image on ${TARGET}"
if [ -n "${FROM_HOST}" ]; then
    run "ssh -o BatchMode=yes ${FROM_HOST} 'docker save wikantik:latest' | ssh -o BatchMode=yes ${TARGET} 'docker load'"
else
    run "docker pull ${WIKANTIK_IMAGE}:${IMAGE_TAG}"
    run "docker tag ${WIKANTIK_IMAGE}:${IMAGE_TAG} wikantik:latest"
    run "docker save wikantik:latest | ssh -o BatchMode=yes ${TARGET} 'docker load'"
fi

# --- 3. Transfer + verify the snapshot ---
log "3/6 resolving + transferring snapshot from ${SNAPSHOT_HOST}"
if [ "${DRY}" -eq 1 ]; then
    echo "  + resolve LATEST under ${SNAPSHOT_PATH}, scp files -> ${TARGET}:${BDIR}/daily/<date>, verify checksums"
    SNAP_DATE="<date>"
else
    # If SNAPSHOT_PATH has a LATEST file it's a tier dir; follow it. Else it's the snapshot.
    if ssh -o BatchMode=yes "${SNAPSHOT_HOST}" "test -f ${SNAPSHOT_PATH}/LATEST"; then
        SNAP_DATE="$(ssh -o BatchMode=yes "${SNAPSHOT_HOST}" "cat ${SNAPSHOT_PATH}/LATEST")"
        SRC_DIR="${SNAPSHOT_PATH}/${SNAP_DATE}"
    else
        SRC_DIR="${SNAPSHOT_PATH}"; SNAP_DATE="$(basename "${SNAPSHOT_PATH}")"
    fi
    DEST_DIR="${BDIR}/daily/${SNAP_DATE}"
    log "    snapshot ${SNAP_DATE}: ${SNAPSHOT_HOST}:${SRC_DIR} -> ${TARGET}:${DEST_DIR}"
    tssh "mkdir -p ${DEST_DIR}"
    for f in db.sql.gz db.sql pages.tar.gz checksums.sha256 backup-status.json; do
        if ssh -o BatchMode=yes "${SNAPSHOT_HOST}" "test -f ${SRC_DIR}/${f}"; then
            ssh -o BatchMode=yes "${SNAPSHOT_HOST}" "cat ${SRC_DIR}/${f}" \
                | ssh -o BatchMode=yes "${TARGET}" "cat > ${DEST_DIR}/${f}"
        fi
    done
    echo "${SNAP_DATE}" | ssh -o BatchMode=yes "${TARGET}" "cat > ${BDIR}/daily/LATEST"
    log "    verifying checksums on ${TARGET}"
    ssh -o BatchMode=yes "${TARGET}" "cd ${DEST_DIR} && sha256sum -c checksums.sha256" \
        || die "checksum verification failed on the target — aborting before restore"
fi

# --- 4. Bring up Postgres ---
log "4/6 starting Postgres on ${TARGET}"
tssh "cd ${RDIR} && ${COMPOSE} up -d db"
if [ "${DRY}" -eq 0 ]; then
    for _ in $(seq 1 40); do
        s="$(ssh -o BatchMode=yes "${TARGET}" "cd ${RDIR} && ${COMPOSE} ps db --format '{{.Health}}'" 2>/dev/null || true)"
        [ "${s}" = "healthy" ] && break; sleep 3
    done
    [ "${s:-}" = "healthy" ] || die "Postgres did not become healthy on ${TARGET}"
fi

# --- 5. Restore via the real restore.sh (one-off container) ---
log "5/6 restoring snapshot ${SNAP_DATE} via docker/backup/restore.sh"
tssh "cd ${RDIR} && ${COMPOSE} run --rm --no-deps --entrypoint /usr/local/bin/restore.sh backup /backups/daily/${SNAP_DATE}"

# --- 6. Start the app + smoke test ---
log "6/6 starting wikantik + smoke test"
tssh "cd ${RDIR} && ${COMPOSE} up -d wikantik"
if [ "${DRY}" -eq 1 ]; then
    echo "  + wait for ${BASE_URL}/api/health, then bin/smoke-wiki.sh ${BASE_URL}"
    log "DRY RUN complete — no changes made."
    exit 0
fi
for _ in $(seq 1 40); do
    curl -fsS --max-time 5 "${BASE_URL}/api/health" >/dev/null 2>&1 && break; sleep 3
done
"${REPO_ROOT}/bin/smoke-wiki.sh" "${BASE_URL}" || die "smoke test failed against ${BASE_URL}"

echo
log "DR restore complete. Wikantik is live at ${BASE_URL}"
log "Teardown: ssh ${TARGET} 'cd ${RDIR} && ${COMPOSE} down -v'"
