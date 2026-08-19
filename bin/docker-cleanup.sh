#!/usr/bin/env bash
#
# bin/docker-cleanup.sh — sweep Docker resources leaked by THIS repo's
# integration-test suite: stale per-module IT postgres/keycloak/authentik
# containers left behind when a module's build dies before Maven's
# post-integration-test `pg-stop` teardown runs, a leftover shared IT
# embedder compose stack, and orphaned anonymous IT-database volumes.
#
# Each IT module pom already force-removes ITS OWN stale container at
# `initialize` (see wikantik-it-tests/pom.xml, execution id pg-cleanup-stale),
# but that only self-heals on that module's next run and never reclaims
# volumes an already-removed container left behind. This script is the
# standing, repo-wide sweep for what's left after a crashed run.
#
# Every resource this script can touch is matched by an EXACT allowlisted
# name/label/content-check derived from this repo (poms, compose files, the
# pinned pgvector image tag) — never a wildcard, never a blanket prune. It is
# designed to be impossible to point at another project's containers or
# volumes on a shared dev box (this machine also runs CrafterCMS, Roller,
# and a Grafana/Alloy agent).
#
# See docs/wikantik-pages/DebuggingFailingIntegrationTests.md (stale pgvector
# containers / the port 55432 collision) for the manual procedure this
# automates.
#
# Scope:
#   1. Stale IT containers, by EXACT name only:
#        wikantik-pg-<it-module-artifactId>
#        wikantik-kc-<it-module-artifactId>                        (only modules whose pom declares it)
#        wikantik-authentik-{redis,db,server,worker}-<it-module-artifactId>  (only modules whose pom declares it)
#      <it-module-artifactId> is discovered by listing the wikantik-it-test-*
#      directories under wikantik-it-tests/ (equal to each module's real
#      Maven artifactId); which of the kc/authentik variants apply to a given
#      module is discovered by grepping THAT module's own pom.xml for the
#      literal alias pattern — never assumed.
#   2. The leftover shared IT embedder: `docker compose -p wikantik-embed-test
#      -f docker/docker-compose.embeddings.yml down`, WITHOUT -v — the named
#      wikantik-embed-test_ollama-models volume caches a ~600 MB model and is
#      never removed. wikantik-embed-dev (the developer's own running
#      embedder) is a different compose project and is never referenced
#      anywhere in this script.
#   3. Orphaned anonymous IT-database volumes: only volumes that are ALL of
#      dangling (`docker volume ls -f dangling=true`, i.e. attached to no
#      container), carrying the com.docker.volume.anonymous label (a named
#      volume, e.g. crafter_authoring_data, can never carry this label), AND
#      whose contents are a PostgreSQL data directory (a PG_VERSION file)
#      whose major version matches <pgvector.image> in the root pom.xml.
#      Checked by mounting the volume read-only into a throwaway busybox
#      container — this box has no host permission to read Docker's volume
#      store directly, and a container-attached volume never appears in the
#      dangling list in the first place.
#   4. --prune-images (opt-in, OFF by default): `docker image prune -f`.
#      Called out separately because dangling images are machine-wide, not
#      scoped to this repo the way 1-3 are.
#
# This script NEVER calls `docker volume prune`, `docker container prune`,
# `docker system prune`, or any other blanket prune. If a change here would
# require one, that is the wrong design — extend the allowlist instead.
#
# Usage:
#   bin/docker-cleanup.sh [--apply] [--prune-images] [-h|--help]
#
# Dry-run by default: prints exactly what would be removed and exits 0
# without changing anything. Pass --apply to actually remove.
#
# Exit codes:
#   0   success (including "nothing to clean" / docker unreachable)
#   2   usage error (unknown argument)
#
# NOT `set -e`: like bin/run-tests.sh, this sweeps several independent
# resource classes and should keep going and report what it could even if
# one docker call for one class fails — a partial sweep beats an aborted one.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}" || exit 1

print_help() {
    awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
}

