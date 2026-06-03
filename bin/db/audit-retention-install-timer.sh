#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#
# audit-retention-install-timer.sh — install the systemd timer that runs the
# monthly audit_log retention purge (audit-retention.sh) on a schedule.
#
# Idempotent: re-running overwrites the unit files and re-enables the timer.
# Uses sudo for the privileged steps (writing /etc/systemd/system, reloading
# systemd) — run it as the normal login user; it will prompt for sudo.
#
# Usage:
#   ./audit-retention-install-timer.sh                 # install with defaults
#   ./audit-retention-install-timer.sh --status        # show timer state, don't install
#   ./audit-retention-install-timer.sh --help
#
# Environment overrides (with defaults):
#   ENV_FILE               /etc/wikantik/audit-retention.env  config sourced by audit-retention.sh
#   ON_CALENDAR            *-*-01 04:00:00                    systemd OnCalendar schedule
#
# EnvironmentFile keys (audit-retention.env):
#   DB_NAME                wikantik
#   PGHOST                 localhost
#   PGPORT                 5432
#   PGUSER                 migrate
#   PGPASSWORD             (required — database password for migrate role)
#   AUDIT_RETENTION_MONTHS 84          keep this many months; older partitions purged
#   AUDIT_PARTITION_LOOKAHEAD 3        months of future partitions to pre-create
#   AUDIT_ARCHIVE_DIR                  (required — dir the off-box backup captures)
#
# After install, inspect with:
#   systemctl list-timers wikantik-audit-retention.timer
#   journalctl -u wikantik-audit-retention            # audit retention output
#
# Run once now to test:
#   systemctl start wikantik-audit-retention.service
set -euo pipefail

case "${1:-}" in
    -h|--help)
        awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
        exit 0
        ;;
esac

UNIT_NAME="wikantik-audit-retention"
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ENV_FILE="${ENV_FILE:-/etc/wikantik/audit-retention.env}"
ON_CALENDAR="${ON_CALENDAR:-*-*-01 04:00:00}"

SUDO=""
[ "$(id -u)" -ne 0 ] && SUDO="sudo"

if [ "${1:-}" = "--status" ]; then
    systemctl list-timers "${UNIT_NAME}.timer" --no-pager || true
    systemctl status "${UNIT_NAME}.timer" --no-pager || true
    exit 0
fi

# Sanity: the audit retention script must exist before we schedule it.
[ -x "${REPO_DIR}/bin/db/audit-retention.sh" ] || { echo "ERROR: ${REPO_DIR}/bin/db/audit-retention.sh not found or not executable" >&2; exit 1; }
[ -f "${ENV_FILE}" ] || { echo "ERROR: ${ENV_FILE} not found" >&2; exit 1; }

echo "Installing ${UNIT_NAME} timer:"
echo "  repo dir:   ${REPO_DIR}"
echo "  env file:   ${ENV_FILE}"
echo "  schedule:   ${ON_CALENDAR}"

${SUDO} tee "/etc/systemd/system/${UNIT_NAME}.service" >/dev/null <<EOF
[Unit]
Description=Wikantik audit_log retention purge
After=network-online.target

[Service]
Type=oneshot
EnvironmentFile=-${ENV_FILE}
ExecStart=${REPO_DIR}/bin/db/audit-retention.sh
EOF

${SUDO} tee "/etc/systemd/system/${UNIT_NAME}.timer" >/dev/null <<EOF
[Unit]
Description=Monthly Wikantik audit_log retention purge

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
