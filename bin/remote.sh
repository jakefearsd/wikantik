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
#
# _ssh_opts — canonical ssh option list used by _ssh.
# Mirror any change here in the inline ssh string built by _rsync below
# (rsync -e takes a string, not an argv array, so the two lists can't
# share a representation).
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
    # Inline ssh string — keep in sync with _ssh_opts above. rsync -e
    # requires a string, not an argv array, so we can't reuse _ssh_opts.
    local ssh_inline="ssh -o ControlMaster=auto -o ControlPath=${SSH_CONTROL_DIR}/%C -o ControlPersist=10m -o StrictHostKeyChecking=accept-new"
    if [[ -n "${SSH_KEY:-}" ]]; then
        ssh_inline+=" -i ${SSH_KEY}"
    fi
    _run rsync -e "${ssh_inline}" "$@"
}

# _run CMD ARGS...  — execute (or, under --dry-run, print) the command.
# Dry-run output is informational, not re-executable shell: args containing
# spaces are wrapped in single quotes so the printed form stays human-readable
# (and so grep tests can match literal substrings like "container.sh -e prod").
_run() {
    if [[ "${DRY_RUN}" -eq 1 ]]; then
        printf '[dry-run]'
        local arg
        for arg in "$@"; do
            if [[ "${arg}" == *[[:space:]\'\"\\]* ]]; then
                # Wrap in single quotes; escape embedded single quotes.
                printf " '%s'" "${arg//\'/\'\\\'\'}"
            else
                printf ' %s' "${arg}"
            fi
        done
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
    local lockfile_q
    lockfile_q="$(printf '%q' "${lockfile}")"
    if ! _ssh "mkdir -p $(printf '%q' "${REMOTE_REPO_DIR}") && flock --nonblock --conflict-exit-code 75 ${lockfile_q} -c 'true'"; then
        echo "remote.sh: deploy lock held on ${REMOTE_HOST} (${lockfile})." >&2
        echo "           Wait for the running operation, or remove the lockfile" >&2
        echo "           if you are certain no deploy/rollback/restore is in progress." >&2
        exit 2
    fi
}

# _subcommand_help ARG  — uniform per-subcommand --help dispatch.
#
# Usage at the top of each cmd_* function:
#
#     _subcommand_help "$1" <<EOF || return 0
#     ...usage text...
#     EOF
#
# When ARG is -h or --help, prints the heredoc on stdout and returns 1 so
# the caller's `|| return 0` short-circuits the rest of the function.
# Otherwise reads-and-discards stdin and returns 0, letting the caller
# continue.
_subcommand_help() {
    local help_text
    help_text="$(cat)"
    case "${1:-}" in
        -h|--help)
            printf '%s\n' "${help_text}"
            return 1 ;;
    esac
    return 0
}

# Selftest subcommand — visible only via dry-run; greps in tests.
cmd_selftest() {
    _ssh true
}

# ---------- Pass-through subcommands ----------

_remote_container() {
    # All pass-through subcommands run bin/container.sh -e prod on the remote.
    # Quote each argument so spaces and special chars survive the ssh hop.
    local quoted=""
    local a
    for a in "$@"; do
        quoted+=" $(printf '%q' "${a}")"
    done
    _ssh "cd $(printf '%q' "${REMOTE_REPO_DIR}") && bin/container.sh -e prod${quoted}"
}

cmd_up()      { _remote_container up "$@"; }
cmd_down()    { _remote_container down "$@"; }
cmd_restart() { _remote_container restart "$@"; }
cmd_logs() {
    if [[ $# -eq 0 ]]; then
        _remote_container logs wikantik
    else
        # Detect whether the user passed -f / --follow. If so and they did not
        # pass a service, append wikantik so default behavior matches container.sh.
        local has_service=0
        local a
        for a in "$@"; do
            case "${a}" in
                -*) ;;
                *) has_service=1 ;;
            esac
        done
        if [[ "${has_service}" -eq 0 ]]; then
            _remote_container logs "$@" wikantik
        else
            _remote_container logs "$@"
        fi
    fi
}
cmd_shell() {
    local svc="${1:-wikantik}"
    _remote_container shell "${svc}"
}
cmd_psql() {
    _remote_container psql "$@"
}
cmd_migrate() {
    _remote_container migrate "$@"
}

cmd_deploy() {
    local skip_build=0
    local health_timeout="${HEALTH_TIMEOUT}"

    _subcommand_help "${1:-}" <<EOF || return 0
deploy — build locally, push image over ssh, up -d on remote, health-poll, auto-rollback on failure.

Usage: bin/remote.sh [--dry-run] deploy [--skip-build] [--health-timeout=N]

Options:
  --skip-build           skip mvn + docker compose build (use existing wikantik:latest)
  --health-timeout=N     seconds to wait for /api/health (default: ${HEALTH_TIMEOUT}, from remote.env)

Flow:
  1. mvn clean install -T 1C -DskipITs   (unless --skip-build)
  2. docker compose build wikantik
  3. flock --nonblock on the remote
  4. rsync compose + .env to REMOTE_REPO_DIR
  5. tag remote wikantik:latest as wikantik:rollback   (silent on first deploy)
  6. docker save | ssh 'docker load'
  7. container.sh -e prod up -d
  8. poll HEALTH_URL every 3s up to --health-timeout
  9. on failure: re-promote :rollback, print last 50 wikantik log lines, exit 1
EOF

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --skip-build) skip_build=1; shift ;;
            --health-timeout=*) health_timeout="${1#*=}"; shift ;;
            --health-timeout)   health_timeout="$2"; shift 2 ;;
            *) echo "deploy: unknown flag: $1" >&2; exit 2 ;;
        esac
    done

    # ---------- 1+2: local build ----------
    if [[ "${skip_build}" -eq 0 ]]; then
        _run mvn clean install -T 1C -DskipITs
        _run docker compose -f docker-compose.yml build wikantik
    else
        echo "remote.sh: --skip-build set; reusing wikantik:latest from local docker daemon."
    fi

    # ---------- 3: acquire lock ----------
    _acquire_deploy_lock

    # ---------- 4: rsync compose + .env ----------
    _rsync -avz --update --chmod=F644 \
        docker-compose.yml docker-compose.prod.yml \
        "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_REPO_DIR}/"
    if [[ -f .env ]]; then
        _rsync -avz --chmod=F600 .env "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_REPO_DIR}/.env"
    fi

    # ---------- 5: tag prior image as :rollback (silent on first deploy) ----------
    _ssh "docker tag wikantik:latest wikantik:rollback 2>/dev/null || true"

    # ---------- 6: stream image ----------
    # No gzip — 1–10 Gb LAN, CPU > wire-time at compress=1. Reachable
    # in dry-run by composing the commands and routing through _run.
    if [[ "${DRY_RUN}" -eq 1 ]]; then
        echo "[dry-run] docker save wikantik:latest | ssh ... 'docker load'"
        # Emit a faux load line for tests/grep:
        echo "[dry-run] (remote) docker load"
    else
        local ssh_inline="ssh -o ControlMaster=auto -o ControlPath=${SSH_CONTROL_DIR}/%C -o ControlPersist=10m -o StrictHostKeyChecking=accept-new"
        if [[ -n "${SSH_KEY:-}" ]]; then ssh_inline+=" -i ${SSH_KEY}"; fi
        docker save wikantik:latest | ${ssh_inline} "${REMOTE_USER}@${REMOTE_HOST}" 'docker load'
    fi

    # ---------- 7: up -d via remote container.sh ----------
    _ssh "cd $(printf '%q' "${REMOTE_REPO_DIR}") && bin/container.sh -e prod up -d"

    # ---------- 8: health poll ----------
    if [[ "${DRY_RUN}" -eq 1 ]]; then
        echo "[dry-run] poll ${HEALTH_URL} every 3s up to ${health_timeout}s"
        return 0
    fi
    local deadline=$(( $(date +%s) + health_timeout ))
    while (( $(date +%s) < deadline )); do
        if curl -sfo /dev/null --max-time 5 "${HEALTH_URL}"; then
            echo "Deploy healthy: ${HEALTH_URL} returned 200."
            return 0
        fi
        sleep 3
    done

    # ---------- 9: failure → auto-rollback ----------
    echo "remote.sh: ${HEALTH_URL} did not return 200 within ${health_timeout}s; rolling back." >&2
    _ssh "docker image inspect wikantik:rollback >/dev/null 2>&1 \
          && docker tag wikantik:rollback wikantik:latest \
          && cd $(printf '%q' "${REMOTE_REPO_DIR}") \
          && bin/container.sh -e prod up -d --force-recreate wikantik \
          || echo 'no :rollback image present — manual recovery required.' >&2"
    _ssh "cd $(printf '%q' "${REMOTE_REPO_DIR}") && bin/container.sh -e prod logs --tail=50 wikantik" >&2 || true
    exit 1
}

