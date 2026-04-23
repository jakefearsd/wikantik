#!/bin/bash
# runextractor.sh — launch the standalone entity-extractor CLI against the
# local Wikantik database with sensible defaults pulled from the existing
# Tomcat configuration.
#
# Usage:
#   bin/runextractor.sh                    # resume / incremental extraction
#   bin/runextractor.sh --force            # clear each chunk's prior mentions first
#   bin/runextractor.sh --concurrency 1    # single-in-flight for low GPU pressure
#   bin/runextractor.sh --help             # show full CLI help
#
# Behaviour:
#   - Builds wikantik-extract-cli if target/wikantik-extract-cli.jar is missing
#     or older than any Java source in the module.
#   - Extracts JDBC URL and password from
#     tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml (the local deploy's
#     context file). Falls back to PG_JDBC_URL / PG_USER / PG_PASSWORD env
#     vars if ROOT.xml is missing.
#   - Passes every other CLI flag straight through to the jar, so overrides
#     like --ollama-model or --concurrency work without editing the script.
#   - Everything logs to stdout. Pipe to tee if you want a file copy:
#        bin/runextractor.sh 2>&1 | tee extract-$(date +%Y%m%d-%H%M).log

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
JAR="${ROOT_DIR}/wikantik-extract-cli/target/wikantik-extract-cli.jar"
CONTEXT_XML="${ROOT_DIR}/tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml"

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

info()  { echo -e "${GREEN}[runextractor]${NC} $*"; }
warn()  { echo -e "${YELLOW}[runextractor]${NC} $*" >&2; }
die()   { echo -e "${RED}[runextractor]${NC} $*" >&2; exit 1; }

# Build the jar if it's missing or stale (any Java source in the module
# newer than the jar triggers a rebuild). Cheap for the common case.
needs_build=0
if [[ ! -f "${JAR}" ]]; then
    needs_build=1
elif find "${ROOT_DIR}/wikantik-extract-cli/src" -name '*.java' -newer "${JAR}" -print -quit | grep -q .; then
    needs_build=1
fi
# Also rebuild if the indexer / listener changed — the CLI links their
# bytecode through wikantik-main, so a stale wikantik-main jar silently
# ships old behaviour in the fat jar.
if [[ ${needs_build} -eq 0 ]]; then
    if find "${ROOT_DIR}/wikantik-main/src/main/java/com/wikantik/knowledge/extraction" \
         -name '*.java' -newer "${JAR}" -print -quit 2>/dev/null | grep -q .; then
        needs_build=1
    fi
fi
if [[ ${needs_build} -eq 1 ]]; then
    info "Building wikantik-extract-cli (jar is missing or stale)…"
    (
        cd "${ROOT_DIR}"
        mvn install -pl wikantik-extract-cli -am -Dmaven.test.skip -q
    ) || die "build failed — run 'mvn install -pl wikantik-extract-cli -am' for details"
fi

# Resolve DB credentials. Preferred source: the deployed ROOT.xml, which is
# already populated with the password the running server uses. Fall back to
# environment variables so the script is still usable on a fresh clone.
jdbc_url=""
jdbc_user=""
jdbc_password=""

if [[ -f "${CONTEXT_XML}" ]]; then
    jdbc_url=$(grep -oE 'url="[^"]+"' "${CONTEXT_XML}" | head -1 | sed -E 's/url="([^"]+)"/\1/')
    jdbc_user=$(grep -oE 'username="[^"]+"' "${CONTEXT_XML}" | head -1 | sed -E 's/username="([^"]+)"/\1/')
    jdbc_password=$(grep -oE 'password="[^"]+"' "${CONTEXT_XML}" | head -1 | sed -E 's/password="([^"]+)"/\1/')
fi

jdbc_url="${jdbc_url:-${PG_JDBC_URL:-jdbc:postgresql://localhost:5432/jspwiki}}"
jdbc_user="${jdbc_user:-${PG_USER:-jspwiki}}"
jdbc_password="${jdbc_password:-${PG_PASSWORD:-}}"

if [[ -z "${jdbc_password}" ]]; then
    die "No JDBC password available. Either deploy ROOT.xml or export PG_PASSWORD."
fi

# Push the password through an env var rather than the command line, so it
# doesn't appear in /proc/*/cmdline. --jdbc-password-env reads the var name
# and the CLI resolves it itself.
export WIKANTIK_EXTRACT_PG_PASSWORD="${jdbc_password}"

info "DB: ${jdbc_url} as ${jdbc_user}"
info "Launching: java -jar ${JAR} $*"
info "Progress lines will appear every --poll-seconds (default 30)."
echo

exec java -jar "${JAR}" \
    --jdbc-url "${jdbc_url}" \
    --jdbc-user "${jdbc_user}" \
    --jdbc-password-env WIKANTIK_EXTRACT_PG_PASSWORD \
    "$@"
