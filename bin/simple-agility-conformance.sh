#!/usr/bin/env bash
#
# simple-agility-conformance.sh — Simple Agility telemetry contract checker
#
# Verifies that a running product instance conforms to the "Simple Agility
# telemetry contract":
#   1. Health   — an unauthenticated liveness endpoint returns 200.
#   2. Metrics  — a Prometheus text endpoint returns 200 and parses; at
#                 least one series is prefixed with "<prefix>_"; label
#                 cardinality per metric family is bounded (WARN, not FAIL,
#                 above the threshold).
#   3. Logs     — one JSON object per line on stdout, with required keys
#                 ts, level, service, msg (optional: event, request_id,
#                 correlation_id, instance).
#   4. (informational) whether correlation_id appears at all in the sample.
#
# Full contract text and rationale live on the wiki:
#   - SimpleAgilityTelemetryContract  (the contract itself)
#   - SimpleAgilityStackHub           (the stack this contract belongs to)
#
# Usage:
#   bin/simple-agility-conformance.sh --base-url URL --prefix NAME [options]
#   bin/simple-agility-conformance.sh --help
#
# Exit status: 0 = no FAILs (WARN/SKIP do not fail the run), 1 = at least
# one FAIL, 2 = usage error.
#
# Requires only: bash, curl, python3, coreutils (mktemp, head, cat).

set -uo pipefail

# ---------------------------------------------------------------------------
# Globals
# ---------------------------------------------------------------------------

BASE_URL=""
PREFIX=""
HEALTH_PATH="/api/health"
METRICS_PATH="/metrics"
LOG_FILE=""
LOG_CMD=""
LOG_STDIN=0
LOG_SOURCE_COUNT=0
SAMPLE=200
MAX_SERIES=500
JSON_OUTPUT=0

# Percentage of sampled log lines that must parse as JSON. Not 100: a healthy
# container still emits lines the application logger never formats (JVM and
# Tomcat startup banners, GC output). See the coverage gate in check_logs.
readonly JSON_COVERAGE_PASS=95
readonly JSON_COVERAGE_WARN=50

CHECK_NAMES=()
CHECK_STATUSES=()
CHECK_REASONS=()

TMP_FILES=()

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

# shellcheck disable=SC2317 # only called indirectly via `trap ... EXIT` below
cleanup() {
  local f
  for f in "${TMP_FILES[@]:-}"; do
    [[ -n "$f" && -f "$f" ]] && rm -f "$f"
  done
}
trap cleanup EXIT

make_tmp() {
  local f
  if ! f=$(mktemp); then
    printf 'FATAL: mktemp failed\n' >&2
    exit 2
  fi
  TMP_FILES+=("$f")
  printf '%s' "$f"
}

die_usage() {
  printf 'usage error: %s\n' "$1" >&2
  printf 'Try --help for usage.\n' >&2
  exit 2
}

# Record one check result. Sanitizes the reason so it is always a single
# line (JSON and human output both assume no embedded tabs/newlines).
add_result() {
  local name="$1" status="$2" reason="$3"
  reason="${reason//$'\t'/ }"
  reason="${reason//$'\n'/ }"
  CHECK_NAMES+=("$name")
  CHECK_STATUSES+=("$status")
  CHECK_REASONS+=("$reason")
}

print_help() {
  cat <<'EOF'
Usage: simple-agility-conformance.sh --base-url URL --prefix NAME [options]

Verifies that a running product instance conforms to the Simple Agility
telemetry contract: an unauthenticated health endpoint, a Prometheus
metrics endpoint carrying a namespaced prefix with bounded label
cardinality, and (when a log source is supplied) structured JSON-lines
logging on stdout with a required key set. See SimpleAgilityTelemetryContract
and SimpleAgilityStackHub on the wiki for the full contract text.

Required:
  --base-url URL         Base URL of the instance under test,
                          e.g. http://localhost:8080
  --prefix NAME           Expected Prometheus metric name prefix,
                          e.g. wikantik

Options:
  --health-path PATH      Liveness endpoint path (default: /api/health)
  --metrics-path PATH     Prometheus metrics endpoint path (default: /metrics)
  --log-file FILE         Read the log sample from FILE
  --log-cmd 'CMD'         Run CMD via the shell and read its stdout as the
                          log sample
  --log-stdin             Read the log sample from this script's stdin
                          (--log-file / --log-cmd / --log-stdin are mutually
                          exclusive; if none is given, the log checks
                          report SKIP rather than FAIL)
  --sample N               Max log lines to sample (default: 200)
  --max-series N          Per-family series count that triggers a
                          cardinality WARN (default: 500)
  --json                  Emit one machine-readable JSON object instead of
                          human-readable lines
  --help                  Show this help and exit

Exit status:
  0   no check FAILed (WARN/SKIP do not fail the run)
  1   at least one check FAILed
  2   usage error

Examples:
  # Health + metrics only, against a local instance
  bin/simple-agility-conformance.sh --base-url http://localhost:8080 --prefix wikantik

  # Also check logs pulled from a running container
  bin/simple-agility-conformance.sh --base-url http://localhost:8080 --prefix wikantik \
      --log-cmd 'docker logs --tail 200 wikantik'

  # Check logs from a file, machine-readable output
  bin/simple-agility-conformance.sh --base-url http://localhost:8080 --prefix wikantik \
      --log-file /var/log/wikantik/app.log --json
EOF
}

