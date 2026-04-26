#!/bin/bash
# kg-extract.sh — launch the standalone entity-extractor CLI against the
# local Wikantik database with sensible defaults pulled from the existing
# Tomcat configuration.
#
# Usage:
#   bin/kg-extract.sh                    # resume / incremental extraction
#   bin/kg-extract.sh --force            # clear each chunk's prior mentions first
#   bin/kg-extract.sh --concurrency 1    # single-in-flight for low GPU pressure
#   bin/kg-extract.sh --help             # show full CLI help
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
#        bin/kg-extract.sh 2>&1 | tee extract-$(date +%Y%m%d-%H%M).log

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
JAR="${ROOT_DIR}/wikantik-extract-cli/target/wikantik-extract-cli.jar"
CONTEXT_XML="${ROOT_DIR}/tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml"

# Colour helpers; auto-disable when stdout is not a terminal so piping/teeing
# to a log file doesn't fill it with escape sequences.
if [[ -t 1 ]]; then
    GREEN='\033[0;32m'
    YELLOW='\033[1;33m'
    RED='\033[0;31m'
    BOLD='\033[1m'
    NC='\033[0m'
else
    GREEN=''; YELLOW=''; RED=''; BOLD=''; NC=''
fi

info()  { echo -e "${GREEN}[kg-extract]${NC} $*"; }
warn()  { echo -e "${YELLOW}[kg-extract]${NC} $*" >&2; }
die()   { echo -e "${RED}[kg-extract]${NC} $*" >&2; exit 1; }

# Pre-flight: java is the actual runtime; without it nothing else matters.
command -v java >/dev/null 2>&1 || die "java is not on PATH"
command -v mvn  >/dev/null 2>&1 || die "mvn is not on PATH (needed if the jar must be rebuilt)"

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

# Surface the resolved settings in a banner. The CLI itself will log a
# matching "Extract-CLI starting" line; this one is what an operator sees
# *before* the JVM warms up, so they know which run they kicked off if
# they're juggling several windows.
extract_flag() {
    # extract_flag <flag-name> <args…> → echoes the value following <flag-name>
    # in <args>, or empty if not present. Handles --foo bar form only (the
    # CLI does not accept --foo=bar).
    local flag="$1"; shift
    while [[ $# -gt 0 ]]; do
        if [[ "$1" == "${flag}" ]]; then echo "${2:-}"; return; fi
        shift
    done
}
has_flag() {
    local flag="$1"; shift
    for a in "$@"; do [[ "$a" == "${flag}" ]] && return 0; done
    return 1
}

backend=$(extract_flag --backend "$@");        backend="${backend:-ollama}"
model=$(extract_flag --ollama-model "$@");     model="${model:-gemma4-assist:latest}"
[[ "${backend}" == "claude" ]] && model="$(extract_flag --claude-model "$@")" \
    && model="${model:-claude-haiku-4-5}"
concurrency=$(extract_flag --concurrency "$@"); concurrency="${concurrency:-2}"
prefilter="off"
if has_flag --prefilter-dry-run "$@"; then prefilter="dry-run"
elif has_flag --prefilter "$@";          then prefilter="on"
fi
force="no"; has_flag --force "$@" && force="yes"

echo
echo -e "${BOLD}== Wikantik entity extraction =="
info "DB:          ${jdbc_url} as ${jdbc_user}"
info "Backend:     ${backend}  model: ${model}"
info "Concurrency: ${concurrency}  prefilter: ${prefilter}  force: ${force}"
info "Forwarded:   $*"
info "Progress lines arrive every --poll-seconds (default 30s)."
info "Ctrl-C asks the indexer to cancel between chunks (in-flight RPC finishes first)."
echo

# Wall-clock + exit-status summary on the way out, regardless of cause
# (clean finish, error, or Ctrl-C). Bash's SECONDS builtin is integer; good
# enough for runs measured in minutes-to-hours.
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

# Run the jar as a child (NOT exec) so the EXIT trap fires with the jar's
# real exit code. SIGINT/SIGTERM flow through to the child because they
# arrive at the foreground process group, and the JVM's shutdown hook
# (added by BootstrapExtractionCli) requests a graceful cancel.
java -jar "${JAR}" \
    --jdbc-url "${jdbc_url}" \
    --jdbc-user "${jdbc_user}" \
    --jdbc-password-env WIKANTIK_EXTRACT_PG_PASSWORD \
    "$@"