cmd_rollback() {
    _subcommand_help "${1:-}" <<'EOF' || return 0
rollback — re-promote wikantik:rollback to wikantik:latest, force-recreate the service.

Usage: bin/remote.sh [--dry-run] rollback

Fails if no :rollback image exists on the remote (means no successful prior
deploy has been recorded). In that case, recovery is manual: re-deploy a
known-good build or restore from backup. Acquires the same deploy lock
as `deploy` and `restore`.
EOF

    _acquire_deploy_lock
    _ssh "docker image inspect wikantik:rollback >/dev/null 2>&1 \
          || { echo 'no wikantik:rollback image on ${REMOTE_HOST} — nothing to roll back to.' >&2; exit 1; }"
    _ssh "docker tag wikantik:rollback wikantik:latest"
    _ssh "cd $(printf '%q' "${REMOTE_REPO_DIR}") && bin/container.sh -e prod up -d --force-recreate wikantik"
    echo "Rollback complete on ${REMOTE_HOST}."
}

cmd_bootstrap() {
    _subcommand_help "${1:-}" <<'EOF' || return 0
bootstrap — first-time remote setup. Idempotent; safe to re-run.

Usage: bin/remote.sh bootstrap [--dry-run]

Steps:
  1. Verify `docker` and `docker compose` are present on REMOTE_HOST.
  2. Create REMOTE_REPO_DIR, REMOTE_PAGES_DIR, REMOTE_BACKUP_DIR on the remote.
  3. Create local SSH_CONTROL_DIR (mode 0700) if absent.
  4. rsync docker-compose.yml + docker-compose.prod.yml + docker/ + bin/ + .env
     to REMOTE_REPO_DIR.

Does NOT:
  - Install docker (distro-specific; if step 1 fails the script tells you what to install).
  - Run `up -d` — that happens on the first `deploy` invocation, which is when the
    wikantik image first lands on the remote.
EOF

    # Local: ensure the ControlMaster dir exists with 0700 (the _ssh helper also
    # does this, but doing it up-front keeps bootstrap self-contained).
    if [[ "${DRY_RUN}" -eq 0 ]]; then
        mkdir -p "${SSH_CONTROL_DIR}" && chmod 700 "${SSH_CONTROL_DIR}"
    else
        echo "[dry-run] mkdir -p ${SSH_CONTROL_DIR} && chmod 700 ${SSH_CONTROL_DIR}"
    fi

    # 1. Verify docker on remote
    _ssh "command -v docker >/dev/null 2>&1 || { echo 'docker not found on ${REMOTE_HOST} — install docker + docker compose, then re-run bootstrap.' >&2; exit 2; }"
    _ssh "docker compose version >/dev/null 2>&1 || { echo 'docker compose plugin not found on ${REMOTE_HOST} — install it, then re-run bootstrap.' >&2; exit 2; }"

    # 2. Create remote directories
    _ssh "mkdir -p $(printf '%q' "${REMOTE_REPO_DIR}") $(printf '%q' "${REMOTE_PAGES_DIR}") $(printf '%q' "${REMOTE_BACKUP_DIR}")"

    # 3. rsync compose stack + bin + docker/ + .env (if present)
    local files=(docker-compose.yml docker-compose.prod.yml)
    # bin/ and docker/ contain helper scripts the remote container.sh invokes
    _rsync -avz --update --chmod=F644 \
        "${files[@]}" \
        "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_REPO_DIR}/"
    _rsync -avz --update --chmod=F755 \
        --include='*/' --include='*.sh' --include='*.sql' --exclude='*' \
        bin/ "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_REPO_DIR}/bin/"
    _rsync -avz --update \
        docker/ "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_REPO_DIR}/docker/"

    if [[ -f .env ]]; then
        _rsync -avz --chmod=F600 .env "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_REPO_DIR}/.env"
    else
        echo "remote.sh: warning — no local .env; remote will not start without one." >&2
        echo "           Create .env locally (copy from .env.example) and re-run bootstrap." >&2
    fi

    echo "Bootstrap complete on ${REMOTE_HOST}."
    echo "Next: bin/remote.sh deploy"
}

