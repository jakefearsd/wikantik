#!/usr/bin/env bash
# curl-probe.sh — external real-user latency probe for the scaling study.
#
# Samples three endpoints once per second for a given duration, recording
# (timestamp, endpoint, HTTP code, total seconds) to <prefix>.log. Runs
# outside k6 so the data is independent of what k6 sees and of the
# server-side metrics histograms. Useful when the dashboard claims fine
# latencies but pages won't load.
#
# Usage: bin/curl-probe.sh <duration-seconds> <output-prefix>
#   BASE_URL env var overrides the default (docker1 prod).
set -uo pipefail

DUR="${1:?duration seconds required}"
OUT="${2:?output prefix required (path without .log)}"
BASE="${BASE_URL:-http://192.168.0.4:8080}"

mkdir -p "$(dirname "${OUT}")"
LOG="${OUT}.log"
: > "${LOG}"  # truncate

END=$(( $(date +%s) + DUR ))
while (( $(date +%s) < END )); do
  ts=$(date +%s.%3N)
  for ep in "/api/health" "/wiki/Main" "/api/search?q=cloud"; do
    # curl's -w output is printed even on timeout/error (HTTP code "000",
    # time_total = the elapsed wait). Do NOT add an || fallback here — it
    # doubles up the line because curl also returns a non-zero exit code
    # while still having emitted the -w line. Trust curl's output alone.
    out=$(curl -sS -o /dev/null -w "%{http_code}\t%{time_total}" \
            --max-time 30 "${BASE}${ep}" 2>/dev/null)
    printf '%s\t%s\t%s\n' "${ts}" "${ep}" "${out:-000$'\t'30.000000}" >> "${LOG}"
  done
  sleep 1
done
