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
# 7 approved:"true" as text (not the boolean), 8 an approved answer for another version. It records the
# Authorization header it was sent.
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
        body = {"1": {"approved": True, "state": "approved", "version": version},
                "2": {"approved": False, "state": "requested", "version": version},
                "7": {"approved": "true", "state": "approved", "version": version},
                "8": {"approved": True, "state": "approved", "version": "9.9.9"}}.get(dep)
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

# The deploy scripts call the gate only when asked to.
for script in bin/deploy-release.sh bin/remote.sh; do
    bash -n "${script}" || fail "${script} does not parse"
    grep -q 'WORK_DEPLOYMENT_ID' "${script}" || fail "${script} does not know WORK_DEPLOYMENT_ID"
done
ok "both deploy scripts parse and know WORK_DEPLOYMENT_ID"

echo "ALL PASSED"