print_human() {
  local i name status reason
  for i in "${!CHECK_NAMES[@]}"; do
    name="${CHECK_NAMES[$i]}"
    status="${CHECK_STATUSES[$i]}"
    reason="${CHECK_REASONS[$i]}"
    printf '%-5s %-22s %s\n' "$status" "$name" "$reason"
  done

  local pass=0 fail=0 warn=0 skip=0 s
  for s in "${CHECK_STATUSES[@]}"; do
    case "$s" in
      PASS) pass=$((pass + 1)) ;;
      FAIL) fail=$((fail + 1)) ;;
      WARN) warn=$((warn + 1)) ;;
      SKIP) skip=$((skip + 1)) ;;
    esac
  done
  printf '\n%d checks: %d pass, %d fail, %d warn, %d skip\n' \
    "${#CHECK_STATUSES[@]}" "$pass" "$fail" "$warn" "$skip"
}

print_json() {
  local i
  {
    for i in "${!CHECK_NAMES[@]}"; do
      printf '%s\t%s\t%s\n' "${CHECK_NAMES[$i]}" "${CHECK_STATUSES[$i]}" "${CHECK_REASONS[$i]}"
    done
  } | python3 <(cat <<'PYEOF'
import sys, json

checks = []
counts = {"PASS": 0, "FAIL": 0, "WARN": 0, "SKIP": 0}
for line in sys.stdin:
    line = line.rstrip("\n")
    if not line:
        continue
    parts = line.split("\t", 2)
    if len(parts) != 3:
        continue
    name, status, reason = parts
    checks.append({"name": name, "status": status, "reason": reason})
    counts[status] = counts.get(status, 0) + 1

summary = {
    "total": len(checks),
    "pass": counts["PASS"],
    "fail": counts["FAIL"],
    "warn": counts["WARN"],
    "skip": counts["SKIP"],
    "conformant": counts["FAIL"] == 0,
}
print(json.dumps({"checks": checks, "summary": summary}, indent=2))
PYEOF
)
}

# ---------------------------------------------------------------------------
# Checks
# ---------------------------------------------------------------------------

check_health() {
  local health_url health_err_file http_code curl_exit
  health_url="${BASE_URL%/}${HEALTH_PATH}"
  health_err_file=$(make_tmp)

  http_code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 15 "$health_url" 2>"$health_err_file")
  curl_exit=$?

  if [[ $curl_exit -ne 0 ]]; then
    add_result "health" "FAIL" "curl failed (exit ${curl_exit}) fetching ${health_url}: $(cat "$health_err_file")"
  elif [[ "$http_code" == "200" ]]; then
    add_result "health" "PASS" "HTTP 200 from ${HEALTH_PATH}"
  else
    add_result "health" "FAIL" "HTTP ${http_code} from ${HEALTH_PATH} (expected 200)"
  fi
}

