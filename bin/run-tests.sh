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
#   bin/run-tests.sh                 # DEFAULT GATE: unit phase + deterministic IT modules
#                                    #   (rest, sso, knowledge-disabled, custom-jdbc, dense) — the pre-commit run
#   bin/run-tests.sh --all           # EVERYTHING: default gate + the opt-in, heavy
#                                    #   Authentik SCIM full-loop (scim-fullloop)
#   bin/run-tests.sh --unit          # unit phase only (Phase 1)
#   bin/run-tests.sh --it            # default-gate IT phase only (assumes a prior --unit)
#   bin/run-tests.sh --module rest   # IT phase for one module: rest|sso|knowledge-disabled|custom-jdbc|dense|scim-fullloop
#   bin/run-tests.sh --fullloop      # ONLY the opt-in Authentik full-loop
#   bin/run-tests.sh --it --parallel 4   # opt-in: default IT modules in one -T 4 reactor
#                                        # (-p 4 short form; or IT_PARALLELISM=4 env — flag wins)
#   bin/run-tests.sh --help
#
# Exit code: 0 only if every phase/module that ran reached BUILD SUCCESS with no
# test failures; non-zero otherwise. A per-run summary is written to
# target/test-suite-report.txt and printed at the end.
set -uo pipefail   # NOT -e: we want to run every module and aggregate, not bail early.

REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_DIR"

usage() {
  cat <<'EOF'
Usage: bin/run-tests.sh [MODE] [OPTIONS]

There are two "everything" levels:
  * the no-arg run is the DEFAULT GATE  — unit + the deterministic IT modules
    (rest, sso, knowledge-disabled, custom-jdbc, dense). This is what you run before committing.
  * --all is EVERYTHING               — the default gate PLUS the opt-in, heavy
    Authentik SCIM full-loop (scim-fullloop). Use it for a complete run.

MODES (default, no args: the default gate = unit, then default-gate IT modules)
  --all                  EVERYTHING: unit + default-gate IT modules + scim-fullloop
  --unit                 Unit reactor only (Phase 1)
  --it                   Default-gate IT modules only (assumes --unit ran)
  --module <name>        One IT module: rest|sso|knowledge-disabled|custom-jdbc|dense|scim-fullloop
  --fullloop             ONLY the opt-in Authentik SCIM full-loop (-Pscim-fullloop)
  --list                 Show modules and their gate, then exit

OPTIONS
  --parallel N, -p N     Run default-gate IT modules in one -T N reactor
  --output MODE, -o MODE Build output routing: file (default) | console | both
  --help, -h             This help

ENVIRONMENT
  UNIT_PARALLELISM=1C    Unit-phase -T value
  IT_PARALLELISM=1       Fallback for --parallel (flag wins)

EXAMPLES
  bin/run-tests.sh                      # default gate (unit + rest,sso,knowledge-disabled,custom-jdbc,dense) — pre-commit
  bin/run-tests.sh --all                # EVERYTHING incl. the heavy Authentik full-loop
  bin/run-tests.sh --all -o both        # everything, streaming build output to console + log
  bin/run-tests.sh --unit               # fast unit-only pass
  bin/run-tests.sh --module rest        # REST + SCIM IT only
  bin/run-tests.sh --it --parallel 4    # default IT modules, 4-way
  bin/run-tests.sh --module sso -o both # Keycloak SSO IT, output to log AND console
  bin/run-tests.sh --fullloop           # ONLY the opt-in Authentik full-loop (heavy)

EXIT: 0 only if every phase/module that ran reached BUILD SUCCESS.
EOF
}

list_modules() {
  echo "Run EVERYTHING with: bin/run-tests.sh --all   (default gate + opt-in below)"
  echo
  echo "Default gate (run by --it / no args):"
  echo "  rest                wikantik-it-tests/wikantik-it-test-rest               (REST API + SCIM)"
  echo "  sso                 wikantik-it-tests/wikantik-it-test-sso                (Keycloak OIDC + SAML, type=both)"
  echo "  knowledge-disabled  wikantik-it-tests/wikantik-it-test-knowledge-disabled (knowledge-subsystem-off degradation)"
  echo "  custom-jdbc         wikantik-it-tests/wikantik-it-test-custom-jdbc        (Selenide browser suite)"
  echo "  dense               wikantik-it-tests/wikantik-it-test-dense              (dense retrieval + context bundle)"
  echo
  echo "Opt-in (run only via --fullloop / --module scim-fullloop):"
  echo "  scim-fullloop  wikantik-it-tests/wikantik-it-test-scim-fullloop (Authentik SCIM full-loop)"
}

