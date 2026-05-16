#!/usr/bin/env bash
#
# deploy-release.sh — deploy a released Wikantik image to the remote host.
#
# The happy-path "update" wrapper: it captures the routine release-upgrade
# sequence (pull the published image → tag it → ship it) as one command.
# The persistent DB volume and host-bind page tree survive the swap, and the
# container entrypoint applies any new schema migrations on start.
#
# This is NOT the first-deploy path. The first deploy initialises the DB and
# pages (see docs/DockerDeployment.md). deploy-release.sh assumes the remote
# has already been bootstrapped and is running a prior version.
#
# Usage:
#   bin/deploy-release.sh X.Y.Z       pull ghcr.io/.../wikantik:X.Y.Z, deploy it
#   bin/deploy-release.sh --local     deploy the local wikantik:latest as-is
#   bin/deploy-release.sh --help
#
# What it does:
#   1. (X.Y.Z given) docker pull ghcr.io/jakefearsd/wikantik:X.Y.Z
#   2. docker tag <image> wikantik:latest
#   3. bin/remote.sh deploy --skip-build
#        → tags the running remote image wikantik:rollback
#        → docker save | ssh 'docker load'
#        → rsyncs docker-compose*.yml + the prod env file
#        → container.sh -e prod up -d   (entrypoint runs pending migrations)
#        → polls /api/health; auto-rolls-back on failure
#
# Env:
#   WIKANTIK_IMAGE   registry image (default: ghcr.io/jakefearsd/wikantik)
#
# Exit codes: 0 success · 1 deploy/health failure · 2 usage error
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

WIKANTIK_IMAGE="${WIKANTIK_IMAGE:-ghcr.io/jakefearsd/wikantik}"

case "${1:-}" in
    -h|--help)
        awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
        exit 0
        ;;
    "")
        echo "deploy-release.sh: missing argument — pass X.Y.Z or --local (see --help)." >&2
        exit 2
        ;;
esac

if [[ "$1" == "--local" ]]; then
    if ! docker image inspect wikantik:latest >/dev/null 2>&1; then
        echo "deploy-release.sh: --local given but no wikantik:latest image exists locally." >&2
        exit 2
    fi
    echo "==> Deploying the existing local wikantik:latest image."
else
    VERSION="$1"
    if [[ ! "${VERSION}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
        echo "deploy-release.sh: '${VERSION}' is not a X.Y.Z version (or use --local)." >&2
        exit 2
    fi
    echo "==> Pulling ${WIKANTIK_IMAGE}:${VERSION}"
    docker pull "${WIKANTIK_IMAGE}:${VERSION}"
    echo "==> Tagging as wikantik:latest"
    docker tag "${WIKANTIK_IMAGE}:${VERSION}" wikantik:latest
    docker tag "${WIKANTIK_IMAGE}:${VERSION}" "wikantik:${VERSION}"
fi

echo "==> Deploying to the remote (bin/remote.sh deploy --skip-build)"
exec bin/remote.sh deploy --skip-build
