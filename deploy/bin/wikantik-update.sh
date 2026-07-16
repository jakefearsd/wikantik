#!/usr/bin/env bash
#
# deploy/bin/wikantik-update.sh — pull-based update tool for a cloud VM
# running the Wikantik container stack (docker-compose.yml +
# docker-compose.cloud.yml). Installed by cloud-init onto VM images (see
# docs/superpowers/plans/2026-07-16-aws-gcp-deployment-readiness.md, Phase 2).
#
# Ports the discipline of bin/remote.sh's `deploy` flow (see that script's
# header) to a *local*, pull-only context: this script runs *on* the target
# VM itself, where the image already lives in a registry (GHCR) — so there
# is no local Maven/Docker build step and no `docker save | ssh docker load`
# transfer, unlike bin/remote.sh.
#
# Flow (remote.sh's 9 steps, minus the 2 that don't apply here — local build
# and the save/load transfer):
#   1. docker login ghcr.io   (only if GHCR_USER + GHCR_TOKEN are both set;
#      otherwise assume the daemon already has a working ghcr.io login, e.g.
#      a one-time `docker login` at VM provisioning, or a public image)
#   2. docker pull <image ref>          (the exact tag/ref being deployed)
#   3. tag the currently-running wikantik image as wikantik:rollback
#      (silent no-op on first deploy, matching bin/remote.sh's own
#      "docker tag wikantik:latest wikantik:rollback 2>/dev/null || true")
#   4. back up + update WIKANTIK_IMAGE= in the VM's .env
#   5. docker compose <files> <profiles> --env-file ENV_FILE up -d
#   6. poll HEALTH_URL every 3s up to HEALTH_TIMEOUT
#   7. on success: exit 0
#   8. on failure: restore the previous .env WIKANTIK_IMAGE, up -d again
#      (force-recreating just the wikantik service, matching bin/remote.sh's
#      rollback), print the last 50 wikantik log lines, exit 1
#
# Idempotent: re-running with the tag that is already deployed rewrites .env
# with the same value and re-runs `up -d`, which compose treats as a no-op
# (no config diff => no container recreation) as long as the stack is
# already healthy — a safe redeploy, not a hard requirement that nothing at
# all happens on disk (the .env backup file is still refreshed).
#
# Usage:
#   wikantik-update.sh [--dry-run] [--config FILE] <tag>
#   WIKANTIK_TAG=1.4.2 wikantik-update.sh [--dry-run]
#
#   <tag> is either a bare tag — combined with WIKANTIK_IMAGE_REPO below into
#   "<repo>:<tag>" — or a full image reference containing a '/' (used as-is,
#   so you can point at an entirely different registry/repo for one run).
#
# Configuration — every value below has a built-in default and can be set
# either in a config file (default /etc/wikantik-update.conf; override the
# path with WIKANTIK_UPDATE_CONF or --config FILE) or as an ambient
# environment variable. If the config file exists, it is sourced (plain
# shell assignments) and its values win for any variable it sets; anything
# it does NOT mention falls back to the ambient environment, then to the
# built-in default. This mirrors bin/remote.sh's remote.env, but the file
# is optional — a bare VM with no conf file still runs off exported env +
# defaults:
#
#   WIKANTIK_REPO_DIR          Directory holding docker-compose*.yml + .env.
#                               Default: /opt/wikantik
#   WIKANTIK_ENV_FILE           Path to the compose .env file.
#                               Default: ${WIKANTIK_REPO_DIR}/.env
#   WIKANTIK_COMPOSE_FILES       Space-separated -f file list, relative to
#                               WIKANTIK_REPO_DIR.
#                               Default: "docker-compose.yml docker-compose.cloud.yml"
#   WIKANTIK_COMPOSE_PROFILES    Space-separated --profile names, e.g.
#                               "caddy embeddings bundled-db". Default: "" (none)
#   WIKANTIK_SERVICE             Compose service name to retag/poll/tail logs for.
#                               Default: wikantik
#   WIKANTIK_IMAGE_REPO          Registry+repo prefix combined with a bare <tag>.
#                               Default: ghcr.io/jakefearsd/wikantik
#   HEALTH_URL                  Polled after `up -d`.
#                               Default: http://localhost:8080/api/health
#   HEALTH_TIMEOUT               Seconds to poll before rolling back.
#                               Default: 180
#   GHCR_USER / GHCR_TOKEN       If both set, `docker login ghcr.io` runs
#                               non-interactively before the pull. If either
#                               is unset, no login is attempted (ambient
#                               login assumed). Default: unset (skip login)
#
# Exit codes:
#   0   success (healthy within HEALTH_TIMEOUT, or --dry-run)
#   1   deploy failure — health check never passed; auto-rollback attempted
#   2   usage error (missing tag, bad flag, missing .env, missing repo dir)

