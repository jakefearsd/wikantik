#!/bin/bash
# config-reference.sh — regenerate docs/ConfigurationReference.md and the wiki
# page docs/wikantik-pages/WikantikConfigurationReference.md from the three
# defaults files (ini/wikantik.properties, wikantik-mcp.properties,
# wikantik-tools.properties) via GenerateConfigReferenceCli.
#
# Usage:
#   bin/config-reference.sh [--write|--check]
#
#   --write  (default): overwrite the two generated docs.
#   --check: report whether the committed docs match the generator; exits 0
#            when in sync, 1 when stale. (ConfigReferenceRegressionTest calls
#            the CLI class directly in --check mode; this flag is for
#            interactive/CI use of the same check.)
#
# Build behaviour: rebuilds wikantik-extract-cli if the jar is missing or
# older than any Java source in the module, any .mustache template in the
# module's resources, or any Java source in wikantik-util's config package
# (ConfigReference itself).

set -euo pipefail

case "${1:-}" in
    -h|--help)
        awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
        exit 0
        ;;
esac

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
JAR="${ROOT_DIR}/wikantik-extract-cli/target/wikantik-extract-cli.jar"

if [[ -t 1 ]]; then
    GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'
else
    GREEN=''; YELLOW=''; RED=''; NC=''
fi
info() { echo -e "${GREEN}[config-reference]${NC} $*"; }
warn() { echo -e "${YELLOW}[config-reference]${NC} $*" >&2; }
die()  { echo -e "${RED}[config-reference]${NC} $*" >&2; exit 1; }

command -v java >/dev/null 2>&1 || die "java is not on PATH"
command -v mvn  >/dev/null 2>&1 || die "mvn is not on PATH (needed if the jar must be rebuilt)"

# Build the jar if it's missing or stale.
needs_build=0
if [[ ! -f "${JAR}" ]]; then
    needs_build=1
elif find "${ROOT_DIR}/wikantik-extract-cli/src" -name '*.java' -newer "${JAR}" -print -quit | grep -q .; then
    needs_build=1
elif find "${ROOT_DIR}/wikantik-extract-cli/src/main/resources" -name '*.mustache' -newer "${JAR}" -print -quit | grep -q .; then
    needs_build=1
elif find "${ROOT_DIR}/wikantik-util/src/main/java/com/wikantik/util/config" \
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

mode="--write"
case "${1:-}" in
    --write|"") mode="--write" ;;
    --check)    mode="--check" ;;
    *)          die "unknown flag: $1 (expected --write or --check)" ;;
esac

java -cp "${JAR}" com.wikantik.extractcli.configref.GenerateConfigReferenceCli "${ROOT_DIR}" "${mode}"