check_metrics() {
  local metrics_url metrics_body_file metrics_err_file http_code curl_exit
  metrics_url="${BASE_URL%/}${METRICS_PATH}"
  metrics_body_file=$(make_tmp)
  metrics_err_file=$(make_tmp)

  http_code=$(curl -sS -o "$metrics_body_file" -w '%{http_code}' --max-time 15 "$metrics_url" 2>"$metrics_err_file")
  curl_exit=$?

  if [[ $curl_exit -ne 0 ]]; then
    add_result "metrics" "FAIL" "curl failed (exit ${curl_exit}) fetching ${metrics_url}: $(cat "$metrics_err_file")"
    add_result "metrics-cardinality" "SKIP" "metrics endpoint unreachable"
    return
  fi
  if [[ "$http_code" != "200" ]]; then
    add_result "metrics" "FAIL" "HTTP ${http_code} from ${METRICS_PATH} (expected 200)"
    add_result "metrics-cardinality" "SKIP" "metrics endpoint did not return 200"
    return
  fi

  declare -A METRICS_INFO=()
  local key value
  while IFS='=' read -r key value; do
    [[ -n "$key" ]] && METRICS_INFO["$key"]="$value"
  done < <(python3 - "$metrics_body_file" "$PREFIX" "$MAX_SERIES" <<'PYEOF'
import sys, re
from collections import defaultdict

body_file, prefix, max_series = sys.argv[1], sys.argv[2], int(sys.argv[3])

with open(body_file, "r", errors="replace") as f:
    text = f.read()

# Loose Prometheus exposition line matcher: "name{labels} value [timestamp]"
# or "name value". Family = the literal metric name token before "{" (so
# histogram/summary component suffixes like _bucket/_sum/_count count as
# separate families, matching how they literally appear in the text).
metric_line_re = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{.*\})?\s+\S+')
family_counts = defaultdict(int)
total_series = 0

for raw in text.splitlines():
    line = raw.strip()
    if not line or line.startswith("#"):
        continue
    m = metric_line_re.match(line)
    if not m:
        continue
    family_counts[m.group(1)] += 1
    total_series += 1

parsed = total_series > 0
prefix_found = any(name.startswith(prefix + "_") for name in family_counts)

worst_family, worst_count, over_limit = "", 0, 0
for name, count in family_counts.items():
    if count > worst_count:
        worst_family, worst_count = name, count
    if count > max_series:
        over_limit += 1

print(f"PARSED={1 if parsed else 0}")
print(f"TOTAL_SERIES={total_series}")
print(f"TOTAL_FAMILIES={len(family_counts)}")
print(f"PREFIX_FOUND={1 if prefix_found else 0}")
print(f"WORST_FAMILY={worst_family}")
print(f"WORST_COUNT={worst_count}")
print(f"OVER_LIMIT_COUNT={over_limit}")
PYEOF
  )

  local parsed="${METRICS_INFO[PARSED]:-0}"
  local total_series="${METRICS_INFO[TOTAL_SERIES]:-0}"
  local total_families="${METRICS_INFO[TOTAL_FAMILIES]:-0}"
  local prefix_found="${METRICS_INFO[PREFIX_FOUND]:-0}"
  local worst_family="${METRICS_INFO[WORST_FAMILY]:-}"
  local worst_count="${METRICS_INFO[WORST_COUNT]:-0}"
  local over_limit="${METRICS_INFO[OVER_LIMIT_COUNT]:-0}"

  if [[ "$parsed" -ne 1 ]]; then
    add_result "metrics" "FAIL" "HTTP 200 but response body did not parse as Prometheus exposition text (0 series found)"
    add_result "metrics-cardinality" "SKIP" "metrics did not parse"
    return
  fi

  if [[ "$prefix_found" -eq 1 ]]; then
    add_result "metrics" "PASS" "HTTP 200, parsed ${total_series} series across ${total_families} families; prefix '${PREFIX}_' present"
  else
    add_result "metrics" "FAIL" "HTTP 200, parsed ${total_series} series across ${total_families} families, but none prefixed '${PREFIX}_'"
  fi

  if [[ "$over_limit" -gt 0 ]]; then
    add_result "metrics-cardinality" "WARN" "${over_limit} metric famil(y/ies) exceed --max-series=${MAX_SERIES}; worst offender '${worst_family}' with ${worst_count} series"
  else
    add_result "metrics-cardinality" "PASS" "no metric family exceeds --max-series=${MAX_SERIES} (worst: '${worst_family}' with ${worst_count} series)"
  fi
}

