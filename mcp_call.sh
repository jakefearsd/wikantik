#!/bin/bash
# Helper to make MCP tool calls.
# Usage: ./mcp_call.sh <session_id> <tool_name> '<json_args>'
SESSION="$1"
TOOL="$2"
ARGS="$3"
ID="${4:-$RANDOM}"

curl -s -X POST http://localhost:8080/mcp \
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
