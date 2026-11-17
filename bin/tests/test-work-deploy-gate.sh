#!/usr/bin/env bash
# Tests for bin/work-deploy-gate.sh: the deploy script's question to work management, "is this deployment
# approved for this version?". A local stub server plays the work API; nothing here deploys anything.
# Anything but a clear "approved" must stop a deploy: exit 0 only for approved, 1 for refused, 2 for "could not ask".
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"
GATE="bin/work-deploy-gate.sh"

TMP="$(mktemp -d)"
SERVER_PID=""
cleanup() { [[ -n "${SERVER_PID}" ]] && kill "${SERVER_PID}" 2>/dev/null || true; rm -rf "${TMP}"; return 0; }
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
ok()   { echo "ok: $*"; }

# The stub answers by deployment id: 1 approved, 2 not approved, 3 a 500, 4 not JSON, 5 hangs, 6 a 404,
# 7 approved:"true" as text (not the boolean), 8 an approved answer for another version, 9 approved for staging,
# 10 approved with no environment in the answer. It records the Authorization header and the path it was sent.
cat > "${TMP}/stub.py" <<'PY'
import http.server, json, sys, time, urllib.parse
class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_GET(self):
        open(sys.argv[2], "a").write((self.headers.get("Authorization") or "-") + " " + self.path + "\n")
        parts = urllib.parse.urlparse(self.path)
        dep = parts.path.split("/")[4]
        version = urllib.parse.parse_qs(parts.query).get("version", [""])[0]
        if dep == "3": self.send_response(500); self.end_headers(); self.wfile.write(b'{"error":true}'); return
        if dep == "5": time.sleep(30); return
        if dep == "6": self.send_response(404); self.end_headers(); self.wfile.write(b'{"error":true}'); return
        prod = "production"
        body = {"1": {"approved": True, "state": "approved", "version": version, "environment": prod},
                "2": {"approved": False, "state": "requested", "version": version, "environment": prod},
                "7": {"approved": "true", "state": "approved", "version": version, "environment": prod},
                "8": {"approved": True, "state": "approved", "version": "9.9.9", "environment": prod},
                "9": {"approved": True, "state": "approved", "version": version, "environment": "staging"},
                "10": {"approved": True, "state": "approved", "version": version}}.get(dep)
        self.send_response(200); self.end_headers()
        self.wfile.write(b"<html>not json</html>" if dep == "4" else json.dumps(body).encode())
srv = http.server.ThreadingHTTPServer(("127.0.0.1", 0), H)
open(sys.argv[1], "w").write(str(srv.server_address[1]))
srv.serve_forever()
PY
python3 -I "${TMP}/stub.py" "${TMP}/port" "${TMP}/seen" &
SERVER_PID=$!
for _ in $(seq 1 50); do [[ -s "${TMP}/port" ]] && break; sleep 0.1; done
[[ -s "${TMP}/port" ]] || fail "the stub server did not start"
URL="http://127.0.0.1:$(cat "${TMP}/port")"
TOKEN="wka_secret_token_value"

# run <expected exit> <label> <deployment id> [env assignments...]
run() {
    local want="$1" label="$2" id="$3"; shift 3
    local out rc=0
    out="$(env WORK_API_URL="${URL}" WORK_API_TOKEN="${TOKEN}" WORK_GATE_TIMEOUT=2 "$@" "${GATE}" "${id}" 2.5.26 2>&1)" || rc=$?
    [[ "${rc}" -eq "${want}" ]] || fail "${label}: exit ${rc}, wanted ${want}: ${out}"
    [[ "$(printf '%s\n' "${out}" | wc -l)" -eq 1 ]] || fail "${label}: wanted one line, got: ${out}"
    [[ "${out}" != *"${TOKEN}"* ]] || fail "${label}: the token was printed"
    ok "${label}: exit ${rc}: ${out}"
}

[[ -x "${GATE}" ]] || fail "${GATE} is missing or not executable"

run 0 "approved" 1
run 1 "not approved" 2
run 2 "server error" 3
run 2 "not JSON" 4
run 2 "no answer in time" 5
run 2 "unknown or hidden deployment" 6
run 1 "approved as text is not approved" 7
run 1 "an answer for another version" 8
run 1 "approved for staging is not approved for production" 9
run 0 "approved for staging when staging is what is being deployed" 9 WORK_DEPLOYMENT_ENVIRONMENT=staging
run 1 "an answer that names no environment" 10
run 2 "no URL" 1 WORK_API_URL=
run 2 "no token" 1 WORK_API_TOKEN=
run 2 "nothing listening" 1 WORK_API_URL=http://127.0.0.1:1

rc=0; out="$("${GATE}" 2>&1)" || rc=$?
[[ "${rc}" -eq 2 ]] || fail "no arguments: exit ${rc}, wanted 2"
rc=0; out="$(WORK_API_URL="${URL}" WORK_API_TOKEN="${TOKEN}" "${GATE}" "1;rm" 2.5.26 2>&1)" || rc=$?
[[ "${rc}" -eq 2 ]] || fail "a deployment id that is not a number: exit ${rc}, wanted 2"
ok "usage errors are exit 2"