# Logs/report live OUTSIDE any target/ dir — `mvn clean` wipes target/ at the
# start of the build, which would unlink an in-progress log. This dir is gitignored.
#
# WIKANTIK_TEST_SUITE_LOG_DIR redirects it, and exists for exactly one caller:
# bin/tests/test-embeddings.sh drives this script with docker/curl/mvn shimmed on
# PATH to test its *dispatch* logic. Without the redirect each such run truncates a
# real run's it-<module>.log and report.txt (run_step redirects into $log
# unconditionally, shimmed mvn or not) and takes .run.lock — so a shell-unit run
# during a real gate would either destroy that gate's evidence or bail at the lock
# with exit 3, which reads as a legitimate test result. Never set this by hand.
LOG_DIR="${WIKANTIK_TEST_SUITE_LOG_DIR:-${REPO_DIR}/.test-suite-logs}"
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

# Short --module name for the dense IT module (Task 4 creates
# wikantik-it-tests/wikantik-it-test-dense). Single source of truth: used to
# build its IT_MODULES entry below AND to gate the shared embedder further
# down — only a dense run, or the full IT phase, actually needs it started.
DENSE_MODULE="dense"

# IT modules in their required sequential order (custom-jdbc runs the Selenide
# browser suite via the shared wikantik-selenide-tests jar).
IT_MODULES=(
  "wikantik-it-tests/wikantik-it-test-rest"
  "wikantik-it-tests/wikantik-it-test-sso"
  "wikantik-it-tests/wikantik-it-test-knowledge-disabled"
  "wikantik-it-tests/wikantik-it-test-custom-jdbc"
  "wikantik-it-tests/wikantik-it-test-${DENSE_MODULE}"
)

# Shared CPU embedder for the IT phase. One instance serves every module for the
# whole run, so the model loads once regardless of --parallel N. This manages its
# own container rather than reusing whatever happens to be listening: depending on
# ambient machine state is what made PreviewClickHoldsStillIT hang for 120s.
EMBED_PORT=11435
EMBED_URL="http://localhost:${EMBED_PORT}"
EMBED_MODEL_TAG="qwen3-embedding:0.6b"
EMBED_PROJECT="wikantik-embed-test"
EMBED_COMPOSE="${REPO_DIR}/docker/docker-compose.embeddings.yml"

# embeddings_model_ready() lives in bin/lib/embeddings.sh (shared with
# deploy-local.sh) precisely so both callers use the same readiness gate: a bare
# `curl .../api/tags` HTTP-200 check reports ready while the model pull is still
# running in the background (Ollama answers 200 with an empty model list mid-pull)
# — Tasks 1 and 2 both had to fix that exact trap. Sourcing has no side effects
# beyond defining functions/constants.
# shellcheck disable=SC1091
source "${REPO_DIR}/bin/lib/embeddings.sh"

# Readiness budget. A warm machine (model already in the ollama-models volume)
# is ready in seconds; the long pole is a COLD machine, where the first run
# downloads ~600 MB before /api/tags ever lists the tag. 360s was tight enough
# that a household broadband link (~20 Mbit) could plausibly lose the race, and
# losing it fails the whole gate for a reason that is not a test failure — so the
# budget is 900s. It costs nothing on a warm machine, which exits the loop on
# the first iteration; only a genuinely-stuck pull ever pays it.
EMBED_READY_TRIES=450     # x 2s sleep = 900s
EMBED_READY_SLEEP=2

embed_start() {
  echo ">>> Starting shared embedder on ${EMBED_URL}"
  COMPOSE_PROJECT_NAME="${EMBED_PROJECT}" WIKANTIK_EMBEDDING_PORT="${EMBED_PORT}" \
    WIKANTIK_EMBEDDING_MODEL_TAG="${EMBED_MODEL_TAG}" \
    docker compose -f "${EMBED_COMPOSE}" up -d >/dev/null 2>&1 || return 1
  echo "    waiting for ${EMBED_MODEL_TAG} (first run on this machine downloads ~600 MB)"
  for _ in $(seq 1 "${EMBED_READY_TRIES}"); do
    embeddings_model_ready "${EMBED_URL}" "${EMBED_MODEL_TAG}" && { echo "    embedder ready"; return 0; }
    sleep "${EMBED_READY_SLEEP}"
  done
  echo "    WARNING: embedder not ready after $(( EMBED_READY_TRIES * EMBED_READY_SLEEP ))s."
  echo "             The first run pulls ~600 MB into the ollama-models volume; on a slow"
  echo "             link that can outlast this budget. Check progress with:"
  echo "                 docker logs -f \$(docker ps -qf name=${EMBED_PROJECT})"
  echo "             then re-run — the volume persists, so the second run starts warm."
  return 1
}

embed_stop() {
  COMPOSE_PROJECT_NAME="${EMBED_PROJECT}" WIKANTIK_EMBEDDING_PORT="${EMBED_PORT}" \
    WIKANTIK_EMBEDDING_MODEL_TAG="${EMBED_MODEL_TAG}" \
    docker compose -f "${EMBED_COMPOSE}" down >/dev/null 2>&1 || true
}

