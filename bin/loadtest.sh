#!/usr/bin/env bash
#
# loadtest.sh — run the Wikantik k6 load harness.
#
# Usage:
#   bin/loadtest.sh <profile> [options]
#
# Profiles:
#   smoke    ~2 min, low concurrency; hits every instrumented endpoint.
#   load     sustained ramping load for steady-state performance testing.
#   stress   staged ramp past capacity until thresholds break.
#
# Options:
#   --verify        scrape /metrics before+after and gate on per-panel deltas
#   --writes        add the authenticated create/edit/delete + login cycle
#   --metrics-url U scrape metrics from U instead of BASE_URL/metrics
#   --duration D    override the run duration (load/stress)
#   --vus N         override peak VUs (load/stress)
#   --dry-run       print the k6 command without running it
#   -h | --help     this help
#
# Credentials: loadtest/loadtest.env (copy from loadtest.env.example), with
# admin credentials falling back to test.properties. No secrets are embedded.
set -euo pipefail

REPO_ROOT="$( cd "$( dirname "${BASH_SOURCE[0]}" )/.." && pwd )"
LOADTEST_DIR="${REPO_ROOT}/loadtest"

usage() { sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//; $d'; }

[[ "${1:-}" == "-h" || "${1:-}" == "--help" || -z "${1:-}" ]] && { usage; exit 0; }

PROFILE="$1"; shift
case "${PROFILE}" in smoke|load|stress) ;; *)
  echo "ERROR: unknown profile '${PROFILE}' (expected smoke|load|stress)" >&2; exit 2 ;;
esac

VERIFY=0 WRITES=0 DRY_RUN=0 DURATION="" VUS="" METRICS_URL=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --verify)      VERIFY=1; shift ;;
    --writes)      WRITES=1; shift ;;
    --metrics-url) METRICS_URL="$2"; shift 2 ;;
    --duration)    DURATION="$2"; shift 2 ;;
    --vus)         VUS="$2"; shift 2 ;;
    --dry-run)     DRY_RUN=1; shift ;;
    -h|--help)     usage; exit 0 ;;
    *) echo "ERROR: unknown option '$1'" >&2; exit 2 ;;
  esac
done

if ! command -v k6 >/dev/null 2>&1; then
  echo "ERROR: k6 is not installed." >&2
  echo "Install it: https://grafana.com/docs/k6/latest/set-up/install-k6/" >&2
  exit 2
fi

# Load credentials: loadtest.env first, then test.properties for admin creds.
[[ -f "${LOADTEST_DIR}/loadtest.env" ]] && set -a && \
  . "${LOADTEST_DIR}/loadtest.env" && set +a
if [[ -f "${REPO_ROOT}/test.properties" ]]; then
  : "${LOADTEST_ADMIN_USER:=$(grep -E '^test.user.login=' "${REPO_ROOT}/test.properties" | cut -d= -f2-)}"
  : "${LOADTEST_ADMIN_PASS:=$(grep -E '^test.user.password=' "${REPO_ROOT}/test.properties" | cut -d= -f2-)}"
fi
: "${BASE_URL:=http://localhost:8080}"

# Fail fast on missing credentials a chosen mode needs.
if [[ "${WRITES}" == 1 && ( -z "${LOADTEST_ADMIN_USER:-}" || -z "${LOADTEST_ADMIN_PASS:-}" ) ]]; then
  echo "ERROR: --writes needs LOADTEST_ADMIN_USER and LOADTEST_ADMIN_PASS" >&2
  echo "       set them in loadtest/loadtest.env or test.properties" >&2
  exit 2
fi

K6_ARGS=(run
  -e "PROFILE=${PROFILE}"
  -e "BASE_URL=${BASE_URL}"
  -e "VERIFY=${VERIFY}"
  -e "WRITES=${WRITES}"
  -e "LOADTEST_ADMIN_USER=${LOADTEST_ADMIN_USER:-}"
  -e "LOADTEST_ADMIN_PASS=${LOADTEST_ADMIN_PASS:-}"
  -e "LOADTEST_MCP_KEY=${LOADTEST_MCP_KEY:-}"
  -e "LOADTEST_TOOLS_KEY=${LOADTEST_TOOLS_KEY:-}"
)
[[ -n "${METRICS_URL}" ]] && K6_ARGS+=(-e "METRICS_URL=${METRICS_URL}")
[[ -n "${DURATION}" ]] && K6_ARGS+=(-e "K6_DURATION=${DURATION}")
[[ -n "${VUS}" ]] && K6_ARGS+=(-e "K6_VUS=${VUS}")
K6_ARGS+=(wikantik-load.js)

# k6 remote-writes its own metrics (offered RPS, VUs, latency) to Prometheus
# when K6_PROMETHEUS_RW_SERVER_URL is set.
K6_OUT_ARGS=()
if [[ -n "${K6_PROMETHEUS_RW_SERVER_URL:-}" ]]; then
  K6_OUT_ARGS+=(--out experimental-prometheus-rw)
fi

# Best-effort Grafana region annotation around the run.
post_annotation() {
  [[ -z "${GRAFANA_URL:-}" || -z "${GRAFANA_TOKEN:-}" ]] && return 0
  curl -s -o /dev/null -X POST "${GRAFANA_URL}/api/annotations" \
    -H "Authorization: Bearer ${GRAFANA_TOKEN}" \
    -H 'Content-Type: application/json' \
    -d "$1" || echo "WARN: Grafana annotation POST failed" >&2
}

if [[ "${DRY_RUN}" == 1 ]]; then
  echo "cd ${LOADTEST_DIR} && k6 ${K6_ARGS[*]} ${K6_OUT_ARGS[*]}"
  exit 0
fi

START_MS=$(( $(date +%s) * 1000 ))
post_annotation "{\"time\":${START_MS},\"tags\":[\"loadtest\",\"${PROFILE}\"],\"text\":\"loadtest ${PROFILE} start\"}"

cd "${LOADTEST_DIR}"
set +e
k6 "${K6_ARGS[@]}" "${K6_OUT_ARGS[@]}"
K6_EXIT=$?
set -e

END_MS=$(( $(date +%s) * 1000 ))
post_annotation "{\"time\":${START_MS},\"timeEnd\":${END_MS},\"tags\":[\"loadtest\",\"${PROFILE}\"],\"text\":\"loadtest ${PROFILE} finished (exit ${K6_EXIT})\"}"
exit "${K6_EXIT}"