grep -q "^Bearer ${TOKEN} /api/work/deployments/1/gate?version=2.5.26$" "${TMP}/seen" \
    || fail "the request was not the gate route with the bearer token: $(cat "${TMP}/seen")"
ok "asks the gate route with the token as a bearer"

# A version with a plus sign is sent encoded (a bare + would arrive as a space), and tracing never shows the token.
rc=0; out="$(WORK_API_URL="${URL}" WORK_API_TOKEN="${TOKEN}" "${GATE}" 1 "2.5.26+b1" 2>&1)" || rc=$?
[[ "${rc}" -eq 0 ]] || fail "a version with a plus sign: exit ${rc}: ${out}"
grep -q 'gate?version=2.5.26%2Bb1$' "${TMP}/seen" || fail "the plus sign was not encoded: $(tail -1 "${TMP}/seen")"
out="$(WORK_API_URL="${URL}" WORK_API_TOKEN="${TOKEN}" bash -x "${GATE}" 1 2.5.26 2>&1)" || true
[[ "${out}" != *"${TOKEN}"* ]] || fail "bash -x printed the token"
ok "encodes a plus sign; tracing does not print the token"

# bin/work-deploy-check.sh works out the version that is actually being shipped and asks about that one.
CHECK="bin/work-deploy-check.sh"
[[ -x "${CHECK}" ]] || fail "${CHECK} is missing or not executable"
mkdir -p "${TMP}/bin"
cat > "${TMP}/bin/docker" <<'STUB'
#!/bin/sh
# docker image inspect wikantik:latest --format ... : prints the label the test chose
printf '%s\n' "${STUB_IMAGE_VERSION:-}"
[ -n "${STUB_IMAGE_VERSION:-}" ]
STUB
chmod +x "${TMP}/bin/docker"
check() {
    local want="$1" label="$2"; shift 2
    local out rc=0
    out="$(env PATH="${TMP}/bin:${PATH}" WORK_API_URL="${URL}" WORK_API_TOKEN="${TOKEN}" WORK_GATE_TIMEOUT=2 "$@" 2>&1)" || rc=$?
    [[ "${rc}" -eq "${want}" ]] || fail "${label}: exit ${rc}, wanted ${want}: ${out}"
    [[ "${out}" != *"${TOKEN}"* ]] || fail "${label}: the token was printed"
    ok "${label}: exit ${rc}: $(printf '%s' "${out}" | tail -1)"
}
check 0 "pull: asks about the tag being pulled" WORK_DEPLOYMENT_ID=1 "${CHECK}" pull 2.5.26
grep -q 'gate?version=2.5.26$' "${TMP}/seen" || fail "pull did not ask about the tag"
check 1 "pull: the tag is not the version the operator named" WORK_DEPLOYMENT_ID=1 WORK_DEPLOYMENT_VERSION=2.5.26 "${CHECK}" pull 2.5.27
check 0 "image: asks about the image's own version label" WORK_DEPLOYMENT_ID=1 STUB_IMAGE_VERSION=2.5.30 "${CHECK}" image
grep -q 'gate?version=2.5.30$' "${TMP}/seen" || fail "image did not ask about the image label"
check 1 "image: the label is not the version the operator named" WORK_DEPLOYMENT_ID=1 STUB_IMAGE_VERSION=26.04 WORK_DEPLOYMENT_VERSION=2.5.30 "${CHECK}" image
check 2 "image: no version label to ask about" WORK_DEPLOYMENT_ID=1 "${CHECK}" image
POM_VERSION="$(sed -n 's:^  <version>\(.*\)</version>$:\1:p' pom.xml | head -1)"
check 0 "build: asks about the version in the pom" WORK_DEPLOYMENT_ID=1 "${CHECK}" build
grep -qF "gate?version=${POM_VERSION}" "${TMP}/seen" || fail "build did not ask about the pom version ${POM_VERSION}"
check 1 "refused by work management" WORK_DEPLOYMENT_ID=2 "${CHECK}" pull 2.5.26
check 2 "no deployment id" "${CHECK}" pull 2.5.26
check 2 "an unknown mode" WORK_DEPLOYMENT_ID=1 "${CHECK}" teleport

# The deploy scripts call the gate only when asked to.
for script in bin/deploy-release.sh bin/remote.sh; do
    bash -n "${script}" || fail "${script} does not parse"
    grep -q 'WORK_DEPLOYMENT_ID' "${script}" || fail "${script} does not know WORK_DEPLOYMENT_ID"
    ! grep -q 'WORK_DEPLOY_GATE_CHECKED' "${script}" || fail "${script} can be told the check already happened"
done
grep -q 'work-deploy-check.sh' bin/remote.sh || fail "remote.sh does not ask about the version it ships"
ok "both deploy scripts parse, know WORK_DEPLOYMENT_ID, and cannot be told to skip the check"

echo "ALL PASSED"
