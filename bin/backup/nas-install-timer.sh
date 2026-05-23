#!/usr/bin/env bash
#
# nas-install-timer.sh — install the systemd timer that runs the off-box
# backup pull (nas-pull.sh) on a schedule. Run this ON THE NAS.
#
# Idempotent: re-running overwrites the unit files and re-enables the timer.
# Uses sudo for the privileged steps (writing /etc/systemd/system, reloading
# systemd) — run it as the normal login user; it will prompt for sudo.
#
# Usage:
#   ./nas-install-timer.sh                 # install with defaults
#   ./nas-install-timer.sh --status        # show timer state, don't install
#   ./nas-install-timer.sh --help
#
# Environment overrides (with defaults):
#   PULL_DIR    /home/<user>/wikantik-backup   dir holding nas-pull.sh + .env
#   ENV_FILE    ${PULL_DIR}/nas-pull.env       config sourced by nas-pull.sh
#   RUN_USER    the invoking user              user the pull runs as
#   ON_CALENDAR *-*-* 05:00:00                 systemd OnCalendar schedule
#
# After install, inspect with:
#   systemctl list-timers wikantik-backup-pull.timer
#   journalctl -u wikantik-backup-pull            # pull output
set -euo pipefail

case "${1:-}" in
    -h|--help)
        awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
        exit 0
        ;;
esac

RUN_USER="${RUN_USER:-${SUDO_USER:-$(id -un)}}"
PULL_DIR="${PULL_DIR:-/home/${RUN_USER}/wikantik-backup}"
ENV_FILE="${ENV_FILE:-${PULL_DIR}/nas-pull.env}"
ON_CALENDAR="${ON_CALENDAR:-*-*-* 05:00:00}"
UNIT_NAME="wikantik-backup-pull"

SUDO=""
[ "$(id -u)" -ne 0 ] && SUDO="sudo"

if [ "${1:-}" = "--status" ]; then
    systemctl list-timers "${UNIT_NAME}.timer" --no-pager || true
    systemctl status "${UNIT_NAME}.timer" --no-pager || true
    exit 0
fi

# Sanity: the pull script + env must exist before we schedule them.
[ -x "${PULL_DIR}/nas-pull.sh" ] || { echo "ERROR: ${PULL_DIR}/nas-pull.sh not found or not executable" >&2; exit 1; }
[ -f "${ENV_FILE}" ] || { echo "ERROR: ${ENV_FILE} not found" >&2; exit 1; }

echo "Installing ${UNIT_NAME} timer:"
echo "  run user:   ${RUN_USER}"
echo "  pull dir:   ${PULL_DIR}"
echo "  env file:   ${ENV_FILE}"
echo "  schedule:   ${ON_CALENDAR}"

${SUDO} tee "/etc/systemd/system/${UNIT_NAME}.service" >/dev/null <<EOF
[Unit]
Description=Wikantik off-box backup pull (docker1 -> NAS)
After=network-online.target
Wants=network-online.target

[Service]
Type=oneshot
User=${RUN_USER}
WorkingDirectory=${PULL_DIR}
ExecStart=${PULL_DIR}/nas-pull.sh --env ${ENV_FILE}
EOF

${SUDO} tee "/etc/systemd/system/${UNIT_NAME}.timer" >/dev/null <<EOF
[Unit]
Description=Daily Wikantik off-box backup pull

[Timer]
OnCalendar=${ON_CALENDAR}
Persistent=true

[Install]
WantedBy=timers.target
EOF

${SUDO} systemctl daemon-reload
${SUDO} systemctl enable --now "${UNIT_NAME}.timer"

echo
echo "Installed. Next runs:"
systemctl list-timers "${UNIT_NAME}.timer" --no-pager || true
echo
echo "Run once now to test:  ${SUDO} systemctl start ${UNIT_NAME}.service"
echo "Follow output:         journalctl -u ${UNIT_NAME} -f"
