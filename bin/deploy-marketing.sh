#!/usr/bin/env bash
#
# bin/deploy-marketing.sh — publish everything served from www.wikantik.com:
# the static marketing site (docroot root) AND the generated code-health site
# (docroot /site subdir). One command, one sudo prompt.
#
# Both halves live on the same host and the same nginx docroot, so they are
# deployed together:
#
#   marketing/            -> ${MKT_DOCROOT}/        (no build step; source of truth is the repo)
#   target/staging/       -> ${MKT_DOCROOT}/site/   (built by bin/site.sh)
#
# The marketing host's sudo is NOT passwordless, so the privileged copy runs
# over an interactive ssh -t session: you will be prompted for the sudo
# password on the marketing host. Run it yourself (or via `! bin/deploy-marketing.sh`
# inside a Claude session) so the prompt is answerable. Both halves are copied
# inside that ONE session, so you are prompted once, not twice.
#
# The code-health site is only published if it has been built. If
# target/staging/ is absent this script publishes marketing, prints a loud
# SKIPPED notice, and still exits 0 — a missing local build must not block a
# marketing deploy, and the already-published /site on the host is left
# untouched. Pass --build-site to build it first, or --marketing-only to say
# you meant to skip it.
#
# Usage:
#   bin/deploy-marketing.sh [--dry-run] [--marketing-only] [--site-only]
#                           [--build-site] [-h|--help]
#
#   (no flags)        publish marketing + code-health site (if built)
#   --marketing-only  publish only the marketing site
#   --site-only       publish only the code-health site (requires it be built)
#   --build-site      run bin/site.sh first, then publish both
#   --dry-run         print every action, change nothing
#
# Configuration (env overrides; defaults match the live setup):
#   MKT_HOST          ssh host alias for the marketing box  (default: cloudflare)
#   MKT_DOCROOT       nginx docroot for the vhost           (default: /var/www/www.wikantik.com)
#   MKT_STAGING       unprivileged staging dir, marketing   (default: ~/wikantik-deploy/site)
#   MKT_SITE_STAGING  unprivileged staging dir, code-health (default: ~/wikantik-deploy/code-health-site)
#   MKT_OWNER         docroot owner:group                   (default: www-data:www-data)
#   MKT_ORIGIN_PORT   nginx listen port for on-origin verify (default: 8000)
#   SITE_SRC          local built code-health site          (default: target/staging)
#
# Exit codes: 0 success · 1 deploy/verify failure · 2 usage error.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

MKT_HOST="${MKT_HOST:-cloudflare}"
MKT_DOCROOT="${MKT_DOCROOT:-/var/www/www.wikantik.com}"
MKT_STAGING="${MKT_STAGING:-~/wikantik-deploy/site}"
MKT_SITE_STAGING="${MKT_SITE_STAGING:-~/wikantik-deploy/code-health-site}"
MKT_OWNER="${MKT_OWNER:-www-data:www-data}"
MKT_ORIGIN_PORT="${MKT_ORIGIN_PORT:-8000}"
SITE_SRC="${SITE_SRC:-target/staging}"

DRY_RUN=0
DO_MARKETING=1
DO_SITE=1
BUILD_SITE=0

# The web bundle that nginx serves. Explicit allowlist so dev-only files
# (form-helper.mjs, test/, form-backend/, README.md, FORM-SETUP.md) never
# leak into the public docroot. Add new web assets here.
WEB_FILES=(
    index.html
    styles.css
    favicon.svg
    ads.txt
    robots.txt
    sitemap.xml
    71ae77e77ccbdd4e052d901722cf22a8.txt
    assets
    platform
    enterprise
    compare
)

print_help() {
    awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
}

for arg in "$@"; do
    case "${arg}" in
        --dry-run)        DRY_RUN=1 ;;
        --marketing-only) DO_SITE=0 ;;
        --site-only)      DO_MARKETING=0 ;;
        --build-site)     BUILD_SITE=1 ;;
        -h|--help)        print_help; exit 0 ;;
        *) echo "deploy-marketing: unknown argument: ${arg}" >&2; exit 2 ;;
    esac
done

if [[ "${DO_MARKETING}" -eq 0 && "${DO_SITE}" -eq 0 ]]; then
    echo "deploy-marketing: --marketing-only and --site-only are mutually exclusive" >&2
    exit 2
fi

run() {
    if [[ "${DRY_RUN}" -eq 1 ]]; then
        echo "[dry-run] $*"
    else
        "$@"
    fi
}

# ---------------------------------------------------------------- preflight

# Optionally build the code-health site first (a full Maven site run — minutes).
if [[ "${BUILD_SITE}" -eq 1 && "${DO_SITE}" -eq 1 ]]; then
    echo "==> Building code-health site (bin/site.sh)"
    run bin/site.sh
fi