set -euo pipefail

print_help() {
    awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
}

_err() {
    echo "wikantik-update.sh: $*" >&2
}

_usage_line() {
    echo "Usage: $(basename "$0") [--dry-run] [--config FILE] <tag>   (or set WIKANTIK_TAG)" >&2
}

# ---------- Argument parsing ----------
# Deliberately front-loaded: tag presence is validated here, before the
# config file is read and long before any docker/curl call, so a missing
# tag fails fast with zero side effects (mirrors bin/remote.sh's front-
# loaded validation for `deploy --pull`).

DRY_RUN=0
CONFIG_FILE="${WIKANTIK_UPDATE_CONF:-/etc/wikantik-update.conf}"
POSITIONAL=()

while [[ $# -gt 0 ]]; do
    case "$1" in
        -h|--help) print_help; exit 0 ;;
        --dry-run) DRY_RUN=1; shift ;;
        --config=*) CONFIG_FILE="${1#*=}"; shift ;;
        --config)
            if [[ -z "${2:-}" ]]; then
                _err "--config: missing FILE argument."
                exit 2
            fi
            CONFIG_FILE="$2"
            shift 2 ;;
        --) shift
            while [[ $# -gt 0 ]]; do POSITIONAL+=("$1"); shift; done
            break ;;
        -*) _err "unknown flag: $1"; _usage_line; exit 2 ;;
        *) POSITIONAL+=("$1"); shift ;;
    esac
done

if [[ "${#POSITIONAL[@]}" -gt 1 ]]; then
    _err "unexpected extra argument(s): ${POSITIONAL[*]:1}"
    _usage_line
    exit 2
fi

TAG="${POSITIONAL[0]:-${WIKANTIK_TAG:-}}"

if [[ -z "${TAG}" ]]; then
    _err "missing tag — pass it as an argument or set WIKANTIK_TAG."
    _usage_line
    exit 2
fi

# ---------- Configuration ----------

if [[ -f "${CONFIG_FILE}" ]]; then
    # shellcheck source=/dev/null
    . "${CONFIG_FILE}"
fi

: "${WIKANTIK_REPO_DIR:=/opt/wikantik}"
: "${WIKANTIK_ENV_FILE:=${WIKANTIK_REPO_DIR}/.env}"
: "${WIKANTIK_COMPOSE_FILES:=docker-compose.yml docker-compose.cloud.yml}"
: "${WIKANTIK_COMPOSE_PROFILES:=}"
: "${WIKANTIK_SERVICE:=wikantik}"
: "${WIKANTIK_IMAGE_REPO:=ghcr.io/jakefearsd/wikantik}"
: "${HEALTH_URL:=http://localhost:8080/api/health}"
: "${HEALTH_TIMEOUT:=180}"

# A bare tag is combined with WIKANTIK_IMAGE_REPO; a value that already looks
# like a full reference (contains '/') is used as-is.
if [[ "${TAG}" == *"/"* ]]; then
    NEW_IMAGE="${TAG}"
else
    NEW_IMAGE="${WIKANTIK_IMAGE_REPO}:${TAG}"
fi

# ---------- Helpers ----------

