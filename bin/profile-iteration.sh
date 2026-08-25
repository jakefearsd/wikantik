#!/usr/bin/env bash
#
# profile-iteration.sh — one repeatable CPU-profiling iteration.
#
# Wraps the k6 harness in a JFR recording and reduces the result to a CPU
# composition table, so two iterations can be diffed frame-by-frame. This is
# the loop docs/LoadTesting.md describes, packaged so every iteration in a
# campaign is executed identically (the discipline that matters: change ONE
# variable, re-run this verbatim, diff the tables).
#
# Usage:
#   bin/profile-iteration.sh <label> [options]
#
# Options:
#   --duration D     load duration (default 3m)
#   --vus N          read-scenario VUs (default 40)
#   --admin-vus N    admin-scenario VUs (default 10); 0 disables --admin
#   --write-vus N    write-scenario VUs (default 0 = no writes)
#   --base-url U     target (default http://localhost:8080)
#   --no-load        record JFR without driving load (idle baseline)
#   -h | --help      this help
#
# Output lands in loadtest/results/<label>/:
#   k6.log            full k6 output
#   <label>.jfr       the recording
#   cpu-leaf.txt      top leaf frames (where cycles are actually spent)
#   cpu-wikantik.txt  top *application* frames (deepest com.wikantik frame per
#                     sample) — the actionable view: JDK leaves like
#                     String.hashCode tell you nothing about what to fix
#   alloc.txt         top allocation sites
#   monitor.txt       contended monitors (jdk.JavaMonitorEnter)
#   summary.txt       headline numbers, and the per-surface latency table
#
set -euo pipefail

REPO_ROOT="$( cd "$( dirname "${BASH_SOURCE[0]}" )/.." && pwd )"

usage() { sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//; $d'; }
[[ "${1:-}" == "-h" || "${1:-}" == "--help" || -z "${1:-}" ]] && { usage; exit 0; }

LABEL="$1"; shift
DURATION=3m VUS=40 ADMIN_VUS=10 WRITE_VUS=0 BASE_URL="" NO_LOAD=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --duration)  DURATION="$2"; shift 2 ;;
    --vus)       VUS="$2"; shift 2 ;;
    --admin-vus) ADMIN_VUS="$2"; shift 2 ;;
    --write-vus) WRITE_VUS="$2"; shift 2 ;;
    --base-url)  BASE_URL="$2"; shift 2 ;;
    --no-load)   NO_LOAD=1; shift ;;
    -h|--help)   usage; exit 0 ;;
    *) echo "ERROR: unknown option '$1'" >&2; exit 2 ;;
  esac
done

OUT_DIR="${REPO_ROOT}/loadtest/results/${LABEL}"
mkdir -p "${OUT_DIR}"

# Credentials (same precedence as bin/loadtest.sh).
[[ -f "${REPO_ROOT}/loadtest/loadtest.env" ]] && { set -a; . "${REPO_ROOT}/loadtest/loadtest.env"; set +a; }
if [[ -f "${REPO_ROOT}/test.properties" ]]; then
  : "${LOADTEST_ADMIN_USER:=$(grep -E '^test.user.login=' "${REPO_ROOT}/test.properties" | cut -d= -f2-)}"
  : "${LOADTEST_ADMIN_PASS:=$(grep -E '^test.user.password=' "${REPO_ROOT}/test.properties" | cut -d= -f2-)}"
fi
: "${BASE_URL:=http://localhost:8080}"
[[ -n "${BASE_URL}" ]] || { echo "ERROR: BASE_URL unset" >&2; exit 2; }
AUTH=(-u "${LOADTEST_ADMIN_USER}:${LOADTEST_ADMIN_PASS}")

# JFR must outlive the load run: k6 needs a moment to spin up and wind down.
# Convert the k6 duration to seconds and add 30s of slack on either side.
dur_to_s() {
  local d="$1"
  case "$d" in
    *h) echo $(( ${d%h} * 3600 )) ;;
    *m) echo $(( ${d%m} * 60 )) ;;
    *s) echo "${d%s}" ;;
    *)  echo "$d" ;;
  esac
}
LOAD_S=$(dur_to_s "${DURATION}")
JFR_S=$(( LOAD_S + 60 ))
# JfrProfilingService caps duration_s at 600.
if (( JFR_S > 600 )); then JFR_S=600; fi

