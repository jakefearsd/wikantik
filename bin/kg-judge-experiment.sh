#!/bin/bash
# kg-judge-experiment.sh — sample pending kg_proposals rows and judge each row
# with both NoOpProposalJudge and a comparator (ollama or claude). Writes a
# side-by-side JSON report so the operator can decide whether to flip the
# production extractor's --judge default.
#
# Usage:
#   bin/kg-judge-experiment.sh --judge ollama --judge-model qwen3.5:9b \
#                              --sample 50 --output /tmp/judge.json
#   bin/kg-judge-experiment.sh --judge claude --judge-model claude-haiku-4-5 \
#                              --anthropic-key-env ANTHROPIC_API_KEY \
#                              --sample 50 --output /tmp/judge.json
#
# Same plumbing as bin/kg-extract.sh: rebuilds the extract-cli jar if stale,
# extracts DB credentials from ROOT.xml, exec's the JudgeExperimentCli main
# class. Pass --help to see the full flag set.

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
CONTEXT_XML="${ROOT_DIR}/tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml"

if [[ -t 1 ]]; then
    GREEN='\033[0;32m'
    YELLOW='\033[1;33m'
    RED='\033[0;31m'
    BOLD='\033[1m'
    NC='\033[0m'
else
    GREEN=''; YELLOW=''; RED=''; BOLD=''; NC=''
fi

info()  { echo -e "${GREEN}[kg-judge-experiment]${NC} $*"; }
warn()  { echo -e "${YELLOW}[kg-judge-experiment]${NC} $*" >&2; }
die()   { echo -e "${RED}[kg-judge-experiment]${NC} $*" >&2; exit 1; }

command -v java >/dev/null 2>&1 || die "java is not on PATH"
command -v mvn  >/dev/null 2>&1 || die "mvn is not on PATH (needed if the jar must be rebuilt)"

# Build the jar if it's missing or stale (any java source in the module or
# wikantik-main's extraction package newer than the jar triggers a rebuild).
needs_build=0
if [[ ! -f "${JAR}" ]]; then
    needs_build=1
elif find "${ROOT_DIR}/wikantik-extract-cli/src" -name '*.java' -newer "${JAR}" -print -quit | grep -q .; then
    needs_build=1
fi
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
# doesn't appear in /proc/*/cmdline.
export WIKANTIK_EXTRACT_PG_PASSWORD="${jdbc_password}"

extract_flag() {
    local flag="$1"; shift
    while [[ $# -gt 0 ]]; do
        if [[ "$1" == "${flag}" ]]; then echo "${2:-}"; return; fi
        shift
    done
}

judge=$(extract_flag --judge "$@");           judge="${judge:-<missing>}"
sample=$(extract_flag --sample "$@");         sample="${sample:-100}"
output=$(extract_flag --output "$@");         output="${output:-<missing>}"
model=$(extract_flag --judge-model "$@");     model="${model:-<default>}"

echo
echo -e "${BOLD}== Wikantik judge experiment =="
info "DB:        ${jdbc_url} as ${jdbc_user}"
info "Judge:     ${judge}    model: ${model}    sample: ${sample}"
info "Output:    ${output}"
info "Forwarded: $*"
echo

# Allow callers to opt into the Claude judge through this script without
# having to set -Dwikantik.kg.judge.allow_claude=true on the cmdline. The
# property still gates execution; this just bridges the CLI flag forward.
JAVA_OPTS="${JAVA_OPTS:-}"
if [[ "${judge}" == "claude" ]]; then
    JAVA_OPTS="${JAVA_OPTS} -Dwikantik.kg.judge.allow_claude=true"
fi

exec java ${JAVA_OPTS} \
    -cp "${JAR}" com.wikantik.extractcli.JudgeExperimentCli \
    --jdbc-url "${jdbc_url}" \
    --jdbc-user "${jdbc_user}" \
    --jdbc-password-env WIKANTIK_EXTRACT_PG_PASSWORD \
    "$@"