APPLY=0
PRUNE_IMAGES=0
while [[ $# -gt 0 ]]; do
    case "$1" in
        --apply) APPLY=1; shift ;;
        --prune-images) PRUNE_IMAGES=1; shift ;;
        -h|--help) print_help; exit 0 ;;
        *)
            echo "docker-cleanup.sh: unknown argument: $1" >&2
            print_help >&2
            exit 2
            ;;
    esac
done

# _run CMD ARGS... — execute (or, without --apply, print) a DESTRUCTIVE
# command. Read-only discovery commands (docker ps / volume ls / run cat) are
# never routed through this — they must always execute so dry-run can report
# accurately on what it found.
_run() {
    if [[ "${APPLY}" -eq 0 ]]; then
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
    echo "+ $*"
    "$@"
}

if ! command -v docker >/dev/null 2>&1; then
    echo "docker-cleanup.sh: docker not found on PATH; nothing to do." >&2
    exit 0
fi
if ! docker info >/dev/null 2>&1; then
    echo "docker-cleanup.sh: docker daemon not reachable; nothing to do." >&2
    exit 0
fi

REMOVED_CONTAINERS=()
REMOVED_VOLUMES=()
RECLAIMED_VOLUME_BYTES=0
EMBEDDER_TORN_DOWN=0

# ---------------------------------------------------------------------------
# 1. Stale IT containers
# ---------------------------------------------------------------------------

PGVECTOR_MAJOR="$(grep -oE '<pgvector\.image>[^<]+</pgvector\.image>' pom.xml \
    | sed -E 's#.*:pg([0-9]+)</pgvector\.image>#\1#' || true)"
if [[ -z "${PGVECTOR_MAJOR}" ]]; then
    echo "docker-cleanup.sh: WARNING: could not read <pgvector.image> from pom.xml; skipping the anonymous-volume PG_VERSION check." >&2
fi

echo "== Stale IT containers =="
container_candidates=()
if [[ -d wikantik-it-tests ]]; then
    while IFS= read -r moddir; do
        [[ -n "${moddir}" ]] || continue
        artifact_id="$(basename "${moddir}")"
        pomfile="${moddir}/pom.xml"
        [[ -f "${pomfile}" ]] || continue

        # Every IT module activates the parent's pluginManagement-declared
        # docker-maven-plugin pg-start/pg-stop executions, so wikantik-pg-<id>
        # applies unconditionally. The kc/authentik variants are sidecars
        # only sso/scim-fullloop declare — confirmed per-module by grepping
        # THAT module's own pom, never assumed for the whole set.
        container_candidates+=("wikantik-pg-${artifact_id}")
        # shellcheck disable=SC2016  # literal fixed-string pattern (-F), not meant to expand
        if grep -qF 'wikantik-kc-${project.artifactId}' "${pomfile}"; then
            container_candidates+=("wikantik-kc-${artifact_id}")
        fi
        for role in redis db server worker; do
            if grep -qF "wikantik-authentik-${role}-\${project.artifactId}" "${pomfile}"; then
                container_candidates+=("wikantik-authentik-${role}-${artifact_id}")
            fi
        done
    done < <(find wikantik-it-tests -mindepth 1 -maxdepth 1 -type d -name 'wikantik-it-test-*' 2>/dev/null | sort)
fi

existing_containers="$(docker ps -a --format '{{.Names}}' 2>/dev/null || true)"
for name in "${container_candidates[@]+"${container_candidates[@]}"}"; do
    if printf '%s\n' "${existing_containers}" | grep -qFx "${name}"; then
        echo "  found: ${name}"
        if _run docker rm -f -v "${name}"; then
            REMOVED_CONTAINERS+=("${name}")
        else
            echo "  WARNING: failed to remove container ${name} (continuing sweep)" >&2
        fi
    fi
