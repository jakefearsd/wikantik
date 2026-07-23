#!/usr/bin/env bash
#
# bin/deploy-site.sh — publish the generated code-health site to
# https://wikantik.com/site (served from the marketing nginx docroot).
#
# Source is target/staging/ (produced by bin/site.sh). This rsyncs the staged
# tree to an unprivileged staging dir on the marketing host, then copies it into
# the nginx docroot's /site subdir under sudo (docroot is www-data-owned) and
# chowns it back.
#
# The marketing host's sudo is NOT passwordless — the privileged copy runs over
# an interactive `ssh -t` session (you will be prompted). Run via
# `! bin/deploy-site.sh` inside a Claude session so the prompt is answerable.
#
# Usage: bin/deploy-site.sh [--dry-run] [-h|--help]
#
# Env overrides (defaults match the live setup):
#   MKT_HOST         ssh alias for the marketing box   (default: cloudflare)
#   MKT_DOCROOT      nginx docroot for the vhost        (default: /var/www/www.wikantik.com)
#   MKT_STAGING      unprivileged staging dir on host   (default: ~/wikantik-deploy/code-health-site)
#   MKT_OWNER        docroot owner:group                (default: www-data:www-data)
#   MKT_ORIGIN_PORT  nginx listen port for verify       (default: 8000)
#   SITE_SRC         local staged site dir              (default: target/staging)
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

MKT_HOST="${MKT_HOST:-cloudflare}"
MKT_DOCROOT="${MKT_DOCROOT:-/var/www/www.wikantik.com}"
MKT_STAGING="${MKT_STAGING:-~/wikantik-deploy/code-health-site}"
MKT_OWNER="${MKT_OWNER:-www-data:www-data}"
MKT_ORIGIN_PORT="${MKT_ORIGIN_PORT:-8000}"
SITE_SRC="${SITE_SRC:-target/staging}"
DRY_RUN=0

print_help() { awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"; }
for arg in "$@"; do
  case "${arg}" in
    --dry-run) DRY_RUN=1 ;;
    -h|--help) print_help; exit 0 ;;
    *) echo "deploy-site: unknown argument: ${arg}" >&2; exit 2 ;;
  esac
done
run() { if [[ "${DRY_RUN}" -eq 1 ]]; then echo "[dry-run] $*"; else "$@"; fi; }

if [[ "${DRY_RUN}" -eq 0 && ! -f "${SITE_SRC}/index.html" ]]; then
  echo "deploy-site: ${SITE_SRC}/index.html not found — run bin/site.sh first" >&2
  exit 1
fi

echo "==> Publishing code-health site to ${MKT_HOST}:${MKT_DOCROOT}/site"

# 1. rsync staged site -> unprivileged staging dir (trailing slash: contents).
run rsync -avz --delete-after "${SITE_SRC}/" "${MKT_HOST}:${MKT_STAGING}/"

# 2. Privileged copy staging -> docroot/site, restore www-data ownership.
REMOTE_PUBLISH="set -e
sudo mkdir -p '${MKT_DOCROOT}/site'
sudo rsync -a --delete ${MKT_STAGING}/ '${MKT_DOCROOT}/site/'
sudo chown -R ${MKT_OWNER} '${MKT_DOCROOT}/site'"
if [[ "${DRY_RUN}" -eq 1 ]]; then
  echo "[dry-run] ssh -t ${MKT_HOST} <<publish>>"
else
  ssh -t "${MKT_HOST}" "${REMOTE_PUBLISH}"
fi

# 3. On-origin verify (bypasses Cloudflare) that key pages return 200.
echo "==> Verifying on-origin (http://localhost:${MKT_ORIGIN_PORT}, Host: www.wikantik.com)"
if [[ "${DRY_RUN}" -eq 1 ]]; then echo "[dry-run] curl /site/index.html + /site/coupling.html"; exit 0; fi
for path in /site/index.html /site/coupling.html /site/pmd.html; do
  line="$(ssh "${MKT_HOST}" "curl -s -o /dev/null -w '%{http_code} %{content_type}' \
      -H 'Host: www.wikantik.com' http://localhost:${MKT_ORIGIN_PORT}${path}")"
  echo "    ${path} -> ${line}"
  case "${line}" in 200\ *) ;; *) echo "deploy-site: ${path} != 200 (${line})" >&2; exit 1 ;; esac
done
echo "==> Code-health site published to https://wikantik.com/site/"