# _run CMD ARGS...  — execute (or, under --dry-run, print) the command.
_run() {
    if [[ "${DRY_RUN}" -eq 1 ]]; then
        printf '[dry-run]'
        local arg
        for arg in "$@"; do
            if [[ "${arg}" == *[[:space:]\'\"\\]* ]]; then
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

# _compose_args — builds the COMPOSE_ARGS array from WIKANTIK_COMPOSE_FILES /
# WIKANTIK_COMPOSE_PROFILES / WIKANTIK_ENV_FILE. Word-splitting on the two
# space-separated config vars is intentional (that's the documented list
# convention), hence the shellcheck disables.
_compose_args() {
    COMPOSE_ARGS=(--env-file "${WIKANTIK_ENV_FILE}")
    local f
    # shellcheck disable=SC2086
    for f in ${WIKANTIK_COMPOSE_FILES}; do
        COMPOSE_ARGS+=(-f "${f}")
    done
    local p
    # shellcheck disable=SC2086
    for p in ${WIKANTIK_COMPOSE_PROFILES}; do
        COMPOSE_ARGS+=(--profile "${p}")
    done
}

# _set_env_var FILE KEY VALUE — rewrite (or append) a KEY=VALUE line in FILE,
# in place via a temp-file + mv (atomic, no reliance on `sed -i`'s
# GNU-vs-BSD-incompatible backup-suffix syntax).
_set_env_var() {
    local file="$1" key="$2" value="$3"
    local tmp line found=0
    tmp="$(mktemp "${file}.XXXXXX")"
    if [[ -f "${file}" ]]; then
        while IFS= read -r line || [[ -n "${line}" ]]; do
            if [[ "${line}" == "${key}="* ]]; then
                printf '%s=%s\n' "${key}" "${value}" >> "${tmp}"
                found=1
            else
                printf '%s\n' "${line}" >> "${tmp}"
            fi
        done < "${file}"
    fi
    if [[ "${found}" -eq 0 ]]; then
        printf '%s=%s\n' "${key}" "${value}" >> "${tmp}"
    fi
    mv "${tmp}" "${file}"
}

# ---------- 0: move into the compose project dir ----------
# Skipped (printed only) under --dry-run so the plan can be inspected from
# any directory, without WIKANTIK_REPO_DIR needing to exist yet (useful for
# validating the script before it's installed on a real VM).
if [[ "${DRY_RUN}" -eq 1 ]]; then
    echo "[dry-run] cd ${WIKANTIK_REPO_DIR}"
else
    if [[ ! -d "${WIKANTIK_REPO_DIR}" ]]; then
        _err "WIKANTIK_REPO_DIR does not exist: ${WIKANTIK_REPO_DIR}"
        exit 2
    fi
    cd "${WIKANTIK_REPO_DIR}"
    if [[ ! -f "${WIKANTIK_ENV_FILE}" ]]; then
        _err "env file not found: ${WIKANTIK_ENV_FILE}"
        exit 2
    fi
fi

_compose_args

# ---------- 1: docker login (conditional) ----------
if [[ -n "${GHCR_USER:-}" && -n "${GHCR_TOKEN:-}" ]]; then
    if [[ "${DRY_RUN}" -eq 1 ]]; then
        echo "[dry-run] docker login ghcr.io -u ${GHCR_USER} --password-stdin   (token redacted)"
    else
        printf '%s' "${GHCR_TOKEN}" | docker login ghcr.io -u "${GHCR_USER}" --password-stdin
    fi
else
    echo "wikantik-update.sh: GHCR_USER/GHCR_TOKEN not both set; assuming ambient ghcr.io login."
fi

# ---------- 2: pull the exact image ref ----------
_run docker pull "${NEW_IMAGE}"

# ---------- 3: tag the currently-running image as wikantik:rollback ----------
# Silent no-op on first deploy (no running container yet), same discipline as
# bin/remote.sh's "docker tag wikantik:latest wikantik:rollback 2>/dev/null || true".
if [[ "${DRY_RUN}" -eq 1 ]]; then
    echo "[dry-run] (identify currently-running ${WIKANTIK_SERVICE} image, then) docker tag <that image> wikantik:rollback   (silent no-op on first deploy)"
else
    CURRENT_CID="$(docker compose "${COMPOSE_ARGS[@]}" ps -q "${WIKANTIK_SERVICE}" 2>/dev/null || true)"
    if [[ -n "${CURRENT_CID}" ]]; then
        CURRENT_IMAGE_ID="$(docker inspect --format '{{.Image}}' "${CURRENT_CID}" 2>/dev/null || true)"
        if [[ -n "${CURRENT_IMAGE_ID}" ]]; then
            docker tag "${CURRENT_IMAGE_ID}" wikantik:rollback 2>/dev/null || true
        fi
    fi
fi

# ---------- 4: back up + update WIKANTIK_IMAGE in .env ----------
OLD_IMAGE_VALUE=""
if [[ -f "${WIKANTIK_ENV_FILE}" ]]; then
    OLD_IMAGE_VALUE="$(grep -m1 '^WIKANTIK_IMAGE=' "${WIKANTIK_ENV_FILE}" 2>/dev/null | cut -d= -f2- || true)"
fi

if [[ "${DRY_RUN}" -eq 1 ]]; then
    echo "[dry-run] cp ${WIKANTIK_ENV_FILE} ${WIKANTIK_ENV_FILE}.bak"
    echo "[dry-run] set WIKANTIK_IMAGE=${NEW_IMAGE} in ${WIKANTIK_ENV_FILE}   (was: ${OLD_IMAGE_VALUE:-<unset>})"
else
    cp "${WIKANTIK_ENV_FILE}" "${WIKANTIK_ENV_FILE}.bak"
    _set_env_var "${WIKANTIK_ENV_FILE}" WIKANTIK_IMAGE "${NEW_IMAGE}"
fi

# ---------- 5: up -d ----------
_run docker compose "${COMPOSE_ARGS[@]}" up -d

# ---------- 6: health poll ----------
if [[ "${DRY_RUN}" -eq 1 ]]; then
    echo "[dry-run] poll ${HEALTH_URL} every 3s up to ${HEALTH_TIMEOUT}s"
    echo "wikantik-update.sh: dry-run complete — no docker/curl commands were executed."
    exit 0
fi

DEADLINE=$(( $(date +%s) + HEALTH_TIMEOUT ))
HEALTHY=0
while (( $(date +%s) < DEADLINE )); do
    if curl -sfo /dev/null --max-time 5 "${HEALTH_URL}"; then
        HEALTHY=1
        break
    fi
    sleep 3
done

if [[ "${HEALTHY}" -eq 1 ]]; then
    echo "wikantik-update.sh: healthy — ${NEW_IMAGE} deployed (${HEALTH_URL} returned 200)."
    exit 0
fi

# ---------- 7: failure -> auto-rollback ----------
_err "${HEALTH_URL} did not return 200 within ${HEALTH_TIMEOUT}s; rolling back."

if [[ -n "${OLD_IMAGE_VALUE}" ]]; then
    _set_env_var "${WIKANTIK_ENV_FILE}" WIKANTIK_IMAGE "${OLD_IMAGE_VALUE}"
    echo "wikantik-update.sh: restored WIKANTIK_IMAGE=${OLD_IMAGE_VALUE} in ${WIKANTIK_ENV_FILE}; re-running up -d." >&2
    # --force-recreate scoped to just the service, matching bin/remote.sh's
    # rollback (`up -d --force-recreate wikantik`) — needed in the edge case
    # where OLD_IMAGE_VALUE equals NEW_IMAGE (redeploying an already-broken
    # tag), where a plain `up -d` would otherwise see no config diff.
    docker compose "${COMPOSE_ARGS[@]}" up -d --force-recreate "${WIKANTIK_SERVICE}" \
        || _err "rollback up -d failed — manual recovery required."
else
    _err "no previous WIKANTIK_IMAGE recorded in ${WIKANTIK_ENV_FILE} (first deploy?) — cannot auto-rollback .env."
    _err "the wikantik:rollback docker tag (if one was set above) may still be usable for a manual recovery."
fi

docker compose "${COMPOSE_ARGS[@]}" logs --tail=50 "${WIKANTIK_SERVICE}" >&2 || true
exit 1
