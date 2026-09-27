#!/usr/bin/env bash
#
# kg-materialize-approved.sh — replay machine-approved new-node proposals into kg_nodes.
#
# Until 2026-09-26 KgMaterializationService.materialize handled only 'new-edge', so
# approving a 'new-node' proposal wrote nothing at all: nodes existed only as a side
# effect of edge materialisation. Fixing the materialiser does not create the missing
# rows retroactively, which is what this replays. It performs NO LLM inference — every
# proposal was already extracted and judged, so this is pure database work.
#
# Scope: status='pending' AND machine_status='approved' AND proposal_type='new-node'.
# Predicating on 'pending' is what keeps human verdicts authoritative — proposals a
# human has since REJECTED are excluded by construction, even though the machine
# approved them. new-edge proposals are deliberately untouched: edge materialisation
# always worked, and re-running those would fire needless ontology re-projections.
#
# Usage:
#   bin/kg-materialize-approved.sh --dry-run        # report scope, write nothing
#   bin/kg-materialize-approved.sh --limit 100      # materialise the first 100 only
#   bin/kg-materialize-approved.sh                  # replay everything in scope
#
# Safe to re-run: the node upsert is ON CONFLICT ( name ), pinned by
# KgMaterializationServiceMaterializeTest.materializeMachine_new_node_is_idempotent.
#
# Build behaviour: rebuilds wikantik-extract-cli if the jar is missing or stale.
#
# Exit codes: 0 completed · 1 one or more proposals failed · 2 bad arguments
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="${ROOT_DIR}/wikantik-extract-cli/target/wikantik-extract-cli.jar"
CONTEXT_XML="${ROOT_DIR}/tomcat/tomcat-11/conf/Catalina/localhost/ROOT.xml"

if [[ -t 1 ]]; then GREEN='\033[0;32m'; RED='\033[0;31m'; NC='\033[0m'; else GREEN=''; RED=''; NC=''; fi
info() { echo -e "${GREEN}[kg-materialize]${NC} $*"; }
die()  { echo -e "${RED}[kg-materialize]${NC} $*" >&2; exit 1; }

case "${1:-}" in
    -h|--help)
        awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
        exit 0
        ;;
esac

command -v java >/dev/null 2>&1 || die "java is not on PATH"
command -v mvn  >/dev/null 2>&1 || die "mvn is not on PATH (needed if the jar must be rebuilt)"

needs_build=0
if [[ ! -f "${JAR}" ]]; then
    needs_build=1
elif find "${ROOT_DIR}/wikantik-extract-cli/src" -name '*.java' -newer "${JAR}" -print -quit | grep -q .; then
    needs_build=1
elif find "${ROOT_DIR}/wikantik-main/src/main/java/com/wikantik/knowledge/judge" \
        -name '*.java' -newer "${JAR}" -print -quit 2>/dev/null | grep -q .; then
    needs_build=1
fi
if [[ ${needs_build} -eq 1 ]]; then
    info "Building wikantik-extract-cli (jar is missing or stale)…"
    ( cd "${ROOT_DIR}" && mvn install -pl wikantik-extract-cli -am -DskipTests -q ) \
        || die "build failed — run 'mvn install -pl wikantik-extract-cli -am' for details"
fi

# JDBC discovery (matches kg-policy.sh / kg-extract.sh).
jdbc_url=""; jdbc_user=""; jdbc_password=""
if [[ -f "${CONTEXT_XML}" ]]; then
    jdbc_url=$(grep -oE 'url="[^"]+"' "${CONTEXT_XML}" | head -1 | sed -E 's/url="([^"]+)"/\1/')
    jdbc_user=$(grep -oE 'username="[^"]+"' "${CONTEXT_XML}" | head -1 | sed -E 's/username="([^"]+)"/\1/')
    jdbc_password=$(grep -oE 'password="[^"]+"' "${CONTEXT_XML}" | head -1 | sed -E 's/password="([^"]+)"/\1/')
fi
jdbc_url="${jdbc_url:-${PG_JDBC_URL:-jdbc:postgresql://localhost:5432/wikantik}}"
jdbc_user="${jdbc_user:-${PG_USER:-wikantik}}"
jdbc_password="${jdbc_password:-${PG_PASSWORD:-}}"
[[ -z "${jdbc_password}" ]] && die "No JDBC password available. Either deploy ROOT.xml or export PG_PASSWORD."

java -cp "${JAR}" com.wikantik.extractcli.MaterializeApprovedProposalsCli \
    --jdbc-url "${jdbc_url}" \
    --jdbc-user "${jdbc_user}" \
    --jdbc-password "${jdbc_password}" \
    "$@"