check_logs() {
  if [[ "$LOG_SOURCE_COUNT" -eq 0 ]]; then
    add_result "logs-format" "SKIP" "no log source given (--log-file, --log-cmd, or --log-stdin)"
    add_result "logs-correlation-id" "SKIP" "no log source given (--log-file, --log-cmd, or --log-stdin)"
    return
  fi

  local log_sample_file log_source_ok=0
  log_sample_file=$(make_tmp)

  if [[ -n "$LOG_FILE" ]]; then
    if [[ ! -r "$LOG_FILE" ]]; then
      add_result "logs-format" "FAIL" "cannot read log file '${LOG_FILE}'"
      add_result "logs-correlation-id" "SKIP" "log file unreadable"
      return
    fi
    head -n "$SAMPLE" "$LOG_FILE" > "$log_sample_file"
    log_source_ok=1
  elif [[ -n "$LOG_CMD" ]]; then
    local log_cmd_out_file log_cmd_err_file cmd_exit
    log_cmd_out_file=$(make_tmp)
    log_cmd_err_file=$(make_tmp)
    bash -c "$LOG_CMD" >"$log_cmd_out_file" 2>"$log_cmd_err_file"
    cmd_exit=$?
    if [[ $cmd_exit -ne 0 ]]; then
      add_result "logs-format" "FAIL" "log command failed (exit ${cmd_exit}): $(cat "$log_cmd_err_file")"
      add_result "logs-correlation-id" "SKIP" "log command failed"
      return
    fi
    head -n "$SAMPLE" "$log_cmd_out_file" > "$log_sample_file"
    log_source_ok=1
  elif [[ "$LOG_STDIN" -eq 1 ]]; then
    head -n "$SAMPLE" > "$log_sample_file"
    log_source_ok=1
  fi

  [[ "$log_source_ok" -eq 1 ]] || return

  declare -A LOG_INFO=()
  local key value
  while IFS='=' read -r key value; do
    [[ -n "$key" ]] && LOG_INFO["$key"]="$value"
  done < <(python3 - "$log_sample_file" "ts,level,service,msg" <<'PYEOF'
import sys, json

path, required_csv = sys.argv[1], sys.argv[2]
required = required_csv.split(",")

with open(path, "r", errors="replace") as f:
    lines = [ln.rstrip("\n") for ln in f if ln.strip() != ""]

total = len(lines)
json_parsed = 0
all_have_required = True
missing_union = set()
lines_missing = 0
correlation_found = False

for ln in lines:
    try:
        obj = json.loads(ln)
    except ValueError:
        continue
    if not isinstance(obj, dict):
        continue
    json_parsed += 1
    missing = [k for k in required if k not in obj]
    if missing:
        all_have_required = False
        lines_missing += 1
        missing_union.update(missing)
    if "correlation_id" in obj:
        correlation_found = True

percent = (json_parsed / total * 100.0) if total else 0.0

print(f"TOTAL_SAMPLED={total}")
print(f"JSON_PARSED={json_parsed}")
print(f"JSON_PERCENT={percent:.1f}")
print(f"ALL_HAVE_REQUIRED={1 if (json_parsed > 0 and all_have_required) else 0}")
print(f"MISSING_KEYS={','.join(sorted(missing_union))}")
print(f"LINES_MISSING_KEYS_COUNT={lines_missing}")
print(f"CORRELATION_ID_FOUND={1 if correlation_found else 0}")
PYEOF
  )

  local total_sampled="${LOG_INFO[TOTAL_SAMPLED]:-0}"
  local json_parsed="${LOG_INFO[JSON_PARSED]:-0}"
  local json_percent="${LOG_INFO[JSON_PERCENT]:-0.0}"
  local all_have_required="${LOG_INFO[ALL_HAVE_REQUIRED]:-0}"
  local missing_keys="${LOG_INFO[MISSING_KEYS]:-}"
  local lines_missing="${LOG_INFO[LINES_MISSING_KEYS_COUNT]:-0}"
  local correlation_found="${LOG_INFO[CORRELATION_ID_FOUND]:-0}"

  if [[ "$total_sampled" -eq 0 ]]; then
    add_result "logs-format" "FAIL" "no log lines available from source"
    add_result "logs-correlation-id" "SKIP" "no log lines available from source"
    return
  fi

  if [[ "$json_parsed" -eq 0 ]]; then
    add_result "logs-format" "FAIL" "plain-text logs; contract requires JSON per line (log4j2 JsonTemplateLayout is the migration path) — 0/${total_sampled} lines parsed as JSON (${json_percent}%)"
    add_result "logs-correlation-id" "SKIP" "no JSON log lines to inspect"
    return
  fi

  if [[ "$all_have_required" -ne 1 ]]; then
    add_result "logs-format" "FAIL" "${lines_missing} of ${json_parsed} JSON lines missing required key(s) [${missing_keys}]; ${json_parsed}/${total_sampled} lines parsed as JSON (${json_percent}%)"
  # Coverage gate. The contract says "one JSON object per line", so a stream that
  # is half plain text does not conform even when its JSON half is perfect. But a
  # real container also emits lines log4j2 never sees — JVM and Tomcat startup
  # banners, GC output — so demanding 100% would fail every healthy service. PASS
  # above JSON_COVERAGE_PASS, WARN in the band above JSON_COVERAGE_WARN (visible,
  # not exit-code-breaking), FAIL below it. Integer math: no float compare in sh.
  elif (( json_parsed * 100 >= total_sampled * JSON_COVERAGE_PASS )); then
    add_result "logs-format" "PASS" "${json_parsed}/${total_sampled} lines parsed as JSON (${json_percent}%); all sampled JSON lines carry ts, level, service, msg"
  elif (( json_parsed * 100 >= total_sampled * JSON_COVERAGE_WARN )); then
    add_result "logs-format" "WARN" "mixed stream: only ${json_parsed}/${total_sampled} lines parsed as JSON (${json_percent}%, want >=${JSON_COVERAGE_PASS}%); the JSON lines all carry the required keys, so check what else is writing to stdout"
  else
    add_result "logs-format" "FAIL" "mostly non-JSON: ${json_parsed}/${total_sampled} lines parsed as JSON (${json_percent}%, want >=${JSON_COVERAGE_PASS}%); contract requires JSON per line (log4j2 JsonTemplateLayout is the migration path)"
  fi

  if [[ "$correlation_found" -eq 1 ]]; then
    add_result "logs-correlation-id" "PASS" "correlation_id present in at least one sampled line (informational, optional key)"
  else
    add_result "logs-correlation-id" "WARN" "correlation_id not found in sampled lines (informational, optional key)"
  fi
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

main() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --base-url)
        [[ $# -ge 2 ]] || die_usage "--base-url requires a value"
        BASE_URL="$2"; shift 2 ;;
      --prefix)
        [[ $# -ge 2 ]] || die_usage "--prefix requires a value"
        PREFIX="$2"; shift 2 ;;
      --health-path)
        [[ $# -ge 2 ]] || die_usage "--health-path requires a value"
        HEALTH_PATH="$2"; shift 2 ;;
      --metrics-path)
        [[ $# -ge 2 ]] || die_usage "--metrics-path requires a value"
        METRICS_PATH="$2"; shift 2 ;;
      --log-file)
        [[ $# -ge 2 ]] || die_usage "--log-file requires a value"
        LOG_FILE="$2"; LOG_SOURCE_COUNT=$((LOG_SOURCE_COUNT + 1)); shift 2 ;;
      --log-cmd)
        [[ $# -ge 2 ]] || die_usage "--log-cmd requires a value"
        LOG_CMD="$2"; LOG_SOURCE_COUNT=$((LOG_SOURCE_COUNT + 1)); shift 2 ;;
      --log-stdin)
        LOG_STDIN=1; LOG_SOURCE_COUNT=$((LOG_SOURCE_COUNT + 1)); shift ;;
      --sample)
        [[ $# -ge 2 ]] || die_usage "--sample requires a value"
        SAMPLE="$2"; shift 2 ;;
      --max-series)
        [[ $# -ge 2 ]] || die_usage "--max-series requires a value"
        MAX_SERIES="$2"; shift 2 ;;
      --json)
        JSON_OUTPUT=1; shift ;;
      --help|-h)
        print_help; exit 0 ;;
      --)
        shift; break ;;
      -*)
        die_usage "unknown option: $1" ;;
      *)
        die_usage "unexpected argument: $1" ;;
    esac
  done

  [[ -n "$BASE_URL" ]] || die_usage "--base-url is required"
  [[ -n "$PREFIX" ]] || die_usage "--prefix is required"
  [[ "$LOG_SOURCE_COUNT" -le 1 ]] || die_usage "--log-file, --log-cmd, and --log-stdin are mutually exclusive"
  [[ "$SAMPLE" =~ ^[0-9]+$ && "$SAMPLE" -gt 0 ]] || die_usage "--sample must be a positive integer"
  [[ "$MAX_SERIES" =~ ^[0-9]+$ && "$MAX_SERIES" -gt 0 ]] || die_usage "--max-series must be a positive integer"

  check_health
  check_metrics
  check_logs

  local fail_count=0 s
  for s in "${CHECK_STATUSES[@]}"; do
    [[ "$s" == "FAIL" ]] && fail_count=$((fail_count + 1))
  done

  if [[ "$JSON_OUTPUT" -eq 1 ]]; then
    print_json
  else
    print_human
  fi

  [[ "$fail_count" -eq 0 ]] || exit 1
  exit 0
}

main "$@"
