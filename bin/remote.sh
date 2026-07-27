#!/usr/bin/env bash
#
# bin/remote.sh — ssh-driven admin tool for a remote Wikantik deployment.
# Wraps bin/container.sh on the remote host plus adds image transfer
# (docker save | ssh), rsync-based content sync, ControlMaster, and a
# deploy lock. See docs/superpowers/specs/2026-05-14-remote-container-admin-design.md.
#
# Subcommands:
#   bootstrap                       first-time remote setup (no up -d)
#   deploy   [--skip-build] [--health-timeout=N] [--pull TAG]
#                                   build, push image, up -d, health-poll, auto-rollback.
#                                   --pull TAG skips the local build AND the docker
#                                   save|ssh load transfer; the remote pulls
#                                   ghcr.io/jakefearsd/wikantik:TAG directly instead
#                                   (for a target with its own registry access, e.g.
#                                   a cloud VM — see REMOTE_ENV_FILE below).
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
# Override the file with REMOTE_ENV_FILE=path/to/other.env to drive a second
# target (e.g. a cloud VM) without touching the docker1 remote.env, e.g.:
#   REMOTE_ENV_FILE=remote-aws.env bin/remote.sh deploy --pull 2.4.0
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

# ENV_FILE — which remote.env-shaped file to load. REMOTE_ENV_FILE overrides
# the default so a second target (e.g. a cloud VM) can be driven with
# REMOTE_ENV_FILE=remote-aws.env bin/remote.sh ... without touching docker1's
# remote.env. Default unchanged: "remote.env".
ENV_FILE="${REMOTE_ENV_FILE:-remote.env}"

