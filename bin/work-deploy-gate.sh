#!/usr/bin/env bash
#
# work-deploy-gate.sh — ask work management whether a deployment may go ahead.
#
# A deploy script calls this before it builds or ships anything. It asks
#   GET $WORK_API_URL/api/work/deployments/<id>/gate?version=<version>
# and says yes only when the answer is a clear yes: the deployment exists, is
# visible to the credential, is for that version, and is approved (or already
# running). Anything else stops the deploy.
#
# Usage:
#   bin/work-deploy-gate.sh <deployment-id> <version>
#
# Env:
#   WORK_API_URL       base URL of the wiki, e.g. https://wiki.example.com   (required)
#   WORK_API_TOKEN     a `work` API key or a work agent's token              (required)
#   WORK_GATE_TIMEOUT  seconds to wait for the answer (default 10)
#
# Prints one line. The token is never printed.
# Exit codes: 0 approved · 1 not approved · 2 could not ask (usage, network,
# a non-200 answer, or an answer that is not what was expected)
set -euo pipefail

say() { printf 'work-deploy-gate: %s\n' "$1"; }

if [[ $# -ne 2 ]]; then
    say "usage: work-deploy-gate.sh <deployment-id> <version>"
    exit 2
fi
ID="$1"
VERSION="$2"
if [[ ! "${ID}" =~ ^[0-9]{1,18}$ ]]; then
    say "the deployment id must be a number"
    exit 2
fi
if [[ ! "${VERSION}" =~ ^[A-Za-z0-9._+-]{1,60}$ ]]; then
    say "the version is not one work management accepts"
    exit 2
fi
if [[ -z "${WORK_API_URL:-}" ]]; then
    say "WORK_API_URL is not set; cannot ask about deployment ${ID}"
    exit 2
fi
if [[ -z "${WORK_API_TOKEN:-}" ]]; then
    say "WORK_API_TOKEN is not set; cannot ask about deployment ${ID}"
    exit 2
fi

BODY="$(mktemp)"
trap 'rm -f "${BODY}"' EXIT

# The token goes in through a header file descriptor, never the command line (which `ps` shows).
STATUS="$(curl --silent --show-error --output "${BODY}" --write-out '%{http_code}' \
    --max-time "${WORK_GATE_TIMEOUT:-10}" --header 'Accept: application/json' \
    --header @<(printf 'Authorization: Bearer %s\n' "${WORK_API_TOKEN}") \
    "${WORK_API_URL%/}/api/work/deployments/${ID}/gate?version=${VERSION}" 2>/dev/null)" || {
    say "no answer from work management about deployment ${ID} (network error or timeout)"
    exit 2
}
if [[ "${STATUS}" != "200" ]]; then
    say "work management answered HTTP ${STATUS} about deployment ${ID}; not deploying"
    exit 2
fi

# Yes only for the JSON boolean true and the version that was asked about. -I: no user site, no cwd imports.
VERDICT="$(python3 -I - "${BODY}" "${VERSION}" <<'PY' 2>/dev/null
import json, sys
try:
    answer = json.load(open(sys.argv[1]))
    state = answer.get("state")
    if not isinstance(state, str) or "approved" not in answer:
        raise ValueError("unexpected shape")
    safe_state = "".join(c for c in state if c.isalnum() or c == "_")[:20]
    ok = answer["approved"] is True and answer.get("version") == sys.argv[2]
    print(("yes " if ok else "no ") + safe_state)
except Exception:
    print("unreadable")
PY
)" || VERDICT="unreadable"

case "${VERDICT}" in
    "yes "*)
        say "deployment ${ID} of ${VERSION} is approved (${VERDICT#yes }); going ahead"
        exit 0 ;;
    "no "*)
        say "deployment ${ID} is not approved for ${VERSION} (state: ${VERDICT#no }); not deploying"
        exit 1 ;;
    *)
        say "could not read work management's answer about deployment ${ID}; not deploying"
        exit 2 ;;
esac