RUN_UNIT=1
RUN_IT=1
RUN_FULLLOOP=0
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
    --help|-h) usage; exit 0 ;;
    --list)    list_modules; exit 0 ;;
    --all)     RUN_UNIT=1; RUN_IT=1; RUN_FULLLOOP=1 ;;
    --fullloop) RUN_UNIT=0; RUN_IT=0; ONE_MODULE="scim-fullloop" ;;
    --unit)    RUN_IT=0 ;;
    --it)      RUN_UNIT=0 ;;
    --module)  RUN_UNIT=0; RUN_IT=0; ONE_MODULE="${2:-}"; shift
               [ -n "$ONE_MODULE" ] || { echo "--module needs a name (rest|sso|knowledge-disabled|custom-jdbc|dense|scim-fullloop)" >&2; exit 2; } ;;
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
  last_step_rc="$rc"
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

# 0 when the unit phase was skipped (--it): that flag's contract is "assumes --unit
# ran", so the caller is asserting the artifacts are already installed.
unit_phase_rc=0

if [ "$RUN_UNIT" = 1 ]; then
  # Parallel unit reactor: compile all, run unit tests, install artifacts (incl. WAR).
  run_step "Phase 1: unit reactor (-T ${UNIT_PARALLELISM} -DskipITs)" "${LOG_DIR}/phase1-unit.log" \
    clean install -T "${UNIT_PARALLELISM}" -DskipITs
  unit_phase_rc="$last_step_rc"
fi

# Phase 1 starts with `clean` and ends with `install`. If it fails, THIS tree's
# artifacts were never installed — but the previous build's still sit in ~/.m2, so
# the IT modules (which resolve deps from there, deliberately without -am) happily
# run against stale jars and report PASS/FAIL for code that is not what you just
# changed. That is worse than no result at all: it sends you chasing IT failures
# that are really Phase-1 fallout, or hands you a green IT phase for code that
# never compiled. Skip instead.
if [ "$RUN_IT" = 1 ] && [ "$unit_phase_rc" -ne 0 ]; then
  {
    echo "SKIP  IT phase — Phase 1 failed, so this tree's artifacts were never installed."
    echo "        The IT modules resolve from ~/.m2 and would silently test the PREVIOUS"
    echo "        build's jars. Fix the unit failures and re-run."
    echo "        To run the ITs against artifacts you have installed yourself:"
    echo "            mvn clean install -DskipTests -T 1C && bin/run-tests.sh --it --parallel ${IT_PARALLELISM}"
  } | tee -a "$REPORT"
  RUN_IT=0
fi

# Only start the shared embedder for a run that actually needs it: the full
# IT phase, or a single-module run of the dense module specifically. Other
# --module runs (rest, sso, knowledge-disabled, custom-jdbc, scim-fullloop)
# have nothing to do with embeddings — starting it for e.g. --fullloop would
# add up to the full 360s embed_start timeout of dead wait for no reason.
if [ "$RUN_IT" = 1 ] || [ "$ONE_MODULE" = "$DENSE_MODULE" ]; then
  embed_start || true   # the dense module fails loudly on its own if this did not work
  trap embed_stop EXIT
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
  if [ "$ONE_MODULE" = "scim-fullloop" ]; then
    run_step "IT: ${mod} (full-loop)" "${LOG_DIR}/it-${ONE_MODULE}.log" \
      install -Pintegration-tests,scim-fullloop -fae -pl "$mod"
  else
    run_step "IT: ${mod}" "${LOG_DIR}/it-${ONE_MODULE}.log" \
      install -Pintegration-tests -fae -pl "$mod"
  fi
fi

# Opt-in Authentik full-loop, additive after the default gate. Set by --all (which
# also runs unit + default IT). The heavy multi-container stack runs sequentially
# (never folded into --parallel). --fullloop alone runs ONLY this, via ONE_MODULE above.
if [ "$RUN_FULLLOOP" = 1 ]; then
  fl_mod="wikantik-it-tests/wikantik-it-test-scim-fullloop"
  if [ -d "$fl_mod" ]; then
    run_step "IT: ${fl_mod} (full-loop)" "${LOG_DIR}/it-scim-fullloop.log" \
      install -Pintegration-tests,scim-fullloop -fae -pl "$fl_mod"
  else
    echo "no such IT module: $fl_mod" >&2; overall_rc=1
  fi
fi

total_dur="$(fmt_dur $(( $(date +%s) - run_start_epoch )) )"
echo "Wikantik test suite — finished $(date -u +%Y-%m-%dT%H:%M:%SZ) (started ${start_ts})" | tee -a "$REPORT"
echo "Total runtime: ${total_dur}" | tee -a "$REPORT"
echo "---------- SUMMARY ----------"
cat "$REPORT"
[ "$overall_rc" = 0 ] && echo "RESULT: ALL PASSED" || echo "RESULT: FAILURES (see above)"
echo "TOTAL RUNTIME: ${total_dur}"
exit "$overall_rc"
