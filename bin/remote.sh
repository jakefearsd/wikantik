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

# ---------- Internal helpers ----------

# _ssh ARGS...      — run a command on the remote with ControlMaster
# _ssh -t ARGS...   — same, with a tty (for interactive shell/psql)
_ssh_opts() {
    local opts=(
        -o "ControlMaster=auto"
        -o "ControlPath=${SSH_CONTROL_DIR}/%C"
        -o "ControlPersist=10m"
        -o "StrictHostKeyChecking=accept-new"
    )
    if [[ -n "${SSH_KEY:-}" ]]; then
        opts+=(-i "${SSH_KEY}")
    fi
    printf '%s\0' "${opts[@]}"
}

_ssh() {
    mkdir -p "${SSH_CONTROL_DIR}" && chmod 700 "${SSH_CONTROL_DIR}"
    local opts=()
    while IFS= read -r -d '' opt; do opts+=("${opt}"); done < <(_ssh_opts)
    _run ssh "${opts[@]}" "${REMOTE_USER}@${REMOTE_HOST}" "$@"
}

# _rsync ARGS...    — rsync with ControlMaster-aware ssh
_rsync() {
    mkdir -p "${SSH_CONTROL_DIR}" && chmod 700 "${SSH_CONTROL_DIR}"
    local ssh_inline="ssh -o ControlMaster=auto -o ControlPath=${SSH_CONTROL_DIR}/%C -o ControlPersist=10m -o StrictHostKeyChecking=accept-new"
    if [[ -n "${SSH_KEY:-}" ]]; then
        ssh_inline+=" -i ${SSH_KEY}"
    fi
    _run rsync -e "${ssh_inline}" "$@"
}

# _run CMD ARGS...  — execute (or, under --dry-run, print) the command.
_run() {
    if [[ "${DRY_RUN}" -eq 1 ]]; then
        printf '[dry-run]'
        printf ' %q' "$@"
        printf '\n'
        return 0
    fi
    "$@"
}

# _acquire_deploy_lock — non-blocking probe of the deploy lock. Fails with
# exit 2 if another deploy/rollback/restore is in progress. Used by all
# three state-mutating top-level subcommands.
#
# Caveat: the probe holds the lock only for the duration of this single
# ssh call. Subsequent ssh calls inside the same subcommand do not re-
# acquire. This is sufficient for the common "two terminals at once"
# case the spec calls out; it does not protect against finer races,
# which are acceptable in a sole-developer environment.
_acquire_deploy_lock() {
    local lockfile="${REMOTE_REPO_DIR}/.deploy.lock"
    if ! _ssh "mkdir -p $(printf '%q' "${REMOTE_REPO_DIR}") && flock --nonblock --conflict-exit-code 75 ${lockfile} -c 'true'"; then
        echo "remote.sh: deploy lock held on ${REMOTE_HOST} (${lockfile})." >&2
        echo "           Wait for the running operation, or remove the lockfile" >&2
        echo "           if you are certain no deploy/rollback/restore is in progress." >&2
        exit 2
    fi
}

# Selftest subcommand — visible only via dry-run; greps in tests.
cmd_selftest() {
    _ssh true
}

# ---------- Subcommand dispatch ----------

case "${SUBCOMMAND}" in
    __selftest) cmd_selftest "$@" ;;
    *) echo "remote.sh: unknown subcommand: ${SUBCOMMAND}" >&2
       echo "           run: bin/remote.sh --help" >&2
       exit 2 ;;
esac