load_env() {
    if [[ ! -f "${ENV_FILE}" ]]; then
        echo "remote.sh: ${ENV_FILE} not found in $(pwd)." >&2
        echo "           copy remote.env.example to ${ENV_FILE} and edit it." >&2
        exit 2
    fi
    set -a
    # Force cwd-relative sourcing (not a PATH search) even when ENV_FILE is a
    # bare filename with no slash — `.`/source treats a slash-free argument as
    # a PATH lookup, same reason the original hardcoded path used "./remote.env".
    local source_path="${ENV_FILE}"
    case "${source_path}" in
        /*|*/*) ;;                       # absolute, or already has a directory component
        *) source_path="./${source_path}" ;;
    esac
    # shellcheck source=/dev/null
    . "${source_path}"
    set +a

    local missing=()
    for v in "${REQUIRED_VARS[@]}"; do
        if [[ -z "${!v:-}" ]]; then
            missing+=("${v}")
        fi
    done
    if (( ${#missing[@]} > 0 )); then
        echo "remote.sh: required vars unset in ${ENV_FILE}: ${missing[*]}" >&2
        exit 2
    fi

    : "${SSH_CONTROL_DIR:=${HOME}/.ssh/cm}"
    : "${HEALTH_URL:=http://${REMOTE_HOST}:8080/api/health}"
    : "${HEALTH_TIMEOUT:=90}"
    # Registry image prefix for `deploy --pull TAG` (remote-side docker pull).
    # Default matches bin/deploy-release.sh's WIKANTIK_IMAGE convention.
    : "${WIKANTIK_IMAGE:=ghcr.io/jakefearsd/wikantik}"
    # How many wikantik:X.Y.Z version tags to keep on the remote. Version tags
    # are what keep old releases addressable (and safe from `docker image
    # prune`) so there is always something concrete to roll back to.
    : "${ROLLBACK_KEEP:=5}"
}

# Temp tag holding the pre-deploy image between steps 5 and 6a. Never promoted
# to :rollback until the new image's ID is known to differ — see cmd_deploy.
PREV_TAG="wikantik:__deploy_prev"

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

# _local_env_file — the local file shipped to the remote as .env. A prod-only
# .env.prod wins over .env, so prod deploys never require overwriting the dev
# .env with prod values. Echoes nothing if neither file exists.
_local_env_file() {
    if [[ -f .env.prod ]]; then printf '%s\n' .env.prod
    elif [[ -f .env ]]; then printf '%s\n' .env
    fi
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

# _validate_pull_tag TAG — enforce the Docker tag grammar before TAG is
# interpolated into the remote ssh command string. Anchored full-string
# match, so whitespace/newlines and shell metacharacters are rejected
# outright (an embedded newline would make printf %q emit $'...' bashisms
# that a non-bash remote login shell mishandles). Same precedent as
# bin/deploy-release.sh's VERSION regex check.
_validate_pull_tag() {
    if [[ ! "$1" =~ ^[A-Za-z0-9_][A-Za-z0-9._-]{0,127}$ ]]; then
        echo "deploy --pull: invalid tag ${1@Q} — must match the Docker tag grammar" >&2
        echo "               [A-Za-z0-9_][A-Za-z0-9._-]{0,127} (no whitespace, newlines," >&2
        echo "               or shell metacharacters)." >&2
        exit 2
    fi
}

cmd_deploy() {
    local skip_build=0
    local health_timeout="${HEALTH_TIMEOUT}"
    local pull_tag=""

    _subcommand_help "${1:-}" <<EOF || return 0
deploy — build locally, push image over ssh, up -d on remote, health-poll, auto-rollback on failure.

Usage: bin/remote.sh [--dry-run] deploy [--skip-build] [--health-timeout=N] [--pull TAG]

Options:
  --skip-build           skip mvn + docker compose build (use existing wikantik:latest)
  --health-timeout=N     seconds to wait for /api/health (default: ${HEALTH_TIMEOUT}, from ${ENV_FILE})
  --pull TAG             skip the local build AND the docker save|ssh load transfer;
                         instead the REMOTE runs
                         'docker pull ${WIKANTIK_IMAGE}:TAG && docker tag ... wikantik:latest'
                         directly. For a target with its own registry access (e.g. a
                         cloud VM reached via REMOTE_ENV_FILE=remote-aws.env). Implies
                         --skip-build; TAG is required and must match the Docker tag
                         grammar [A-Za-z0-9_][A-Za-z0-9._-]{0,127} (both validated
                         before any remote contact).

Flow (default / --skip-build):
  1. mvn clean install -T 1C -DskipITs   (unless --skip-build)
  2. docker compose build wikantik
  3. flock --nonblock on the remote
  4. rsync compose + .env to REMOTE_REPO_DIR
  5. snapshot remote wikantik:latest under a temp tag  (silent on first deploy)
  6. docker save | ssh 'docker load'
 6a. promote the snapshot to :rollback ONLY if the new image differs, then tag
     wikantik:<version> from the image label and prune to ROLLBACK_KEEP (${ROLLBACK_KEEP})
  7. container.sh -e prod up -d
 7a. verify the running container's image == wikantik:latest (fails a no-op deploy)
  8. poll HEALTH_URL every 3s up to --health-timeout
  9. on failure: re-promote :rollback, print last 50 wikantik log lines, exit 1

Flow (--pull TAG) — steps 1+2+6 above are replaced by a single remote pull+tag:
  3. flock --nonblock on the remote
  4. rsync compose + .env to REMOTE_REPO_DIR
  5. snapshot remote wikantik:latest under a temp tag  (silent on first deploy)
  6. remote: docker pull ${WIKANTIK_IMAGE}:TAG && docker tag ${WIKANTIK_IMAGE}:TAG wikantik:latest
 6a. promote the snapshot to :rollback ONLY if the new image differs, then tag
     wikantik:<version> from the image label and prune to ROLLBACK_KEEP (${ROLLBACK_KEEP})
  7. container.sh -e prod up -d
 7a. verify the running container's image == wikantik:latest (fails a no-op deploy)
  8. poll HEALTH_URL every 3s up to --health-timeout
  9. on failure: re-promote :rollback, print last 50 wikantik log lines, exit 1

Why 5/6a are split: tagging :rollback before the load made a repeat deploy of
the same image overwrite the only rollback target with the image you would need
to roll back *from*. IDs are compared remote-side because docker save|load
recomputes the image ID across differing storage backends.
EOF

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --skip-build) skip_build=1; shift ;;
            --health-timeout=*) health_timeout="${1#*=}"; shift ;;
            --health-timeout)   health_timeout="$2"; shift 2 ;;
            --pull=*)
                pull_tag="${1#*=}"
                if [[ -z "${pull_tag}" ]]; then
                    echo "deploy --pull: missing <tag> argument." >&2
                    exit 2
                fi
                _validate_pull_tag "${pull_tag}"
                shift ;;
            --pull)
                if [[ -z "${2:-}" || "${2}" == -* ]]; then
                    echo "deploy --pull: missing <tag> argument." >&2
                    exit 2
                fi
                pull_tag="$2"
                _validate_pull_tag "${pull_tag}"
                shift 2 ;;
            *) echo "deploy: unknown flag: $1" >&2; exit 2 ;;
        esac
    done

    # ---------- 1+2: local build ----------
    if [[ -n "${pull_tag}" ]]; then
        skip_build=1
        echo "remote.sh: --pull ${pull_tag} set; skipping local build — ${REMOTE_HOST} will pull ${WIKANTIK_IMAGE}:${pull_tag} directly."
    fi
    if [[ "${skip_build}" -eq 0 ]]; then
        _run mvn clean install -T 1C -DskipITs
        # Build the image through the container.sh facade so there is a
        # single source of truth for the compose invocation. The wikantik
        # build context lives in the base docker-compose.yml.
        _run bin/container.sh build wikantik
    elif [[ -z "${pull_tag}" ]]; then
        echo "remote.sh: --skip-build set; reusing wikantik:latest from local docker daemon."
    fi

    # ---------- 3: acquire lock ----------
    _acquire_deploy_lock

    # ---------- 4: rsync compose + .env ----------
    _rsync -avz --update --chmod=F644 \
        docker-compose.yml docker-compose.prod.yml \
        "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_REPO_DIR}/"
    local env_src
    env_src="$(_local_env_file)"
    if [[ -n "${env_src}" ]]; then
        _rsync -avz --chmod=F600 "${env_src}" "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_REPO_DIR}/.env"
    fi

    # ---------- 5: snapshot the prior image (silent on first deploy) ----------
    # NOT :rollback yet. Tagging :rollback here unconditionally meant a repeat
    # deploy of the same image copied the just-deployed image onto :rollback,
    # destroying the only rollback target — the image you would need to roll
    # back *from*. Park it under a temp tag; step 6a promotes it only if the
    # newly-loaded image actually differs.
    _ssh "docker tag wikantik:latest ${PREV_TAG} 2>/dev/null || true"

    # ---------- 6: get the new image onto the remote ----------
    if [[ -n "${pull_tag}" ]]; then
        # --pull TAG: the remote already has registry access, so pull directly
        # instead of streaming the image over ssh. Retag to wikantik:latest so
        # every step downstream (up -d, rollback re-promote) is unchanged.
        local remote_image="${WIKANTIK_IMAGE}:${pull_tag}"
        _ssh "docker pull $(printf '%q' "${remote_image}") && docker tag $(printf '%q' "${remote_image}") wikantik:latest"
    elif [[ "${DRY_RUN}" -eq 1 ]]; then
        # No gzip — 1–10 Gb LAN, CPU > wire-time at compress=1. Reachable
        # in dry-run by composing the commands and routing through _run.
        echo "[dry-run] docker save wikantik:latest | ssh ... 'docker load'"
        # Emit a faux load line for tests/grep:
        echo "[dry-run] (remote) docker load"
    else
        local ssh_inline="ssh -o ControlMaster=auto -o ControlPath=${SSH_CONTROL_DIR}/%C -o ControlPersist=10m -o StrictHostKeyChecking=accept-new"
        if [[ -n "${SSH_KEY:-}" ]]; then ssh_inline+=" -i ${SSH_KEY}"; fi
        docker save wikantik:latest | ${ssh_inline} "${REMOTE_USER}@${REMOTE_HOST}" 'docker load'
    fi

    # ---------- 6a: decide the rollback target, tag + prune version tags ----------
    # Compares image IDs on the remote, after the load. Comparing local-vs-remote
    # IDs would be wrong: `docker save | docker load` recomputes the ID when the
    # two daemons use different storage backends, so an identical image can
    # arrive with a different ID.
    _ssh "set -e
