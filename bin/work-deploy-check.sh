#!/usr/bin/env bash
#
# work-deploy-check.sh — ask work management about the version that is actually
# about to be shipped, not one somebody named.
#
# A deploy script calls this when WORK_DEPLOYMENT_ID is set, saying how the image
# it is about to ship came to be. This script works out that image's version and
# asks bin/work-deploy-gate.sh about it. If the operator also named a version
# (WORK_DEPLOYMENT_VERSION) and it is not the one being shipped, the deploy stops.
#
# Usage:
#   bin/work-deploy-check.sh pull <tag>   the remote will pull <tag>
#   bin/work-deploy-check.sh image        the local wikantik:latest image is shipped as it is
#   bin/work-deploy-check.sh build        the working tree is built and shipped
#
# Env: WORK_DEPLOYMENT_ID (required), WORK_DEPLOYMENT_VERSION (optional), and what
#      bin/work-deploy-gate.sh needs (WORK_API_URL, WORK_API_TOKEN).
#
# Exit codes: as bin/work-deploy-gate.sh (0 approved · 1 not approved · 2 could not ask).
set -euo pipefail
set +x

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
say() { printf 'work-deploy-check: %s\n' "$1"; }

if [[ -z "${WORK_DEPLOYMENT_ID:-}" ]]; then
    say "WORK_DEPLOYMENT_ID is not set; nothing to check against"
    exit 2
fi

case "${1:-}" in
    pull)
        SHIPPED="${2:-}" ;;
    image)
        # Only the release workflow labels an image with its version; a locally built image carries its base
        # image's label, which no deployment was approved for, so it is refused rather than guessed at.
        SHIPPED="$(docker image inspect wikantik:latest \
            --format '{{ index .Config.Labels "org.opencontainers.image.version" }}' 2>/dev/null | head -1)" || SHIPPED="" ;;
    build)
        SHIPPED="$(sed -n 's:^  <version>\(.*\)</version>$:\1:p' "${REPO_ROOT}/pom.xml" | head -1)" ;;
    *)
        say "usage: work-deploy-check.sh pull <tag> | image | build"
        exit 2 ;;
esac

if [[ -z "${SHIPPED}" ]]; then
    say "cannot tell which version is about to be shipped; not deploying"
    exit 2
fi
if [[ -n "${WORK_DEPLOYMENT_VERSION:-}" && "${WORK_DEPLOYMENT_VERSION}" != "${SHIPPED}" ]]; then
    say "about to ship ${SHIPPED}, but WORK_DEPLOYMENT_VERSION says ${WORK_DEPLOYMENT_VERSION}; not deploying"
    exit 1
fi
exec "${REPO_ROOT}/bin/work-deploy-gate.sh" "${WORK_DEPLOYMENT_ID}" "${SHIPPED}"
