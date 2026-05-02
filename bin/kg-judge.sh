#!/usr/bin/env bash
# Trigger an ad-hoc Knowledge Graph judge run against the local deployment.
#
# Usage:
#   bin/kg-judge.sh                       # fire-and-forget runner pass
#   bin/kg-judge.sh --proposal-id UUID    # synchronous judge of one proposal
#   bin/kg-judge.sh --status              # show queue depth (pending count)

set -euo pipefail

URL_BASE="${WIKANTIK_URL:-http://localhost:8080}"
TEST_PROPS="$(dirname "$0")/../test.properties"

if [[ ! -f "$TEST_PROPS" ]]; then
    echo "Missing test.properties at $TEST_PROPS — see CLAUDE.md > Manual Testing Credentials" >&2
    exit 2
fi

LOGIN=$(grep '^test.user.login=' "$TEST_PROPS" | cut -d= -f2)
PASS=$(grep '^test.user.password=' "$TEST_PROPS" | cut -d= -f2)

PROPOSAL_ID=""
SHOW_STATUS=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        --proposal-id)
            PROPOSAL_ID="$2"
            shift 2
            ;;
        --status)
            SHOW_STATUS=true
            shift
            ;;
        -h|--help)
            sed -n '2,8p' "$0"
            exit 0
            ;;
        *)
            echo "Unknown arg: $1" >&2
            exit 2
            ;;
    esac
done

if [[ "$SHOW_STATUS" == true ]]; then
    curl -fsS -u "${LOGIN}:${PASS}" \
        "${URL_BASE}/admin/knowledge-graph/proposals?status=pending&limit=1" \
        | head -c 500
    echo
    exit 0
fi

if [[ -n "$PROPOSAL_ID" ]]; then
    echo "Judging proposal ${PROPOSAL_ID}..."
    curl -fsS -u "${LOGIN}:${PASS}" -X POST \
        "${URL_BASE}/admin/knowledge-graph/proposals/${PROPOSAL_ID}/judge"
else
    echo "Triggering background judge runner..."
    curl -fsS -u "${LOGIN}:${PASS}" -X POST \
        "${URL_BASE}/admin/knowledge-graph/judge/run"
fi
echo
