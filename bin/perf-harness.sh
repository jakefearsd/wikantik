#!/usr/bin/env bash
# Backend performance harness (native wikilinks / embeds / reference scan / rename / Obsidian import).
#
#   bin/perf-harness.sh [scenario...]        # scenarios: resolver render embed htmlcache rename refscan import
#   PERF_LABEL=before bin/perf-harness.sh render
#   JFR=1 bin/perf-harness.sh render         # also writes target/perf/<label>-<scenario>.jfr per invocation
#   NO_COMPILE=1 bin/perf-harness.sh ...     # skip the test-compile step
#   PERF_JAVA_OPTS="-Dperf.notes=500" ...    # extra JVM options (perf.notes, perf.pages, perf.nodb)
#
# Compiles wikantik-main's test classes (module-scoped, no npm), then runs
# com.wikantik.perf.BackendPerfHarness on the test classpath from the module directory.
# Results are appended to wikantik-main/target/perf/results.txt as "label<TAB>name<TAB>metric<TAB>value".
# The harness is not a *Test class, so the normal surefire suite never runs it.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MOD="$ROOT/wikantik-main"
CP_FILE="$MOD/target/perf/classpath.txt"
mkdir -p "$MOD/target/perf"
if [[ -z "${NO_COMPILE:-}" ]]; then
  mvn -q -o test-compile -pl wikantik-main
fi
if [[ ! -s "$CP_FILE" || "$MOD/pom.xml" -nt "$CP_FILE" ]]; then
  mvn -q -o dependency:build-classpath -pl wikantik-main -Dmdep.includeScope=test -Dmdep.outputFile="$CP_FILE" >/dev/null
fi
CP="$MOD/target/test-classes:$MOD/target/classes:$(cat "$CP_FILE")"
MOCKITO="$(tr ':' '\n' < "$CP_FILE" | grep -m1 '/mockito-core-')"
LABEL="${PERF_LABEL:-run}"
JFR_OPT=()
if [[ -n "${JFR:-}" ]]; then
  JFR_OPT=("-XX:StartFlightRecording=filename=$MOD/target/perf/${LABEL}-${*// /_}.jfr,settings=profile")
fi
cd "$MOD"
exec java -Xmx4g -XX:+EnableDynamicAgentLoading -javaagent:"$MOCKITO" --add-modules=jdk.incubator.vector \
  -Dcom.wikantik.util.bcrypt.cost=4 -Djava.io.tmpdir="$MOD/target" -Dlog4j2.level=WARN \
  ${PERF_JAVA_OPTS:-} "${JFR_OPT[@]}" -cp "$CP" com.wikantik.perf.BackendPerfHarness "$@"
