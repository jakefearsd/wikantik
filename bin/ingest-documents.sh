#!/bin/bash
# ingest-documents.sh — batch-ingest a folder of documents via POST /api/ingest.
#
# Walks a local directory recursively and POSTs each supported file
# (.pdf .txt .md .docx .pptx .xlsx) to the running Wikantik instance's
# REST ingest endpoint as a multipart/form-data upload.
#
# Usage:
#   bin/ingest-documents.sh --base-url http://localhost:8080 \
#                           --dir /data/docs \
#                           --user admin \
#                           --password secret
#   bin/ingest-documents.sh --base-url http://localhost:8080 \
#                           --dir /data/docs \
#                           --user admin \
#                           --password secret \
#                           --force
#   bin/ingest-documents.sh --help
#   bin/ingest-documents.sh --jar-help    # full Java CLI flag reference
#
# Auth:
#   --user / --password are sent as HTTP Basic auth (Authorization: Basic <base64>).
#   The /api/ingest endpoint requires admin or createPages permission.
#
# Build behaviour:
#   Rebuilds wikantik-extract-cli if target/wikantik-extract-cli.jar is
#   missing or older than any Java source in the module.
#
# Everything logs to stdout. Pipe to tee for a file copy:
#   bin/ingest-documents.sh … 2>&1 | tee ingest-$(date +%Y%m%d-%H%M).log

set -euo pipefail

case "${1:-}" in
    -h|--help)
        awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
        exit 0
        ;;
    --jar-help)
        shift
        set -- --help "$@"
        ;;
esac

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
JAR="${ROOT_DIR}/wikantik-extract-cli/target/wikantik-extract-cli.jar"

# Colour helpers; auto-disable when stdout is not a terminal.
if [[ -t 1 ]]; then
    GREEN='\033[0;32m'
    YELLOW='\033[1;33m'
    RED='\033[0;31m'
    BOLD='\033[1m'
    NC='\033[0m'
else
    GREEN=''; YELLOW=''; RED=''; BOLD=''; NC=''
fi

info() { echo -e "${GREEN}[ingest-docs]${NC} $*"; }
warn() { echo -e "${YELLOW}[ingest-docs]${NC} $*" >&2; }
die()  { echo -e "${RED}[ingest-docs]${NC} $*" >&2; exit 1; }

command -v java >/dev/null 2>&1 || die "java is not on PATH"
command -v mvn  >/dev/null 2>&1 || die "mvn is not on PATH (needed if the jar must be rebuilt)"

# Build the jar if it's missing or stale (any Java source in the module
# newer than the jar triggers a rebuild).
needs_build=0
if [[ ! -f "${JAR}" ]]; then
    needs_build=1
elif find "${ROOT_DIR}/wikantik-extract-cli/src" -name '*.java' -newer "${JAR}" -print -quit | grep -q .; then
    needs_build=1
fi
if [[ ${needs_build} -eq 1 ]]; then
    info "Building wikantik-extract-cli (jar is missing or stale)…"
    (
        cd "${ROOT_DIR}"
        mvn install -pl wikantik-extract-cli -am -Dmaven.test.skip -q
    ) || die "build failed — run 'mvn install -pl wikantik-extract-cli -am' for details"
fi

# Banner
echo
echo -e "${BOLD}== Wikantik document batch ingest =="
info "Forwarded flags: $*"
echo

# Wall-clock + exit-status summary on the way out.
SECONDS=0
trap '
    rc=$?
    elapsed_h=$(( SECONDS / 3600 ))
    elapsed_m=$(( ( SECONDS % 3600 ) / 60 ))
    elapsed_s=$(( SECONDS % 60 ))
    elapsed_str=$(printf "%dh%02dm%02ds" "$elapsed_h" "$elapsed_m" "$elapsed_s")
    if [[ $rc -eq 0 ]]; then
        info "Done in ${elapsed_str} (exit 0)."
    else
        warn "Exited with status ${rc} after ${elapsed_str}."
    fi
' EXIT

# Run with -cp so we can launch IngestDocumentsCli directly from the fat jar
# without making it the jar's default Main-Class (BootstrapExtractionCli keeps
# that role). All flags pass straight through to the Java CLI.
java -cp "${JAR}" com.wikantik.extractcli.IngestDocumentsCli "$@"
