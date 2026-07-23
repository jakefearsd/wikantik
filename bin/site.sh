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

if [[ "${SKIP_BUILD}" -eq 0 ]]; then
  echo "==> Phase 1: coverage build (unit)"
  "${AB}" start sitecov -- mvn clean install -Pcoverage -T 1C -DskipITs
  "${AB}" wait sitecov 1200 || { "${AB}" status sitecov; echo "site: coverage build failed" >&2; exit 1; }

  if [[ "${UNIT_ONLY}" -eq 0 ]]; then
    echo "==> Phase 1b: IT reactor under coverage (aggregate unit+IT)"
    # bin/run-tests.sh's arg parser rejects any argument it doesn't recognize
    # (its case statement's `*)` branch exits 2 on an unknown flag) — it does
    # NOT forward a trailing profile arg like -Pcoverage. So this runs the
    # same parallel IT reactor bin/run-tests.sh --parallel 4 uses, invoked
    # directly via mvn with the coverage profile folded in: the module-by-
    # module IT-coverage invocation wikantik-coverage-report/pom.xml documents.
    IT_MODULES="wikantik-it-tests/wikantik-it-test-rest,wikantik-it-tests/wikantik-it-test-sso,wikantik-it-tests/wikantik-it-test-knowledge-disabled,wikantik-it-tests/wikantik-it-test-custom-jdbc"
    "${AB}" start siteit -- mvn install -Pintegration-tests,coverage -fae -T 4 -pl "${IT_MODULES}"
    "${AB}" wait siteit 1800 || { "${AB}" status siteit; echo "site: IT coverage run failed" >&2; exit 1; }
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
"${AB}" wait sitegen 1800 || { "${AB}" status sitegen; echo "site: site generation failed" >&2; exit 1; }

echo "==> Site staged at: ${REPO_ROOT}/target/staging/index.html"