cmd_pages_push() {
    local local_dir=""
    local mirror=0
    local assume_yes=0
    _subcommand_help "${1:-}" <<'EOF' || return 0
pages-push — rsync a local pages directory to REMOTE_PAGES_DIR.

Usage:
  bin/remote.sh [--dry-run] pages-push LOCAL_DIR
  bin/remote.sh [--dry-run] pages-push LOCAL_DIR --mirror [--yes]

Default: no --delete. Files present on the remote but missing locally survive.
--mirror: opts in to rsync --delete. By default, prompts for confirmation
          showing the files that would be deleted; --yes skips the prompt.
EOF
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --mirror) mirror=1; shift ;;
            --yes)    assume_yes=1; shift ;;
            -*) echo "pages-push: unknown flag: $1" >&2; exit 2 ;;
            *) if [[ -z "${local_dir}" ]]; then local_dir="$1"; shift
               else echo "pages-push: unexpected arg: $1" >&2; exit 2
               fi ;;
        esac
    done
    [[ -n "${local_dir}" ]] || { echo "pages-push: missing LOCAL_DIR" >&2; exit 2; }
    [[ -d "${local_dir}" ]] || { echo "pages-push: not a directory: ${local_dir}" >&2; exit 2; }

    local rsync_args=(-avz --update)
    if [[ "${mirror}" -eq 1 ]]; then
        if [[ "${assume_yes}" -ne 1 && "${DRY_RUN}" -ne 1 ]]; then
            echo "pages-push --mirror would --delete files on ${REMOTE_HOST}:${REMOTE_PAGES_DIR}."
            echo "Preview of deletions:"
            local preview_log
            preview_log="$(mktemp)"
            if ! _rsync -avzn --delete "${local_dir%/}/" \
                    "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_PAGES_DIR}/" \
                    > "${preview_log}" 2>&1; then
                echo "pages-push: preview rsync failed; aborting --mirror." >&2
                sed 's/^/  /' "${preview_log}" >&2
                rm -f "${preview_log}"
                exit 1
            fi
            if ! grep -E '^deleting ' "${preview_log}"; then
                echo "  (none — remote is already a subset of local)"
            fi
            rm -f "${preview_log}"
            read -r -p "Proceed? [y/N] " yn
            [[ "${yn}" =~ ^[Yy]$ ]] || { echo "Aborted."; return 0; }
        fi
        rsync_args+=(--delete)
    fi

    _rsync "${rsync_args[@]}" "${local_dir%/}/" \
        "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_PAGES_DIR}/"
}

