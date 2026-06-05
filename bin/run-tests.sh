#!/usr/bin/env bash
# Wikantik full test-suite runner.
#
# Runs the suite the ONLY way that is correct and fast here:
#   Phase 1 (unit): one parallel reactor build that compiles every module, runs
#     all unit tests, and INSTALLS the artifacts (incl. the WAR) to ~/.m2.
#   Phase 2 (IT):   each integration-test module in turn — SEQUENTIALLY by
#     default. Each runs with `-pl <module>` and NO `-am`, so the ~6000 unit
#     tests are NOT re-run during the IT phase (they already passed and are
#     installed). This is dramatically faster than a single
#     `mvn clean install -Pintegration-tests` reactor, and it fits within
#     wall-clock limits because each phase/module is a bounded build.
#     Pass --parallel N to instead run all IT modules in one -T N reactor:
#     each module now reserves its own free ports + uniquely-named pgvector
#     container, so they no longer collide.
#
# Why a script: the full reactor cannot complete in one long-lived call in some
# environments (it gets killed mid-build). Splitting into bounded steps with an
# aggregated summary makes a full run reliable, scriptable, and CI/remote-friendly.
#
# Usage:
#   bin/run-tests.sh                 # full suite: unit phase, then every IT module
#   bin/run-tests.sh --unit          # unit phase only (Phase 1)
#   bin/run-tests.sh --it            # IT phase only (assumes a prior --unit installed artifacts)
#   bin/run-tests.sh --module rest   # IT phase for one module: rest|sso|custom-jdbc|scim-fullloop
#   bin/run-tests.sh --it --parallel 4   # opt-in: all IT modules in one -T 4 reactor
#                                        # (-p 4 short form; or IT_PARALLELISM=4 env — flag wins)
#   bin/run-tests.sh --help
#
# Exit code: 0 only if every phase/module that ran reached BUILD SUCCESS with no
# test failures; non-zero otherwise. A per-run summary is written to
# target/test-suite-report.txt and printed at the end.
set -uo pipefail   # NOT -e: we want to run every module and aggregate, not bail early.

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_DIR"
# Logs/report live OUTSIDE any target/ dir — `mvn clean` wipes target/ at the
# start of the build, which would unlink an in-progress log. This dir is gitignored.
LOG_DIR="${REPO_DIR}/.test-suite-logs"
REPORT="${LOG_DIR}/report.txt"
mkdir -p "$LOG_DIR"

# Refuse to run concurrently with another instance on the same checkout. Parallel
# Maven builds on one working tree clobber each other's surefire temp/classpath,
# which surfaces as `ForkStarter IOException: No value present` + a cascade of
# NoClassDefFound for classes that are actually present and compiled. One at a time.
exec 9>"${LOG_DIR}/.run.lock"
if command -v flock >/dev/null 2>&1 && ! flock -n 9; then
  echo "ERROR: another bin/run-tests.sh is already running on this checkout (lock: ${LOG_DIR}/.run.lock)." >&2
  echo "       Wait for it to finish — concurrent Maven builds corrupt each other. Refusing to run." >&2
  exit 3
fi

# IT modules in their required sequential order (custom-jdbc runs the Selenide
# browser suite via the shared wikantik-selenide-tests jar).
IT_MODULES=(
  "wikantik-it-tests/wikantik-it-test-rest"
  "wikantik-it-tests/wikantik-it-test-sso"
  "wikantik-it-tests/wikantik-it-test-custom-jdbc"
)

RUN_UNIT=1
RUN_IT=1
ONE_MODULE=""

# Unit-phase build parallelism. Default 1C (one thread per core). The prior
# cap at 0.5C worked around intermittent "ForkStarter IOException: No value
# present" crashes caused by a race condition in maven-surefire-junit5-tree-reporter
# (ConsoleTreeReporter.testSetCompleted calling Optional.get() on an empty
# Optional under parallel load). The reporter has been removed from the surefire
# configuration (replaced by surefire's built-in plain reporter) and the TCP
# fork-node (SurefireForkNodeFactory) added. Seven consecutive -T 1C runs produced
# zero ForkStarter IOExceptions after the fix. Override with UNIT_PARALLELISM=1
# (serial) if debugging a specific test ordering issue.
UNIT_PARALLELISM="${UNIT_PARALLELISM:-1C}"

# IT-phase parallelism (opt-in). Default 1 = the safe sequential per-module loop
# (one module at a time). Set via --parallel N (preferred) or the IT_PARALLELISM
# env var as a fallback default; an explicit --parallel flag wins. N>1 runs a
# SINGLE `mvn install -Pintegration-tests -T N` reactor over all IT modules at
# once; each module reserves its own free ports + uniquely-named pgvector
# container (build-helper reserve-network-port), so they no longer collide.
# One Maven process, so the surefire/failsafe fork-node fix handles the forks.
IT_PARALLELISM="${IT_PARALLELISM:-1}"

# Build-output routing: file (log only, default) | console (stdout/err only) | both (tee).
OUTPUT_MODE="${OUTPUT_MODE:-file}"

