#!/usr/bin/env bash
#
# bin/deploy-marketing.sh — publish the static marketing site (www.wikantik.com).
#
# Source of truth is the repo's marketing/ directory (no build step). This
# script rsyncs the web bundle to a staging dir on the marketing host, then
# copies it into the nginx docroot under sudo (the docroot is owned by
# www-data, so the copy is privileged) and chowns it back to www-data.
#
# The marketing host's sudo is NOT passwordless, so the privileged copy runs
# over an interactive ssh -t session: you will be prompted for the sudo
# password on the marketing host. Run it yourself (or via `! bin/deploy-marketing.sh`
# inside a Claude session) so the prompt is answerable.
#
# Usage:
#   bin/deploy-marketing.sh [--dry-run] [-h|--help]
#
# Configuration (env overrides; defaults match the live setup):
#   MKT_HOST       ssh host alias for the marketing box   (default: cloudflare)
#   MKT_DOCROOT    nginx docroot for the vhost            (default: /var/www/www.wikantik.com)
#   MKT_STAGING    unprivileged staging dir on the host   (default: ~/wikantik-deploy/site)
#   MKT_OWNER      docroot owner:group                    (default: www-data:www-data)
#   MKT_ORIGIN_PORT  nginx listen port for on-origin verify (default: 8000)
#
# Exit codes: 0 success · 1 deploy/verify failure · 2 usage error.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

MKT_HOST="${MKT_HOST:-cloudflare}"
MKT_DOCROOT="${MKT_DOCROOT:-/var/www/www.wikantik.com}"
MKT_STAGING="${MKT_STAGING:-~/wikantik-deploy/site}"
MKT_OWNER="${MKT_OWNER:-www-data:www-data}"
MKT_ORIGIN_PORT="${MKT_ORIGIN_PORT:-8000}"
DRY_RUN=0

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
        --dry-run) DRY_RUN=1 ;;
        -h|--help) print_help; exit 0 ;;
        *) echo "deploy-marketing: unknown argument: ${arg}" >&2; exit 2 ;;
    esac
done

run() {
    if [[ "${DRY_RUN}" -eq 1 ]]; then
        echo "[dry-run] $*"
    else
        "$@"
    fi
}

# Verify every allowlisted file actually exists in the repo before shipping.
missing=()
for f in "${WEB_FILES[@]}"; do
    [[ -e "marketing/${f}" ]] || missing+=("marketing/${f}")
done
if [[ ${#missing[@]} -gt 0 ]]; then
    echo "deploy-marketing: missing web files: ${missing[*]}" >&2
    exit 1
fi

echo "==> Publishing marketing site to ${MKT_HOST}:${MKT_DOCROOT}"

# 1. rsync the web bundle to the unprivileged staging dir (no sudo needed).
SRC=()
for f in "${WEB_FILES[@]}"; do SRC+=("marketing/${f}"); done
run rsync -avz --delete-after "${SRC[@]}" "${MKT_HOST}:${MKT_STAGING}/"

# 2. Privileged copy staging -> docroot, then restore www-data ownership.
#    ~ and * are expanded by the remote login shell before sudo runs.
#    ssh -t allocates a tty so the sudo password prompt is answerable.
REMOTE_PUBLISH="set -e
sudo cp -r ${MKT_STAGING}/* '${MKT_DOCROOT}/'
sudo chown -R ${MKT_OWNER} '${MKT_DOCROOT}'"
if [[ "${DRY_RUN}" -eq 1 ]]; then
    echo "[dry-run] ssh -t ${MKT_HOST} <<'${REMOTE_PUBLISH}'>>"
else
    ssh -t "${MKT_HOST}" "${REMOTE_PUBLISH}"
fi

# 3. Verify on-origin (bypasses Cloudflare cache) that the key files serve 200.
echo "==> Verifying on-origin (http://localhost:${MKT_ORIGIN_PORT}, Host: www.wikantik.com)"
if [[ "${DRY_RUN}" -eq 1 ]]; then
    echo "[dry-run] ssh ${MKT_HOST} curl ads.txt + index.html + sitemap.xml + cluster index"
    exit 0
fi
for path in /ads.txt /index.html /robots.txt /sitemap.xml /platform/index.html /enterprise/index.html /compare/index.html; do
    line="$(ssh "${MKT_HOST}" "curl -s -o /dev/null -w '%{http_code} %{content_type}' \
        -H 'Host: www.wikantik.com' http://localhost:${MKT_ORIGIN_PORT}${path}")"
    echo "    ${path} -> ${line}"
    case "${line}" in
        200\ *) ;;
        *) echo "deploy-marketing: ${path} did not return 200 (got: ${line})" >&2; exit 1 ;;
    esac
done

echo "==> Marketing site published. Purge Cloudflare cache for /ads.txt if it was ever fetched as a 404."