done
[[ ${#REMOVED_CONTAINERS[@]} -eq 0 ]] && echo "  none found"

# ---------------------------------------------------------------------------
# 2. Leftover shared IT embedder (compose project wikantik-embed-test).
#    wikantik-embed-dev — the developer's own running embedder — is a
#    different compose project and is deliberately never named below.
# ---------------------------------------------------------------------------

echo "== Shared IT embedder (wikantik-embed-test) =="
EMBED_COMPOSE_FILE="docker/docker-compose.embeddings.yml"
embed_present="$(docker compose -p wikantik-embed-test -f "${EMBED_COMPOSE_FILE}" ps -a -q 2>/dev/null || true)"
if [[ -n "${embed_present}" ]]; then
    echo "  found: leftover wikantik-embed-test containers"
    if _run docker compose -p wikantik-embed-test -f "${EMBED_COMPOSE_FILE}" down; then
        EMBEDDER_TORN_DOWN=1
    else
        echo "  WARNING: failed to tear down wikantik-embed-test (continuing sweep)" >&2
    fi
else
    echo "  none found"
fi

# ---------------------------------------------------------------------------
# 3. Orphaned anonymous IT-database volumes
# ---------------------------------------------------------------------------

echo "== Orphaned anonymous IT database volumes (PG${PGVECTOR_MAJOR:-?} data dirs) =="
if [[ -n "${PGVECTOR_MAJOR}" ]]; then
    while IFS= read -r vol; do
        [[ -n "${vol}" ]] || continue
        pgver="$(docker run --rm -v "${vol}:/vol:ro" busybox cat /vol/PG_VERSION 2>/dev/null || true)"
        if [[ "${pgver}" != "${PGVECTOR_MAJOR}" ]]; then
            continue
        fi
        size_bytes="$(docker run --rm -v "${vol}:/vol:ro" busybox du -sb /vol 2>/dev/null | cut -f1 || true)"
        [[ "${size_bytes}" =~ ^[0-9]+$ ]] || size_bytes=0
        echo "  found: ${vol} (PG_VERSION=${pgver}, ${size_bytes} bytes)"
        if _run docker volume rm "${vol}"; then
            REMOVED_VOLUMES+=("${vol}")
            RECLAIMED_VOLUME_BYTES=$(( RECLAIMED_VOLUME_BYTES + size_bytes ))
        else
            echo "  WARNING: failed to remove volume ${vol} (continuing sweep)" >&2
        fi
    done < <(docker volume ls -f dangling=true -f label=com.docker.volume.anonymous --format '{{.Name}}' 2>/dev/null || true)
fi
[[ ${#REMOVED_VOLUMES[@]} -eq 0 ]] && echo "  none found"

# ---------------------------------------------------------------------------
# 4. Dangling images — opt-in, machine-wide (not scoped to this repo)
# ---------------------------------------------------------------------------

if [[ "${PRUNE_IMAGES}" -eq 1 ]]; then
    echo "== Dangling images (--prune-images: machine-wide, NOT repo-scoped) =="
    _run docker image prune -f
else
    echo "== Dangling images: skipped (pass --prune-images to include; this is machine-wide, not repo-scoped) =="
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------

human_bytes() {
    local b="$1"
    if [[ "${b}" -ge 1073741824 ]]; then
        awk -v b="${b}" 'BEGIN { printf "%.2f GB", b/1073741824 }'
    elif [[ "${b}" -ge 1048576 ]]; then
        awk -v b="${b}" 'BEGIN { printf "%.2f MB", b/1048576 }'
    elif [[ "${b}" -ge 1024 ]]; then
        awk -v b="${b}" 'BEGIN { printf "%.2f KB", b/1024 }'
    else
        printf '%s B' "${b}"
    fi
}

echo
echo "== Summary =="
echo "  containers removed: ${#REMOVED_CONTAINERS[@]}"
echo "  volumes removed:    ${#REMOVED_VOLUMES[@]} ($(human_bytes "${RECLAIMED_VOLUME_BYTES}"))"
echo "  embedder torn down: $([[ ${EMBEDDER_TORN_DOWN} -eq 1 ]] && echo yes || echo no)"
if [[ "${APPLY}" -eq 0 ]]; then
    echo "  (dry-run — pass --apply to actually remove)"
fi

exit 0