prev=\$(docker image inspect -f '{{.Id}}' ${PREV_TAG} 2>/dev/null || true)
new=\$(docker image inspect -f '{{.Id}}' wikantik:latest 2>/dev/null || true)
if [ -z \"\${new}\" ]; then
    echo 'remote.sh: no wikantik:latest on the remote after load.' >&2; exit 1
fi
if [ -z \"\${prev}\" ]; then
    echo 'remote.sh: first deploy — no prior image, rollback target not set.'
elif [ \"\${prev}\" = \"\${new}\" ]; then
    echo 'remote.sh: same image redeployed; rollback target left unchanged.'
else
    docker tag ${PREV_TAG} wikantik:rollback
    echo \"remote.sh: rollback target set to the prior image (\${prev}).\"
fi
docker rmi ${PREV_TAG} >/dev/null 2>&1 || true
ver=\$(docker image inspect -f '{{index .Config.Labels \"org.opencontainers.image.version\"}}' wikantik:latest 2>/dev/null || true)
if [ -n \"\${ver}\" ]; then
    docker tag wikantik:latest \"wikantik:\${ver}\"
    echo \"remote.sh: tagged wikantik:\${ver}.\"
    # Retention: keep the newest ROLLBACK_KEEP semver tags, drop the rest.
    docker images --format '{{.Tag}}' wikantik 2>/dev/null \
        | grep -E '^[0-9]+\.[0-9]+\.[0-9]+\$' | sort -Vr | tail -n +\$((${ROLLBACK_KEEP} + 1)) \
        | while read -r old; do docker rmi \"wikantik:\${old}\" >/dev/null 2>&1 || true; done
fi"

    # ---------- 7: up -d via remote container.sh ----------
    _ssh "cd $(printf '%q' "${REMOTE_REPO_DIR}") && bin/container.sh -e prod up -d"

    # ---------- 7a: prove the running container IS the image we just shipped ----------
    # The health poll below only proves *something* healthy is listening — it
    # passes trivially when compose decides not to recreate and the previous
    # container stays up. That is how a no-op deploy reported success.
    _ssh "set -e
cid=\$(cd $(printf '%q' "${REMOTE_REPO_DIR}") && bin/container.sh -e prod ps -q wikantik)
if [ -z \"\${cid}\" ]; then
    echo 'remote.sh: no running wikantik container after up -d.' >&2; exit 1
fi
running=\$(docker inspect -f '{{.Image}}' \"\${cid}\")
want=\$(docker image inspect -f '{{.Id}}' wikantik:latest)
if [ \"\${running}\" != \"\${want}\" ]; then
    echo \"remote.sh: deployed image is not the running image (running \${running}, expected \${want}) — the container was not recreated.\" >&2
    exit 1
fi
echo 'remote.sh: verified the running container is the deployed image.'"

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
rollback — re-promote a prior image to wikantik:latest, force-recreate the service.

Usage: bin/remote.sh [--dry-run] rollback [--to X.Y.Z]

Without --to, re-promotes wikantik:rollback (the image displaced by the last
deploy that actually changed the running image). With --to, re-promotes the
named wikantik:X.Y.Z version tag — deploy retains the newest ROLLBACK_KEEP
(default 5) of those, so you can step back further than one release and can
see what you are choosing.

Fails if the requested image does not exist on the remote, listing the version
tags that ARE available. If none are, recovery is manual: re-deploy a known-good
build or restore from backup. Acquires the same deploy lock as `deploy` and
`restore`.
EOF

    local target="wikantik:rollback" to_version=""
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --to=*) to_version="${1#*=}"; shift ;;
            --to)
                if [[ -z "${2:-}" || "${2}" == -* ]]; then
                    echo "rollback --to: missing <X.Y.Z> argument." >&2
                    exit 2
                fi
                to_version="$2"; shift 2 ;;
            *) echo "rollback: unknown flag: $1" >&2; exit 2 ;;
        esac
    done
    if [[ -n "${to_version}" ]]; then
        # Validate before it reaches a remote shell — this string is interpolated
        # into an ssh command.
        if [[ ! "${to_version}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
            echo "rollback --to: '${to_version}' is not a X.Y.Z version." >&2
            exit 2
        fi
        target="wikantik:${to_version}"
    fi

    _acquire_deploy_lock
    _ssh "if ! docker image inspect $(printf '%q' "${target}") >/dev/null 2>&1; then
    echo 'no ${target} image on ${REMOTE_HOST} — nothing to roll back to.' >&2
    echo 'available wikantik tags:' >&2
    docker images --format '  {{.Repository}}:{{.Tag}}' wikantik >&2 || true
    exit 1
fi"
    # Preserve a way back out of the rollback itself: the image being replaced
    # becomes the new :rollback, unless we are re-promoting :rollback already.
    if [[ -n "${to_version}" ]]; then
        _ssh "docker tag wikantik:latest wikantik:rollback 2>/dev/null || true"
    fi
    _ssh "docker tag $(printf '%q' "${target}") wikantik:latest"
    _ssh "cd $(printf '%q' "${REMOTE_REPO_DIR}") && bin/container.sh -e prod up -d --force-recreate wikantik"
    echo "Rollback complete on ${REMOTE_HOST} (promoted ${target})."
}

cmd_bootstrap() {
    _subcommand_help "${1:-}" <<'EOF' || return 0
bootstrap — first-time remote setup. Idempotent; safe to re-run.

Usage: bin/remote.sh bootstrap [--dry-run]

Steps:
  1. Verify `docker` + `docker compose` exist AND the daemon is reachable
     by REMOTE_USER on REMOTE_HOST.
  2. Create REMOTE_REPO_DIR, REMOTE_PAGES_DIR, REMOTE_BACKUP_DIR on the remote.
  3. Create local SSH_CONTROL_DIR (mode 0700) if absent.
  4. rsync docker-compose.yml + docker-compose.prod.yml + docker/ + bin/ and
     the local env file (.env.prod if present, else .env) to REMOTE_REPO_DIR
     as .env.

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
    # Daemon reachability — not just the binary. A user who can run the docker
    # CLI but is not in the 'docker' group passes the checks above yet fails
    # every real command later with a docker.sock permission error.
    _ssh "docker info >/dev/null 2>&1 || { echo 'cannot reach the Docker daemon on ${REMOTE_HOST} as ${REMOTE_USER} — add the user to the docker group:  sudo usermod -aG docker ${REMOTE_USER}  (then start a fresh login session), and re-run bootstrap.' >&2; exit 2; }"

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

    local env_src
    env_src="$(_local_env_file)"
    if [[ -n "${env_src}" ]]; then
        _rsync -avz --chmod=F600 "${env_src}" "${REMOTE_USER}@${REMOTE_HOST}:${REMOTE_REPO_DIR}/.env"
        echo "remote.sh: shipped ${env_src} as ${REMOTE_HOST}:${REMOTE_REPO_DIR}/.env"
    else
        echo "remote.sh: warning — no local .env.prod or .env; remote will not start without one." >&2
        echo "           Create .env.prod locally (copy from .env.example) and re-run bootstrap." >&2
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
