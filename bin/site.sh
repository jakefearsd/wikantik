#!/usr/bin/env bash
#
# bin/site.sh — generate the Wikantik code-health site (target/staging/).
#
# Two phases:
#   1. Coverage build: produce jacoco.exec + surefire XML (unit; + Cargo IT
#      under -Pcoverage for the aggregate unit+IT coverage number).
#   2. Site: `mvn site site:stage -Pcoverage` — aggregate dashboard + per-module
#      drill-down assembled into target/staging/.
#
# Long build → always via bin/agent-build.sh (detached, survives harness kills).
#
# Usage:
#   bin/site.sh                 # full: unit+IT coverage, then site
#   bin/site.sh --unit-only     # skip the IT reactor (unit coverage only)
#   bin/site.sh --skip-build    # reuse existing exec/test data, regenerate site only
#   bin/site.sh -h|--help
set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

UNIT_ONLY=0; SKIP_BUILD=0
for arg in "$@"; do
  case "${arg}" in
    --unit-only) UNIT_ONLY=1 ;;
    --skip-build) SKIP_BUILD=1 ;;
    -h|--help) awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"; exit 0 ;;
    *) echo "site: unknown argument: ${arg}" >&2; exit 2 ;;
  esac
done

AB="bin/agent-build.sh"

# Block until a detached build actually finishes. bin/agent-build.sh's bounded
# `wait` returns 0=success, 1=failed, 2=still-running (budget elapsed). A full
# code-health site build can run far longer than any single budget, so on code 2
# we keep waiting and only abort on a real failure (code 1). $1=build name,
# $2=human label for the failure message.
wait_done() {
  local name="$1" label="$2" rc
  while :; do
    "${AB}" wait "${name}" 600; rc=$?
    case "${rc}" in
      0) return 0 ;;
      2) echo "    (${label} still running — continuing to wait…)" ;;
      *) "${AB}" status "${name}"; echo "site: ${label} failed" >&2; exit 1 ;;
    esac
  done
}

if [[ "${SKIP_BUILD}" -eq 0 ]]; then
  echo "==> Phase 1: coverage build (unit)"
  "${AB}" start sitecov -- mvn clean install -Pcoverage -T 1C -DskipITs
  wait_done sitecov "coverage build"

  if [[ "${UNIT_ONLY}" -eq 0 ]]; then
    echo "==> Phase 1b: IT reactor under coverage (aggregate unit+IT)"
    # Use the sanctioned parallel IT path (bin/run-tests.sh --parallel): it gives
    # each IT module build-helper-reserved ports + a uniquely-named pgvector
    # container. A raw `mvn -Pintegration-tests -T N` reactor over the IT modules
    # does NOT — concurrent modules would collide on ports/containers (see the
    # "Critical: Integration Test Parallelism" rule in CLAUDE.md). run-tests.sh's
    # arg parser rejects a trailing -Pcoverage, so the coverage profile is folded
    # in via Maven's MAVEN_ARGS env var (Maven 3.9+ folds it into every `mvn`
    # invocation run-tests.sh makes), matching the "run the IT modules with
    # -Pcoverage" path documented in wikantik-coverage-report/pom.xml.
    # --it (not bare --parallel): Phase 1 already ran + installed the unit
    # reactor, so run ONLY the default-gate IT modules here; bare --parallel
    # would re-run the whole unit suite.
    "${AB}" start siteit -- env MAVEN_ARGS=-Pcoverage bin/run-tests.sh --it --parallel 4
    wait_done siteit "IT coverage run"
    echo "==> Aggregate coverage exec"
    mvn -q -Pcoverage -pl wikantik-coverage-report \
        org.jacoco:jacoco-maven-plugin:report-aggregate
  fi
fi

# Module coupling graph (folded in from old Task 5). depgraph writes the reactor
# graph to target/dependency-graph.dot; copy it into the site's generated
# resources and render an SVG when graphviz is available (graphviz-optional).
echo "==> Module coupling graph"
IMG_DIR="${REPO_ROOT}/target/generated-site/resources/images"
mkdir -p "${IMG_DIR}"
mvn -q com.github.ferstl:depgraph-maven-plugin:4.0.3:aggregate \
    -Dincludes='com.wikantik*:*' -DshowGroupIds=false -DshowVersions=false -DmergeScopes=true
cp target/dependency-graph.dot "${IMG_DIR}/module-coupling.dot"
if command -v dot >/dev/null 2>&1; then
  dot -Tsvg "${IMG_DIR}/module-coupling.dot" -o "${IMG_DIR}/module-coupling.svg"
  echo "    rendered module-coupling.svg"
else
  echo "    graphviz 'dot' not found — linking .dot only (install graphviz for the SVG)"
fi

echo "==> Phase 2: site + stage"
"${AB}" start sitegen -- mvn site site:stage -Pcoverage
wait_done sitegen "site generation"

echo "==> Site staged at: ${REPO_ROOT}/target/staging/index.html"
