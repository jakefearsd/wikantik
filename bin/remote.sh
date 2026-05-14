#!/usr/bin/env bash
#
# bin/remote.sh — ssh-driven admin tool for a remote Wikantik deployment.
# Wraps bin/container.sh on the remote host plus adds image transfer
# (docker save | ssh), rsync-based content sync, ControlMaster, and a
# deploy lock. See docs/superpowers/specs/2026-05-14-remote-container-admin-design.md.
#
# Subcommands:
#   bootstrap                       first-time remote setup (no up -d)
#   deploy   [--skip-build] [--health-timeout=N]
#                                   build, push image, up -d, health-poll, auto-rollback
#   rollback                        re-promote :rollback tag to :latest
#   up | down | restart             pass-through to remote container.sh -e prod
#   status                          health + container ps + disk free + pages size
#   logs     [-f] [SERVICE]         tail logs
#   shell    [SERVICE]              interactive shell in a remote container
#   psql     -- ARGS...             psql pass-through
#   migrate  [--status]             ad-hoc migration run
#   pages-push LOCAL_DIR [--mirror] rsync local pages → remote
#   pages-pull LOCAL_DIR            rsync remote pages → local
#   backup-trigger [TIER]           invoke prod backup sidecar
#   backup-pull   [DATE]            rsync a backup snapshot back to the dev box
#   restore       REMOTE_PATH       sidecar restore + restart
#
# Global flags:
#   --dry-run       print commands instead of running them
#   -h | --help     this help (or, after a subcommand, that subcommand's help)
#
# Configuration: remote.env at the repo root. Copy from remote.env.example.
#
# Exit codes:
#   0   success
#   1   subcommand error (build failed, deploy failed, health timeout, …)
#   2   usage error (unknown subcommand, missing required env, lock held)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

print_main_help() {
    awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
}

# ---------- Environment loading ----------

REQUIRED_VARS=(REMOTE_HOST REMOTE_USER REMOTE_REPO_DIR REMOTE_PAGES_DIR REMOTE_BACKUP_DIR)

load_env() {
    if [[ ! -f remote.env ]]; then
        echo "remote.sh: remote.env not found in $(pwd)." >&2
        echo "           copy remote.env.example to remote.env and edit it." >&2
        exit 2
    fi
    set -a
    # shellcheck source=/dev/null
    . ./remote.env
    set +a

    local missing=()
    for v in "${REQUIRED_VARS[@]}"; do
        if [[ -z "${!v:-}" ]]; then
            missing+=("${v}")
        fi
    done
    if (( ${#missing[@]} > 0 )); then
        echo "remote.sh: required vars unset in remote.env: ${missing[*]}" >&2
        exit 2
    fi

    : "${SSH_CONTROL_DIR:=${HOME}/.ssh/cm}"
    : "${HEALTH_URL:=http://${REMOTE_HOST}:8080/api/health}"
    : "${HEALTH_TIMEOUT:=90}"
}

# ---------- Argument parsing (global flags) ----------

DRY_RUN=0

# Help with no args: print usage and exit before loading env, so users
# without a remote.env can still read --help.
if [[ $# -eq 0 || "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    print_main_help
    exit 0
fi

# Strip global flags
ARGS=()
while [[ $# -gt 0 ]]; do
    # shellcheck disable=SC2034  # DRY_RUN consumed by later tasks (deploy/bootstrap)
    case "$1" in
        --dry-run) DRY_RUN=1; shift ;;
        *) ARGS+=("$1"); shift ;;
    esac
done
set -- "${ARGS[@]}"

if [[ $# -eq 0 ]]; then
    print_main_help
    exit 0
fi

SUBCOMMAND="$1"; shift

# Per-subcommand --help is wired up inside each cmd_* function. We load
# env up-front because every real subcommand needs it; --help paths
# above already exited.
load_env

# ---------- Subcommand dispatch ----------

case "${SUBCOMMAND}" in
    *) echo "remote.sh: unknown subcommand: ${SUBCOMMAND}" >&2
       echo "           run: bin/remote.sh --help" >&2
       exit 2 ;;
esac