echo "=== profile-iteration: ${LABEL} ==="
echo "    target      ${BASE_URL}"
echo "    load        ${DURATION} @ ${VUS} read VUs / ${ADMIN_VUS} admin VUs / ${WRITE_VUS} write VUs"
echo "    jfr window  ${JFR_S}s"

# ── start the recording ─────────────────────────────────────────────────────
START_JSON=$(curl -sS "${AUTH[@]}" -X POST "${BASE_URL}/admin/profiling/jfr/start" \
  -H 'Content-Type: application/json' \
  -d "{\"duration_s\":${JFR_S},\"label\":\"${LABEL}\"}")
REC_ID=$(printf '%s' "${START_JSON}" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("recording_id",""))' 2>/dev/null || true)
if [[ -z "${REC_ID}" ]]; then
  echo "ERROR: JFR start failed: ${START_JSON}" >&2
  exit 1
fi
echo "    recording   ${REC_ID}"

# ── drive the load ──────────────────────────────────────────────────────────
if [[ "${NO_LOAD}" == 1 ]]; then
  echo "--- --no-load: idling for ${LOAD_S}s ---"
  sleep "${LOAD_S}"
else
  LT_ARGS=(smoke --duration "${DURATION}" --vus "${VUS}")
  (( ADMIN_VUS > 0 )) && LT_ARGS+=(--admin-vus "${ADMIN_VUS}")
  (( WRITE_VUS > 0 )) && LT_ARGS+=(--write-vus "${WRITE_VUS}")
  echo "--- k6: bin/loadtest.sh ${LT_ARGS[*]} ---"
  set +e
  SLUGS_FILE=./slugs.local.txt BASE_URL="${BASE_URL}" \
    "${REPO_ROOT}/bin/loadtest.sh" "${LT_ARGS[@]}" 2>&1 | tee "${OUT_DIR}/k6.log"
  set -e
fi

# ── stop + fetch ────────────────────────────────────────────────────────────
curl -sS "${AUTH[@]}" -X POST "${BASE_URL}/admin/profiling/jfr/stop" \
  -H 'Content-Type: application/json' \
  -d "{\"recording_id\":\"${REC_ID}\"}" >/dev/null || true
sleep 3

JFR_FILE="${OUT_DIR}/${LABEL}.jfr"
curl -sS "${AUTH[@]}" "${BASE_URL}/admin/profiling/jfr/recordings/${REC_ID}" -o "${JFR_FILE}"
if [[ ! -s "${JFR_FILE}" ]]; then
  echo "ERROR: downloaded recording is empty (${JFR_FILE})" >&2
  exit 1
fi
echo "    jfr         ${JFR_FILE} ($(du -h "${JFR_FILE}" | cut -f1))"

# ── reduce ──────────────────────────────────────────────────────────────────
echo "--- analysing ---"
python3 "${REPO_ROOT}/bin/lib/jfr-cpu-report.py" "${JFR_FILE}" "${OUT_DIR}"

# ── headline summary ────────────────────────────────────────────────────────
{
  echo "=== ${LABEL} ==="
  echo "target:   ${BASE_URL}"
  echo "load:     ${DURATION} @ ${VUS} read / ${ADMIN_VUS} admin / ${WRITE_VUS} write VUs"
  echo
  if [[ -f "${OUT_DIR}/k6.log" ]]; then
    echo "--- k6 headline ---"
    grep -E 'http_reqs|http_req_duration|http_req_failed|iterations\.' "${OUT_DIR}/k6.log" | tail -8 || true
    echo
  fi
  echo "--- CPU: top application frames (see cpu-wikantik.txt for full list) ---"
  head -20 "${OUT_DIR}/cpu-wikantik.txt" 2>/dev/null || echo "(none)"
} > "${OUT_DIR}/summary.txt"

cat "${OUT_DIR}/summary.txt"
echo
echo "=== artefacts in ${OUT_DIR} ==="
ls -1 "${OUT_DIR}"
