#!/usr/bin/env bash
# Tests for bin/loadtest.sh's settings precedence: a value the caller exports
# (BASE_URL, K6_PROMETHEUS_RW_SERVER_URL) must win over loadtest.env, which
# only fills in what the caller left unset. Before this, sourcing loadtest.env
# silently replaced a caller's BASE_URL, so bin/profile-iteration.sh
# --base-url drove k6 at whatever host loadtest.env named while JFR recorded
# the requested one.
#
# Style: PATH-shimmed `k6` and --dry-run — no load is ever generated. The
# env file is a fixture passed via LOADTEST_ENV_FILE.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="${REPO_ROOT}/bin/loadtest.sh"

FAILURES=0
fail() { echo "FAIL: $*" >&2; FAILURES=$((FAILURES + 1)); }
ok()   { echo "ok: $*"; }

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

mkdir -p "${TMP}/bin"
printf '#!/bin/sh\nexit 0\n' > "${TMP}/bin/k6"
chmod +x "${TMP}/bin/k6"

cat > "${TMP}/loadtest.env" <<'EOF'
BASE_URL=http://from-env-file:18080
K6_PROMETHEUS_RW_SERVER_URL=http://from-env-file:9090/api/v1/write
LOADTEST_MCP_KEY=file-key
EOF

run() {  # run <extra env assignments...> — prints the dry-run k6 command
  env -i HOME="${HOME}" PATH="${TMP}/bin:/usr/bin:/bin" \
      LOADTEST_ENV_FILE="${TMP}/loadtest.env" "$@" \
      bash "${SCRIPT}" smoke --dry-run 2>&1
}

# 1. Caller's BASE_URL wins over the env file.
out="$(run BASE_URL=http://caller:8080)"
if grep -q 'BASE_URL=http://caller:8080' <<<"${out}"; then
  ok "caller BASE_URL wins over loadtest.env"
else
  fail "caller BASE_URL was overridden by loadtest.env: ${out}"
fi

# 2. Without a caller value, the env file still supplies BASE_URL.
out="$(run)"
if grep -q 'BASE_URL=http://from-env-file:18080' <<<"${out}"; then
  ok "loadtest.env supplies BASE_URL when the caller sets none"
else
  fail "loadtest.env BASE_URL not used when caller sets none: ${out}"
fi

# 3. A caller can switch remote-write off by exporting it empty.
out="$(run K6_PROMETHEUS_RW_SERVER_URL=)"
if grep -q 'experimental-prometheus-rw' <<<"${out}"; then
  fail "caller-empty K6_PROMETHEUS_RW_SERVER_URL still enabled remote write: ${out}"
else
  ok "caller can disable Prometheus remote write"
fi

# 4. ... and the env file's remote-write target applies otherwise.
out="$(run)"
if grep -q 'experimental-prometheus-rw' <<<"${out}"; then
  ok "loadtest.env remote-write target applies by default"
else
  fail "loadtest.env K6_PROMETHEUS_RW_SERVER_URL ignored: ${out}"
fi

# 5. Unset caller values still come from the file (credentials).
out="$(run BASE_URL=http://caller:8080)"
if grep -q 'LOADTEST_MCP_KEY=file-key' <<<"${out}"; then
  ok "loadtest.env fills values the caller left unset"
else
  fail "loadtest.env credentials lost: ${out}"
fi

# 6. bin/profile-iteration.sh --base-url drives BOTH the JFR start and k6 at
#    the requested host, not loadtest.env's. curl is shimmed: it records its
#    argv and answers the JFR start with a recording id; the run then stops at
#    the (fixture-less) report step, which is after everything asserted here.
PROFILE_SCRIPT="${PROFILE_SCRIPT:-${REPO_ROOT}/bin/profile-iteration.sh}"
cat > "${TMP}/bin/curl" <<EOF
#!/bin/sh
echo "\$*" >> "${TMP}/curl.log"
case "\$*" in *jfr/start*) echo '{"recording_id":"r1"}' ;; esac
exit 0
EOF
cat > "${TMP}/bin/k6" <<EOF
#!/bin/sh
echo "\$*" >> "${TMP}/k6.log"
exit 0
EOF
chmod +x "${TMP}/bin/curl" "${TMP}/bin/k6"
: > "${TMP}/curl.log"; : > "${TMP}/k6.log"
# Run a copy placed in a scratch repo root so its loadtest/results output
# never lands in the real checkout.
mkdir -p "${TMP}/repo/bin" "${TMP}/repo/loadtest"
cp "${PROFILE_SCRIPT}" "${TMP}/repo/bin/profile-iteration.sh"
cp "${SCRIPT}" "${TMP}/repo/bin/loadtest.sh"
cp "${TMP}/loadtest.env" "${TMP}/repo/loadtest/loadtest.env"   # the default location
env -i HOME="${HOME}" PATH="${TMP}/bin:/usr/bin:/bin" \
    LOADTEST_ADMIN_USER=u LOADTEST_ADMIN_PASS=p \
    bash "${TMP}/repo/bin/profile-iteration.sh" t1 --base-url http://cli:8080 \
    --duration 1s --admin-vus 0 >/dev/null 2>&1
if grep -q 'http://cli:8080/admin/profiling/jfr/start' "${TMP}/curl.log"; then
  ok "profile-iteration --base-url targets the JFR start"
else
  fail "profile-iteration JFR start went elsewhere: $(grep jfr/start "${TMP}/curl.log")"
fi
if grep -q 'BASE_URL=http://cli:8080' "${TMP}/k6.log"; then
  ok "profile-iteration --base-url targets the k6 load"
else
  fail "profile-iteration k6 load went elsewhere: $(cat "${TMP}/k6.log")"
fi

if (( FAILURES > 0 )); then
  echo "${FAILURES} failure(s)" >&2
  exit 1
fi
echo "all loadtest.sh tests passed"
