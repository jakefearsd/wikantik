#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
# Wikantik context briefing — Antigravity PreInvocation hook.
# Structurally mirrors clients/claude-code/briefing-hook.sh: fetches
# GET /api/briefing (format=md) and injects it ONCE per session.
#
# PreInvocation fires before EVERY model call in Antigravity (there is no
# confirmed dedicated session-start event as of this writing — see
# clients/antigravity/README.md "Research findings"), so the once-per-session
# state-file gate below is load-bearing, not an optimization.
#
# Never fails the model call: any error exits 0 with a no-op JSON response.
set -uo pipefail

NOOP='{}'

INPUT=$(cat) || { printf '%s\n' "$NOOP"; exit 0; }
command -v jq >/dev/null 2>&1 || { printf '%s\n' "$NOOP"; exit 0; }
[ -n "${WIKANTIK_BASE_URL:-}" ] || { printf '%s\n' "$NOOP"; exit 0; }

# transcriptPath identifies the session; documented hook payload field name
# per community sources (no first-party schema was fetchable). Falls back to
# sessionId/session_id in case a given Antigravity build names it differently.
SESSION_KEY=$(printf '%s' "$INPUT" | jq -r '.transcriptPath // .sessionId // .session_id // empty')
PROMPT=$(printf '%s' "$INPUT" | jq -r '.prompt // empty')
if [ -z "$SESSION_KEY" ]; then
    printf '%s\n' "$NOOP"
    exit 0
fi
# sanitize: a path separator in the session key must not escape STATE_DIR
SESSION_KEY=$(printf '%s' "$SESSION_KEY" | tr '/' '_')

STATE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/wikantik-briefing"
mkdir -p "$STATE_DIR" || { printf '%s\n' "$NOOP"; exit 0; }
STATE_FILE="$STATE_DIR/$SESSION_KEY.done"
if [ -e "$STATE_FILE" ]; then
    printf '%s\n' "$NOOP"
    exit 0
fi
: > "$STATE_FILE"   # mark BEFORE fetching: a broken wiki must not retry every model call

AUTH_ARGS=()
[ -n "${WIKANTIK_BASIC_AUTH:-}" ] && AUTH_ARGS=(-u "$WIKANTIK_BASIC_AUTH")

# ${AUTH_ARGS[@]+...} guard: plain "${AUTH_ARGS[@]}" on an empty array is an
# "unbound variable" error under set -u on bash < 4.4 (incl. macOS stock bash 3.2).
RESP=$(curl -fsS --max-time 10 ${AUTH_ARGS[@]+"${AUTH_ARGS[@]}"} -G "${WIKANTIK_BASE_URL%/}/api/briefing" \
    --data-urlencode "pins=${WIKANTIK_BRIEFING_PINS:-}" \
    --data-urlencode "clusters=${WIKANTIK_BRIEFING_CLUSTERS:-}" \
    --data-urlencode "prompt=$PROMPT" \
    --data-urlencode "budget=${WIKANTIK_BRIEFING_BUDGET:-}" \
    --data-urlencode "format=md") || { printf '%s\n' "$NOOP"; exit 0; }

jq -n --arg ctx "$RESP" '{additionalContext: $ctx}'
