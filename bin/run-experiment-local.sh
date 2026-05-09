#!/bin/bash
#
# run-experiment-local.sh — thin wrapper that invokes
# bin/run-embedding-experiment.sh with credentials sourced from the
# operator's local config:
#   - DB password from tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml
#   - testbot login / password from test.properties
#
# Usage:
#   bin/run-experiment-local.sh                   # full run
#   SKIP_INDEX=1 bin/run-experiment-local.sh      # re-score existing embeddings
#   MODELS=bge-m3 bin/run-experiment-local.sh     # single-model run
#   bin/run-experiment-local.sh --help            # show this help
#
# All flags after the script name are forwarded to
# bin/run-embedding-experiment.sh.

set -euo pipefail

case "${1:-}" in
    -h|--help)
        awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
        exit 0
        ;;
esac

cd "$(dirname "$0")/.."

if [[ ! -f test.properties ]]; then
    echo "test.properties missing — cannot source testbot credentials" >&2
    exit 1
fi

CONTEXT_XML="tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml"
if [[ ! -f "${CONTEXT_XML}" ]]; then
    echo "${CONTEXT_XML} missing — first-time deploy required (run bin/deploy-local.sh)" >&2
    exit 1
fi

DB_PASSWORD=$(grep -oE 'password="[^"]+"' "${CONTEXT_XML}" | head -1 \
              | sed -E 's/password="([^"]+)"/\1/')
if [[ -z "${DB_PASSWORD}" ]]; then
    echo "Could not extract DB password from ${CONTEXT_XML}" >&2
    exit 1
fi

# shellcheck disable=SC1091
source <(grep -v '^#' test.properties | sed 's/^test.user.//' | sed 's/=/="/' | sed 's/$/"/')

export DB_PASSWORD
export WIKI_USER="${login}"
export WIKI_PASSWORD="${password}"

exec bin/run-embedding-experiment.sh "$@"
