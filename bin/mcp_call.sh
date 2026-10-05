#!/bin/bash
# Helper to make MCP tool calls. Posts to $MCP_URL (default http://localhost:8080/wikantik-admin-mcp).
# Usage: bin/mcp_call.sh <session_id> <tool_name> '<json_args>' [request_id]
case "${1:-}" in -h|--help|"")
  sed -n '2,3p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
esac
SESSION="$1"
TOOL="$2"
ARGS="$3"
ID="${4:-$RANDOM}"

curl -s -X POST "${MCP_URL:-http://localhost:8080/wikantik-admin-mcp}" \
  -H "Content-Type: application/json" \
  -H "Accept: text/event-stream, application/json" \
  -H "mcp-session-id: $SESSION" \
  -d "{
    \"jsonrpc\": \"2.0\",
    \"id\": $ID,
    \"method\": \"tools/call\",
    \"params\": {
      \"name\": \"$TOOL\",
      \"arguments\": $ARGS
    }
  }"
