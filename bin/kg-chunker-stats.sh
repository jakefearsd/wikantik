#!/bin/bash
# kg-chunker-stats.sh — inspect chunk-size distribution for the page corpus
# without touching the database. Pure in-memory re-chunk + prefilter eval;
# useful for tuning chunker config before committing GPU hours to a real
# extraction pass.
#
# Split out of bin/kg-extract.sh during the per-page extraction redesign
# (Phase 4 of docs/superpowers/plans/2026-05-01-kg-extraction-redesign.md).
#
# Usage:
#   bin/kg-chunker-stats.sh                                           # defaults (docs/wikantik-pages)
#   bin/kg-chunker-stats.sh --pages-dir docs/wikantik-pages
#   bin/kg-chunker-stats.sh --chunker-max-tokens 1024 --chunker-merge-forward-tokens 200
#   bin/kg-chunker-stats.sh --help

set -euo pipefail

case "${1:-}" in
    -h|--help)
        awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
        exit 0
        ;;
    --jar-help)
        shift; set -- --help "$@"
        ;;
esac

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
JAR="${ROOT_DIR}/wikantik-extract-cli/target/wikantik-extract-cli.jar"

# Build the jar if missing or stale (any Java source in the module newer than
# the jar triggers a rebuild). Cheap for the common case.
needs_build=0
if [[ ! -f "${JAR}" ]]; then
    needs_build=1
elif find "${ROOT_DIR}/wikantik-extract-cli/src" -name '*.java' -newer "${JAR}" -print -quit | grep -q .; then
    needs_build=1
fi
if [[ ${needs_build} -eq 1 ]]; then
    (cd "${ROOT_DIR}" && mvn install -pl wikantik-extract-cli -am -Dmaven.test.skip -q)
fi

java -cp "${JAR}" com.wikantik.extractcli.ChunkerStatsCli "$@"
