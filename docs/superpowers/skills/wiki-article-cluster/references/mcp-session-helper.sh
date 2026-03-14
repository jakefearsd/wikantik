#!/usr/bin/env bash
# MCP Session Helper — shell functions for JSPWiki MCP API interaction
# Source this file, then call mcp_init before making tool calls.
#
# IMPORTANT: Shell state does not persist between Claude Code Bash tool calls.
# You must source this file at the start of EVERY Bash command that uses its functions.
#
# Usage:
#   source mcp-session-helper.sh
#   mcp_init
#   mcp_call "read_page" '{"pageName": "Main"}'
#   mcp_write_page /tmp/mcp_MyPage.json
#   mcp_read_page "MyPage"
#   mcp_search_pages "retirement investing"
#   mcp_get_broken_links
#   mcp_get_stats

MCP_URL="http://localhost:8080/mcp"
MCP_SESSION_FILE="/tmp/mcp_session_id"
MCP_TIMEOUT=15

# Initialize MCP session: send initialize request, capture session ID, complete handshake.
# Stores session ID in /tmp/mcp_session_id for subsequent calls.
mcp_init() {
    local response_headers
    response_headers=$(mktemp)

    # Step 1: Initialize — capture session ID from response header
    curl -s -D "$response_headers" \
        -H "Content-Type: application/json" \
        -H "Accept: text/event-stream, application/json" \
        --max-time "$MCP_TIMEOUT" \
        -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"jspwiki-skill","version":"1.0"}}}' \
        "$MCP_URL" > /dev/null

    local session_id
    session_id=$(grep -i 'Mcp-Session-Id' "$response_headers" | tr -d '\r' | awk '{print $2}')
    rm -f "$response_headers"

    if [ -z "$session_id" ]; then
        echo "ERROR: Failed to obtain MCP session ID. Is the wiki running at $MCP_URL?" >&2
        return 1
    fi

    echo "$session_id" > "$MCP_SESSION_FILE"

    # Step 2: Complete handshake with initialized notification
    curl -s \
        -H "Content-Type: application/json" \
        -H "Accept: text/event-stream, application/json" \
        -H "Mcp-Session-Id: $session_id" \
        --max-time "$MCP_TIMEOUT" \
        -d '{"jsonrpc":"2.0","method":"notifications/initialized"}' \
        "$MCP_URL" > /dev/null

    echo "MCP session initialized: $session_id"
}

# Call an MCP tool by name with a JSON arguments object.
# Usage: mcp_call <tool_name> <args_json>
# Returns: parsed JSON result from the data: SSE line
#
# Note: The heredoc below is intentionally unquoted so that $tool_name and
# $args_json are interpolated. This is safe because both values come from
# function arguments, not user input. The skill's advice about single-quoted
# heredocs applies to PAYLOAD FILES containing article content, not here.
mcp_call() {
    local tool_name="$1"
    local args_json="$2"
    local session_id

    session_id=$(_mcp_get_session) || return 1

    local payload
    payload=$(mktemp)
    cat << ENDJSON > "$payload"
{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$tool_name","arguments":$args_json}}
ENDJSON

    local result
    result=$(curl -s \
        -H "Content-Type: application/json" \
        -H "Accept: text/event-stream, application/json" \
        -H "Mcp-Session-Id: $session_id" \
        --max-time "$MCP_TIMEOUT" \
        -d @"$payload" \
        "$MCP_URL" | grep 'data:' | head -1 | sed 's/^data://')

    rm -f "$payload"

    if [ -z "$result" ]; then
        echo "ERROR: Empty response — session may have expired. Run mcp_init to re-establish." >&2
        return 1
    fi

    echo "$result"
}

# Publish a page from a pre-built JSON payload file.
# Usage: mcp_write_page <payload_file>
# The payload file must contain a complete JSON-RPC tools/call request for write_page.
mcp_write_page() {
    local payload_file="$1"
    local session_id

    session_id=$(_mcp_get_session) || return 1

    if [ ! -f "$payload_file" ]; then
        echo "ERROR: Payload file not found: $payload_file" >&2
        return 1
    fi

    local result
    result=$(curl -s \
        -H "Content-Type: application/json" \
        -H "Accept: text/event-stream, application/json" \
        -H "Mcp-Session-Id: $session_id" \
        --max-time "$MCP_TIMEOUT" \
        -d @"$payload_file" \
        "$MCP_URL" | grep 'data:' | head -1 | sed 's/^data://')

    if [ -z "$result" ]; then
        echo "ERROR: Empty response — session may have expired. Run mcp_init to re-establish." >&2
        return 1
    fi

    echo "$result"
}

# Read a page by name and return the JSON result.
# Usage: mcp_read_page <pageName>
mcp_read_page() {
    local page_name="$1"
    mcp_call "read_page" "{\"pageName\": \"$page_name\"}"
}

# Search pages by query string.
# Usage: mcp_search_pages <query>
mcp_search_pages() {
    local query="$1"
    mcp_call "search_pages" "{\"query\": \"$query\"}"
}

# Get all broken links in the wiki.
# Usage: mcp_get_broken_links
mcp_get_broken_links() {
    mcp_call "get_broken_links" "{}"
}

# Get wiki-wide statistics.
# Usage: mcp_get_stats
mcp_get_stats() {
    mcp_call "get_wiki_stats" "{}"
}

# Get outbound links from a page.
# Usage: mcp_get_outbound_links <pageName>
# Works for both wiki-syntax and Markdown-style [text](PageName) links.
mcp_get_outbound_links() {
    local page_name="$1"
    mcp_call "get_outbound_links" "{\"pageName\": \"$page_name\"}"
}

# Get backlinks (pages linking to this page).
# Usage: mcp_get_backlinks <pageName>
# Works for both wiki-syntax and Markdown-style [text](PageName) links.
mcp_get_backlinks() {
    local page_name="$1"
    mcp_call "get_backlinks" "{\"pageName\": \"$page_name\"}"
}

# Patch a page using a pre-built JSON payload file (patch_page tool).
# Usage: mcp_patch_page <payload_file>
# The payload file must contain a complete JSON-RPC tools/call request for patch_page.
mcp_patch_page() {
    mcp_write_page "$1"  # same transport — different tool name is in the payload
}

# Update metadata fields on a page using a pre-built JSON payload file.
# Usage: mcp_update_metadata <payload_file>
# The payload file must contain a complete JSON-RPC tools/call request for update_metadata.
mcp_update_metadata() {
    mcp_write_page "$1"  # same transport — different tool name is in the payload
}

# Internal: retrieve stored session ID or error.
_mcp_get_session() {
    if [ ! -f "$MCP_SESSION_FILE" ]; then
        echo "ERROR: No MCP session. Run mcp_init first." >&2
        return 1
    fi
    cat "$MCP_SESSION_FILE"
}