# Parse args as a shift-loop so flags (--parallel) can combine with mode tokens
# (--it / --unit / --module). A single positional `case` could only express one.
while [ $# -gt 0 ]; do
  case "$1" in
    --help|-h) sed -n '2,32p' "$0"; exit 0 ;;
    --unit)    RUN_IT=0 ;;
    --it)      RUN_UNIT=0 ;;
    --module)  RUN_UNIT=0; RUN_IT=0; ONE_MODULE="${2:-}"; shift
               [ -n "$ONE_MODULE" ] || { echo "--module needs a name (rest|sso|custom-jdbc|scim-fullloop)" >&2; exit 2; } ;;
    --parallel|-p)
               IT_PARALLELISM="${2:-}"; shift
               case "$IT_PARALLELISM" in
                 ''|*[!0-9]*) echo "--parallel needs a positive integer (e.g. --parallel 4)" >&2; exit 2 ;;
               esac
               [ "$IT_PARALLELISM" -ge 1 ] || { echo "--parallel needs a positive integer (e.g. --parallel 4)" >&2; exit 2; } ;;
    --output|-o)
               OUTPUT_MODE="${2:-}"; shift
               case "$OUTPUT_MODE" in
                 file|console|both) ;;
                 *) echo "--output needs one of: file | console | both" >&2; exit 2 ;;
               esac ;;
    "" ) ;;
    *) echo "unknown argument: $1 (try --help)" >&2; exit 2 ;;
  esac
  shift
done

# Kill only OUR stray maven/surefire/cargo JVMs — never the dev Tomcat or app.jar.
clean_zombies() {
  pkill -9 -f "surefire.*booter|plexus.classworlds|org.codehaus.cargo" 2>/dev/null || true
  rm -rf wikantik-main/target/test-classes 2>/dev/null || true
}

: > "$REPORT"
overall_rc=0
run_start_epoch="$(date +%s)"

# Format a duration (seconds) as "Xm YYs".
fmt_dur() { printf '%dm %02ds' $(( $1 / 60 )) $(( $1 % 60 )); }

# Run one maven step, tee to a log, record PASS/FAIL + elapsed + the
# failsafe/surefire "Tests run" tail. $1=label  $2=logfile  rest=mvn args
run_step() {
  local label="$1"; shift
  local log="$1"; shift
  echo ">>> ${label}"
  clean_zombies
  local t0 dur; t0="$(date +%s)"
  local rc
  case "$OUTPUT_MODE" in
    file)
      mvn "$@" > "$log" 2>&1
      rc=$?
      ;;
    console)
      mvn "$@" 2>&1
      rc=$?
      ;;
    both)
      mvn "$@" 2>&1 | tee "$log"
      rc=${PIPESTATUS[0]}
      ;;
  esac
  if [ "$rc" -eq 0 ]; then
    dur="$(fmt_dur $(( $(date +%s) - t0 )) )"
    local summary
    summary="$(grep -E 'Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+' "$log" 2>/dev/null | tail -1)"
    echo "PASS  ${label}  [${dur}]   ${summary}" | tee -a "$REPORT"
  else
    overall_rc=1
    dur="$(fmt_dur $(( $(date +%s) - t0 )) )"
    local fails
    fails="$(grep -E 'Tests run:.*(Failures: [1-9]|Errors: [1-9])|BUILD FAILURE' "$log" 2>/dev/null | head -5)"
    echo "FAIL  ${label}  [${dur}]" | tee -a "$REPORT"
    [ -n "$fails" ] && echo "${fails}" | sed 's/^/        /' | tee -a "$REPORT"
    echo "        (log: ${log})" | tee -a "$REPORT"
  fi
}

start_ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo "Wikantik test suite — started ${start_ts}" | tee -a "$REPORT"

if [ "$RUN_UNIT" = 1 ]; then
  # Parallel unit reactor: compile all, run unit tests, install artifacts (incl. WAR).
  run_step "Phase 1: unit reactor (-T ${UNIT_PARALLELISM} -DskipITs)" "${LOG_DIR}/phase1-unit.log" \
    clean install -T "${UNIT_PARALLELISM}" -DskipITs
fi

if [ "$RUN_IT" = 1 ]; then
  if [ "$IT_PARALLELISM" -gt 1 ] 2>/dev/null; then
    # Single parallel reactor over all IT modules. Each module reserves its own
    # ports + container name, so -T N cannot collide. -fae so every module runs
    # even if one fails. -pl <all four>, NO -am (deps already installed by
    # Phase 1), so the ~6000 unit tests are not re-run.
    it_pl="$(IFS=,; echo "${IT_MODULES[*]}")"
    run_step "IT (parallel x${IT_PARALLELISM})" "${LOG_DIR}/it-parallel.log" \
      install -Pintegration-tests -fae -T "${IT_PARALLELISM}" -pl "$it_pl"
  else
    for mod in "${IT_MODULES[@]}"; do
      # -pl <module> WITHOUT -am: deps resolve from the Phase-1 install, so unit
      # tests are not re-run. Sequential (default). -fae within the module.
      run_step "IT: ${mod}" "${LOG_DIR}/it-$(basename "$mod").log" \
        install -Pintegration-tests -fae -pl "$mod"
    done
  fi
elif [ -n "$ONE_MODULE" ]; then
  mod="wikantik-it-tests/wikantik-it-test-${ONE_MODULE}"
  [ -d "$mod" ] || { echo "no such IT module: $mod" >&2; exit 2; }
  run_step "IT: ${mod}" "${LOG_DIR}/it-${ONE_MODULE}.log" \
    install -Pintegration-tests -fae -pl "$mod"
fi

total_dur="$(fmt_dur $(( $(date +%s) - run_start_epoch )) )"
echo "Wikantik test suite — finished $(date -u +%Y-%m-%dT%H:%M:%SZ) (started ${start_ts})" | tee -a "$REPORT"
echo "Total runtime: ${total_dur}" | tee -a "$REPORT"
echo "---------- SUMMARY ----------"
cat "$REPORT"
[ "$overall_rc" = 0 ] && echo "RESULT: ALL PASSED" || echo "RESULT: FAILURES (see above)"
echo "TOTAL RUNTIME: ${total_dur}"
exit "$overall_rc"
