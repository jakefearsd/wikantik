#!/usr/bin/env bash
#
# bin/deploy-site.sh — publish ONLY the generated code-health site to
# https://wikantik.com/site.
#
# This is a thin delegator. The marketing site and the code-health site share
# one host and one nginx docroot, so they are deployed by one script:
#
#   bin/deploy-marketing.sh              marketing + code-health site (default)
#   bin/deploy-marketing.sh --site-only  just the code-health site  <- this script
#
# Kept because the name is referenced from CLAUDE.md, docs/ProjectReference.md
# and src/site/markdown/index.md, and because publishing the code-health site
# alone is a genuine case (you rebuilt reports without touching marketing).
# There is no second implementation — everything below is handled by
# deploy-marketing.sh, including the requirement that bin/site.sh has run.
#
# Usage: bin/deploy-site.sh [--dry-run] [-h|--help]
#
# Env overrides are the same as deploy-marketing.sh (MKT_HOST, MKT_DOCROOT,
# MKT_SITE_STAGING, MKT_OWNER, MKT_ORIGIN_PORT, SITE_SRC).

set -euo pipefail

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
    exit 0
fi

exec "$(dirname "${BASH_SOURCE[0]}")/deploy-marketing.sh" --site-only "$@"
