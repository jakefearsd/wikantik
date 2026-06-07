#!/usr/bin/env bash
#
# bin/indexnow-www.sh — submit www.wikantik.com URLs to IndexNow (Bing/Yandex/etc).
#
# IndexNow lets us notify participating search engines the instant content
# changes, instead of waiting for their crawler. Ownership is proven by hosting
# a key file at https://www.wikantik.com/<key>.txt — that file ships as part of
# the marketing WEB_FILES bundle (bin/deploy-marketing.sh), so deploy the site
# BEFORE running this. The URL list is read from marketing/sitemap.xml, so this
# always submits the current set of pages.
#
# Note: IndexNow notifies Bing/Yandex (and partners), NOT Google. Google
# discovery still relies on the sitemap + Search Console.
#
# Usage: bin/indexnow-www.sh
# Exit: 0 accepted (HTTP 200/202) · 1 failure.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOST="www.wikantik.com"
KEY="71ae77e77ccbdd4e052d901722cf22a8"
KEY_LOCATION="https://${HOST}/${KEY}.txt"
SITEMAP="${REPO_ROOT}/marketing/sitemap.xml"
ENDPOINT="https://api.indexnow.org/indexnow"

[[ -f "${SITEMAP}" ]] || { echo "indexnow-www: missing ${SITEMAP}" >&2; exit 1; }

mapfile -t URLS < <(grep -oE '<loc>[^<]+</loc>' "${SITEMAP}" | sed -E 's#</?loc>##g')
[[ ${#URLS[@]} -gt 0 ]] || { echo "indexnow-www: no <loc> URLs in sitemap" >&2; exit 1; }

# The key file must be live before submitting, or IndexNow rejects the batch.
got="$(curl -s --max-time 20 "${KEY_LOCATION}" | tr -d '[:space:]' || true)"
if [[ "${got}" != "${KEY}" ]]; then
    echo "indexnow-www: key file not live at ${KEY_LOCATION} (got: '${got:0:40}')." >&2
    echo "  Deploy the marketing bundle first (bin/deploy-marketing.sh), then re-run." >&2
    exit 1
fi

url_json="$(printf '"%s",' "${URLS[@]}" | sed 's/,$//')"
body="{\"host\":\"${HOST}\",\"key\":\"${KEY}\",\"keyLocation\":\"${KEY_LOCATION}\",\"urlList\":[${url_json}]}"

echo "==> Submitting ${#URLS[@]} URLs to IndexNow (${ENDPOINT})"
resp_file="$(mktemp)"
code="$(curl -s -o "${resp_file}" -w '%{http_code}' -X POST "${ENDPOINT}" \
    -H 'Content-Type: application/json; charset=utf-8' --data "${body}")"
echo "    HTTP ${code}"
[[ -s "${resp_file}" ]] && { echo "    response:"; sed 's/^/      /' "${resp_file}"; }
rm -f "${resp_file}"

case "${code}" in
    200|202) echo "==> Accepted. (200 = OK, 202 = received, validation pending.)" ;;
    *) echo "indexnow-www: submission not accepted (HTTP ${code})" >&2; exit 1 ;;
esac
