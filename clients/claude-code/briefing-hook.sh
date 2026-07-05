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
# Wikantik context briefing — UserPromptSubmit hook.
# Injects GET /api/briefing (format=md) into context ONCE per Claude Code session.
# Never fails the prompt: any error exits 0 with no output.
set -uo pipefail

INPUT=$(cat) || exit 0
command -v jq >/dev/null 2>&1 || exit 0
[ -n "${WIKANTIK_BASE_URL:-}" ] || exit 0

SESSION_ID=$(printf '%s' "$INPUT" | jq -r '.session_id // empty')
PROMPT=$(printf '%s' "$INPUT" | jq -r '.prompt // empty')
[ -n "$SESSION_ID" ] || exit 0
SESSION_ID=${SESSION_ID//\//_}   # sanitize: a path separator in session_id must not escape STATE_DIR

STATE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/wikantik-briefing"
mkdir -p "$STATE_DIR" || exit 0
STATE_FILE="$STATE_DIR/$SESSION_ID.done"
[ -e "$STATE_FILE" ] && exit 0
: > "$STATE_FILE"   # mark BEFORE fetching: a broken wiki must not retry every prompt

AUTH_ARGS=()
[ -n "${WIKANTIK_BASIC_AUTH:-}" ] && AUTH_ARGS=(-u "$WIKANTIK_BASIC_AUTH")

# ${AUTH_ARGS[@]+...} guard: plain "${AUTH_ARGS[@]}" on an empty array is an
# "unbound variable" error under set -u on bash < 4.4 (incl. macOS stock bash 3.2).
RESP=$(curl -fsS --max-time 10 ${AUTH_ARGS[@]+"${AUTH_ARGS[@]}"} -G "${WIKANTIK_BASE_URL%/}/api/briefing" \
    --data-urlencode "pins=${WIKANTIK_BRIEFING_PINS:-}" \
    --data-urlencode "clusters=${WIKANTIK_BRIEFING_CLUSTERS:-}" \
    --data-urlencode "prompt=$PROMPT" \
    --data-urlencode "budget=${WIKANTIK_BRIEFING_BUDGET:-}" \
    --data-urlencode "format=md") || exit 0

printf '%s\n' "$RESP"
