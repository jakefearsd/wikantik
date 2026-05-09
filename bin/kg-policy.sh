#!/bin/bash
# kg-policy.sh — admin CLI for the KG inclusion / exclusion policy.
#
# Subcommands: list, set, clear, explain, review, mark-reviewed,
#              diff, reconcile, purge, audit.
#
# JDBC config is discovered the same way as kg-extract.sh:
#   1) tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml (preferred — uses the
#      live deploy's credentials)
#   2) PG_JDBC_URL / PG_USER / PG_PASSWORD environment variables
# Override per-invocation with --jdbc-url / --jdbc-user / --jdbc-password-env.
#
# Examples:
#   bin/kg-policy.sh list
#   bin/kg-policy.sh set java include --reason "core tech for agent retrieval"
#   bin/kg-policy.sh explain van-life
#   bin/kg-policy.sh purge --reason system_page --confirm
#
# Build behaviour: rebuilds wikantik-extract-cli if the jar is missing or
# older than any Java source in the module (or in
# wikantik-main/src/main/java/com/wikantik/kgpolicy, since the CLI links
# repository classes from wikantik-main).

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
    GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
else
    GREEN=''; YELLOW=''; RED=''; NC=''
fi
info() { echo -e "${GREEN}[kg-policy]${NC} $*"; }
warn() { echo -e "${YELLOW}[kg-policy]${NC} $*" >&2; }
die()  { echo -e "${RED}[kg-policy]${NC} $*" >&2; exit 1; }

command -v java >/dev/null 2>&1 || die "java is not on PATH"
command -v mvn  >/dev/null 2>&1 || die "mvn is not on PATH (needed if the jar must be rebuilt)"

# Build the jar if it's missing or stale.
needs_build=0
if [[ ! -f "${JAR}" ]]; then
    needs_build=1
elif find "${ROOT_DIR}/wikantik-extract-cli/src" -name '*.java' -newer "${JAR}" -print -quit | grep -q .; then
    needs_build=1
elif find "${ROOT_DIR}/wikantik-main/src/main/java/com/wikantik/kgpolicy" \
        -name '*.java' -newer "${JAR}" -print -quit 2>/dev/null | grep -q .; then
    needs_build=1
fi
if [[ ${needs_build} -eq 1 ]]; then
    info "Building wikantik-extract-cli (jar is missing or stale)…"
    (
        cd "${ROOT_DIR}"
        mvn install -pl wikantik-extract-cli -am -Dmaven.test.skip -q
    ) || die "build failed — run 'mvn install -pl wikantik-extract-cli -am' for details"
fi

# JDBC discovery (matches kg-extract.sh).
jdbc_url=""; jdbc_user=""; jdbc_password=""
if [[ -f "${CONTEXT_XML}" ]]; then
    jdbc_url=$(grep -oE 'url="[^"]+"' "${CONTEXT_XML}" | head -1 | sed -E 's/url="([^"]+)"/\1/')
    jdbc_user=$(grep -oE 'username="[^"]+"' "${CONTEXT_XML}" | head -1 | sed -E 's/username="([^"]+)"/\1/')
    jdbc_password=$(grep -oE 'password="[^"]+"' "${CONTEXT_XML}" | head -1 | sed -E 's/password="([^"]+)"/\1/')
fi
jdbc_url="${jdbc_url:-${PG_JDBC_URL:-jdbc:postgresql://localhost:5432/jspwiki}}"
jdbc_user="${jdbc_user:-${PG_USER:-jspwiki}}"
jdbc_password="${jdbc_password:-${PG_PASSWORD:-}}"
[[ -z "${jdbc_password}" ]] && die "No JDBC password available. Either deploy ROOT.xml or export PG_PASSWORD."

# Allow per-invocation override via flags. The Java side honours these;
# we just pass them through.
java -cp "${JAR}" com.wikantik.extractcli.KgPolicyCli \
    --jdbc-url "${jdbc_url}" \
    --jdbc-user "${jdbc_user}" \
    --jdbc-password "${jdbc_password}" \
    "$@"