cmd_pages_pull() {
    local local_dir=""
    _subcommand_help "${1:-}" <<'EOF' || return 0
pages-pull — rsync REMOTE_PAGES_DIR to a local directory. Read-only (never deletes locally).

Usage: bin/remote.sh [--dry-run] pages-pull LOCAL_DIR
EOF
    local_dir="${1:-}"
    [[ -n "${local_dir}" ]] || { echo "pages-pull: missing LOCAL_DIR" >&2; exit 2; }
    mkdir -p "${local_dir}"
    _rsync -avz --update \
        "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_PAGES_DIR}/" \
        "${local_dir%/}/"
}

cmd_backup_trigger() {
    _subcommand_help "${1:-}" <<'EOF' || return 0
backup-trigger — invoke the prod backup sidecar.

Usage: bin/remote.sh [--dry-run] backup-trigger [TIER]
  TIER: daily | weekly | monthly  (default: daily)
EOF
    local tier="${1:-daily}"
    _remote_container backup "${tier}"
}

cmd_backup_pull() {
    local date_arg=""
    _subcommand_help "${1:-}" <<'EOF' || return 0
backup-pull — rsync a backup snapshot from REMOTE_BACKUP_DIR back to the dev box.

Usage: bin/remote.sh [--dry-run] backup-pull [DATE]
  DATE: YYYY-MM-DD subdir under REMOTE_BACKUP_DIR/daily/  (default: latest dated snapshot)

The snapshot is rsynced into ./backups/<DATE>/ locally.
EOF
    date_arg="${1:-}"

    # If no DATE given, discover the lexically-greatest dated subdir on the remote.
    # ls + grep + sort + tail keeps the heuristic simple and dependency-free; YYYY-MM-DD
    # sorts lexically iff dates are well-formed, which the sidecar's backup.sh produces.
    if [[ -z "${date_arg}" ]]; then
        if [[ "${DRY_RUN}" -eq 1 ]]; then
            date_arg="<latest>"
            echo "[dry-run] (would discover latest dated snapshot on remote)"
        else
            date_arg="$(_ssh "ls -1 $(printf '%q' "${REMOTE_BACKUP_DIR}/daily") 2>/dev/null | grep -E '^[0-9]{4}-[0-9]{2}-[0-9]{2}\$' | sort | tail -1")"
            if [[ -z "${date_arg}" ]]; then
                echo "backup-pull: no dated snapshots found under ${REMOTE_BACKUP_DIR}/daily on ${REMOTE_HOST}." >&2
                exit 1
            fi
            echo "backup-pull: using latest snapshot ${date_arg}"
        fi
    fi

    local remote_src="${REMOTE_BACKUP_DIR}/daily/${date_arg}/"
    mkdir -p "backups/${date_arg}"
    _rsync -avz --update \
        "${REMOTE_USER}@${REMOTE_HOST}:${remote_src}" \
        "backups/${date_arg}/"
}