# Marketing: every allowlisted file must exist before we ship anything.
if [[ "${DO_MARKETING}" -eq 1 ]]; then
    missing=()
    for f in "${WEB_FILES[@]}"; do
        [[ -e "marketing/${f}" ]] || missing+=("marketing/${f}")
    done
    if [[ ${#missing[@]} -gt 0 ]]; then
        echo "deploy-marketing: missing web files: ${missing[*]}" >&2
        exit 1
    fi
fi

# Code-health site: present or not? An explicit --site-only makes a missing
# build a hard error (you asked for exactly that thing). In the combined
# default it degrades to a reported skip.
SITE_SKIPPED=0
if [[ "${DO_SITE}" -eq 1 && "${DRY_RUN}" -eq 0 && ! -f "${SITE_SRC}/index.html" ]]; then
    if [[ "${DO_MARKETING}" -eq 0 ]]; then
        echo "deploy-marketing: ${SITE_SRC}/index.html not found — run bin/site.sh first" >&2
        exit 1
    fi
    DO_SITE=0
    SITE_SKIPPED=1
fi

# ------------------------------------------------------------------ stage

echo "==> Publishing to ${MKT_HOST}:${MKT_DOCROOT}"
[[ "${DO_MARKETING}" -eq 1 ]] && echo "    marketing site   -> ${MKT_DOCROOT}/"
[[ "${DO_SITE}"      -eq 1 ]] && echo "    code-health site -> ${MKT_DOCROOT}/site/"

# 1. rsync each tree to its own unprivileged staging dir (no sudo needed).
#    The two staging dirs MUST stay distinct or each deploy clobbers the other.
if [[ "${DO_MARKETING}" -eq 1 ]]; then
    SRC=()
    for f in "${WEB_FILES[@]}"; do SRC+=("marketing/${f}"); done
    run rsync -avz --delete-after "${SRC[@]}" "${MKT_HOST}:${MKT_STAGING}/"
fi
if [[ "${DO_SITE}" -eq 1 ]]; then
    run rsync -avz --delete-after "${SITE_SRC}/" "${MKT_HOST}:${MKT_SITE_STAGING}/"
fi

# ------------------------------------------------------- privileged publish

# 2. ONE privileged session for both halves, so sudo prompts once.
#    ~ and * are expanded by the remote login shell before sudo runs.
#
#    Ordering and copy semantics are load-bearing:
#      - marketing uses `cp -r` (NOT `rsync --delete`) into the docroot root.
#        The docroot also contains the code-health site at /site, so a
#        --delete here would wipe it. Do not "improve" this to rsync --delete
#        without excluding /site.
#      - the code-health half uses `rsync --delete` scoped to /site/, which is
#        safe precisely because it is confined to that subdir.
REMOTE_PUBLISH="set -e"
if [[ "${DO_MARKETING}" -eq 1 ]]; then
    REMOTE_PUBLISH="${REMOTE_PUBLISH}
sudo cp -r ${MKT_STAGING}/* '${MKT_DOCROOT}/'
sudo chown -R ${MKT_OWNER} '${MKT_DOCROOT}'"
fi
if [[ "${DO_SITE}" -eq 1 ]]; then
    REMOTE_PUBLISH="${REMOTE_PUBLISH}
sudo mkdir -p '${MKT_DOCROOT}/site'
sudo rsync -a --delete ${MKT_SITE_STAGING}/ '${MKT_DOCROOT}/site/'
sudo chown -R ${MKT_OWNER} '${MKT_DOCROOT}/site'"
fi

if [[ "${DRY_RUN}" -eq 1 ]]; then
    echo "[dry-run] ssh -t ${MKT_HOST} <<'${REMOTE_PUBLISH}'>>"
else
    ssh -t "${MKT_HOST}" "${REMOTE_PUBLISH}"
fi

# ------------------------------------------------------------------ verify

# 3. Verify on-origin (bypasses Cloudflare cache) that the key files serve 200.
VERIFY_PATHS=()
[[ "${DO_MARKETING}" -eq 1 ]] && VERIFY_PATHS+=(
    /ads.txt /index.html /robots.txt /sitemap.xml
    /platform/index.html /enterprise/index.html /compare/index.html )
[[ "${DO_SITE}" -eq 1 ]] && VERIFY_PATHS+=(
    /site/index.html /site/coupling.html /site/pmd.html )

echo "==> Verifying on-origin (http://localhost:${MKT_ORIGIN_PORT}, Host: www.wikantik.com)"
if [[ "${DRY_RUN}" -eq 1 ]]; then
    echo "[dry-run] ssh ${MKT_HOST} curl ${VERIFY_PATHS[*]}"
else
    for path in "${VERIFY_PATHS[@]}"; do
        line="$(ssh "${MKT_HOST}" "curl -s -o /dev/null -w '%{http_code} %{content_type}' \
            -H 'Host: www.wikantik.com' http://localhost:${MKT_ORIGIN_PORT}${path}")"
        echo "    ${path} -> ${line}"
        case "${line}" in
            200\ *) ;;
            *) echo "deploy-marketing: ${path} did not return 200 (got: ${line})" >&2; exit 1 ;;
        esac
    done
fi

# ------------------------------------------------------------------ report

[[ "${DO_MARKETING}" -eq 1 ]] && echo "==> Marketing site published to https://www.wikantik.com/"
[[ "${DO_SITE}"      -eq 1 ]] && echo "==> Code-health site published to https://wikantik.com/site/"

if [[ "${SITE_SKIPPED}" -eq 1 ]]; then
    echo
    echo "!!  SKIPPED the code-health site: ${SITE_SRC}/ has not been built."
    echo "!!  The copy already on the host was left untouched (not deleted)."
    echo "!!  To include it:  bin/deploy-marketing.sh --build-site"
    echo "!!  Or build then deploy:  bin/site.sh && bin/deploy-marketing.sh"
    echo "!!  To silence this notice:  bin/deploy-marketing.sh --marketing-only"
fi

[[ "${DO_MARKETING}" -eq 1 ]] && \
    echo "==> Purge Cloudflare cache for /ads.txt if it was ever fetched as a 404."

exit 0