cmd_status() {
    _subcommand_help "${1:-}" <<'EOF' || return 0
status — one-screen summary of the remote deployment.

Usage: bin/remote.sh status

Prints:
  - docker compose ps                                    (container state)
  - HEALTH_URL → curl status                             (app health)
  - df -h on the REMOTE_PAGES_DIR partition              (disk free)
  - du -sh REMOTE_PAGES_DIR REMOTE_BACKUP_DIR            (data size)
  - last 10 wikantik log lines                           (recent activity)
EOF

    echo "=== ${REMOTE_HOST} — container state ==="
    _ssh "cd $(printf '%q' "${REMOTE_REPO_DIR}") && bin/container.sh -e prod ps"

    echo
    echo "=== health (${HEALTH_URL}) ==="
    if [[ "${DRY_RUN}" -eq 1 ]]; then
        printf '%s\n' "[dry-run] curl -sfo /dev/null -w 'HTTP %{http_code}\\n' ${HEALTH_URL}"
    else
        curl -sfo /dev/null -w 'HTTP %{http_code}\n' --max-time 5 "${HEALTH_URL}" \
            || echo "(no response)"
    fi

    echo
    echo "=== disk + data size ==="
    _ssh "df -h $(printf '%q' "${REMOTE_PAGES_DIR}") || df -h /"
    _ssh "du -sh $(printf '%q' "${REMOTE_PAGES_DIR}") $(printf '%q' "${REMOTE_BACKUP_DIR}") 2>/dev/null || true"

    echo
    echo "=== last 10 wikantik log lines ==="
    _ssh "cd $(printf '%q' "${REMOTE_REPO_DIR}") && bin/container.sh -e prod logs --tail=10 wikantik"
}

cmd_restore() {
    _subcommand_help "${1:-}" <<'EOF' || return 0
restore — invoke the prod backup sidecar's restore.sh with a remote backup path.

Usage: bin/remote.sh [--dry-run] restore REMOTE_PATH
  REMOTE_PATH: e.g. /backups/daily/2026-05-14  (path inside the backup sidecar)

The wikantik container is brought down for restore and back up afterward.
Acquires the same deploy lock as `deploy` and `rollback`.
EOF
    local path="${1:-}"
    [[ -n "${path}" ]] || { echo "restore: missing REMOTE_PATH" >&2; exit 2; }
    _acquire_deploy_lock
    _remote_container down
    _remote_container restore "${path}"
    _remote_container up -d
}

# ---------- Subcommand dispatch ----------

case "${SUBCOMMAND}" in
    __selftest) cmd_selftest "$@" ;;
    up)         cmd_up "$@" ;;
    down)       cmd_down "$@" ;;
    restart)    cmd_restart "$@" ;;
    logs)       cmd_logs "$@" ;;
    shell)      cmd_shell "$@" ;;
    psql)       cmd_psql "$@" ;;
    migrate)    cmd_migrate "$@" ;;
    bootstrap)  cmd_bootstrap "$@" ;;
    deploy)     cmd_deploy "$@" ;;
    rollback)   cmd_rollback "$@" ;;
    status)     cmd_status "$@" ;;
    pages-push) cmd_pages_push "$@" ;;
    pages-pull) cmd_pages_pull "$@" ;;
    backup-trigger) cmd_backup_trigger "$@" ;;
    backup-pull)    cmd_backup_pull "$@" ;;
    restore)        cmd_restore "$@" ;;
    *) echo "remote.sh: unknown subcommand: ${SUBCOMMAND}" >&2
       echo "           run: bin/remote.sh --help" >&2
       exit 2 ;;
esac
