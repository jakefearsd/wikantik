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
# Tests for docker/docker-compose.embeddings.yml. Pure YAML/config assertions —
# does not start a container, so it runs anywhere.
set -euo pipefail
REPO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE="${REPO_DIR}/docker/docker-compose.embeddings.yml"
fails=0
check() { # check <description> <pattern>
  if grep -q -- "$2" "$COMPOSE"; then echo "  ok: $1"; else echo "  FAIL: $1 (no match for: $2)"; fails=$((fails+1)); fi
}
echo "test-embeddings: $COMPOSE"
[ -f "$COMPOSE" ] || { echo "  FAIL: compose file missing"; exit 1; }
check "uses the ollama image"            "image: ollama/ollama"
check "port is parameterised"            'WIKANTIK_EMBEDDING_PORT'
check "model tag is parameterised"       'WIKANTIK_EMBEDDING_MODEL_TAG'
check "defaults to qwen3-embedding:0.6b" 'qwen3-embedding:0.6b'
check "caches models in a named volume"  'ollama-models'
check "has a healthcheck"                'healthcheck:'
# The pull must be idempotent-on-restart, not a one-shot init container.
# Anchored to the actual invocation (not just the substring "ollama pull"),
# which also appears in a prose comment above the entrypoint script — a plain
# substring match would still pass if a future edit deleted the real
# invocation but left the comment behind.
check "pulls the model on start"         'ollama pull "\${WIKANTIK_EMBEDDING_MODEL_TAG'

TEMPLATE="${REPO_DIR}/wikantik-war/src/main/config/tomcat/wikantik-custom-postgresql.properties.template"
DEPLOY="${REPO_DIR}/bin/deploy-local.sh"
ENVEX="${REPO_DIR}/.env.example"
LIBFILE="${REPO_DIR}/bin/lib/embeddings.sh"
checkf() { # checkf <description> <file> <pattern>
  if grep -q -- "$3" "$2"; then echo "  ok: $1"; else echo "  FAIL: $1 (no match for: $3)"; fails=$((fails+1)); fi
}
checkf "template carries the base-url placeholder" "$TEMPLATE" '@@EMBEDDING_BASE_URL@@'
checkf "deploy-local substitutes it"               "$DEPLOY"   '@@EMBEDDING_BASE_URL@@'
checkf "deploy-local can be opted out"             "$DEPLOY"   'WIKANTIK_LOCAL_EMBEDDINGS'
# The readiness/repair logic itself lives in bin/lib/embeddings.sh (sourced by
# deploy-local.sh) precisely so it can be exercised directly below, with a
# stubbed curl and temp files, instead of only being grepped for as text.
checkf "deploy-local sources the embeddings lib"     "$DEPLOY"  'lib/embeddings.sh'
checkf "deploy-local wires up the repair call"       "$DEPLOY"  'repair_embedding_base_url'
checkf "lib knows the deployed property key"         "$LIBFILE" 'wikantik.search.embedding.base-url'
checkf "lib guards the one known-dead host, not a blind overwrite" "$LIBFILE" 'WIKANTIK_KNOWN_DEAD_EMBEDDING_URL'
checkf ".env.example documents the base url"       "$ENVEX"    'WIKANTIK_EMBEDDING_BASE_URL'
checkf ".env.example documents the opt-out"        "$ENVEX"    'WIKANTIK_LOCAL_EMBEDDINGS'

# =============================================================================
# Behavioural coverage for embeddings_model_ready() and repair_embedding_base_url()
# (bin/lib/embeddings.sh). Pure shell: curl is stubbed on PATH (same pattern
# as bin/tests/test-audit-retention.sh stubbing psql/pg_dump/pg_restore), and
# property files are temp fixtures. No Docker, no live wiki, no network.
# =============================================================================
STUBDIR="$(mktemp -d)"
MSGLOG="$(mktemp)"
cleanup_lib_test() { rm -rf "$STUBDIR"; rm -f "$MSGLOG"; }
trap cleanup_lib_test EXIT

cat > "$STUBDIR/curl" <<'STUB'
#!/usr/bin/env bash
# Ignores its real args entirely — the test controls the response via env,
# not by parsing the URL. Fine here because each test case makes exactly one
# curl call per embeddings_model_ready() invocation.
[ -n "${CURL_STUB_BODY+x}" ] && printf '%s' "$CURL_STUB_BODY"
exit "${CURL_STUB_RC:-0}"
STUB
chmod +x "$STUBDIR/curl"

# repair_embedding_base_url() calls print_status/print_warning; provide real
# (log-capturing) implementations so it can be sourced standalone.
print_status()  { echo "STATUS: $1"  >> "$MSGLOG"; }
print_warning() { echo "WARNING: $1" >> "$MSGLOG"; }

export PATH="${STUBDIR}:${PATH}"
# shellcheck disable=SC1090
source "$LIBFILE"

pass() { echo "  ok: $1"; }
fail() { echo "  FAIL: $1"; fails=$((fails+1)); }

# --- embeddings_model_ready(): the readiness gate this task exists to fix ---

if CURL_STUB_RC=0 CURL_STUB_BODY='{"models":[]}' \
   embeddings_model_ready "http://localhost:11434" "qwen3-embedding:0.6b"; then
  fail "embeddings_model_ready true on HTTP 200 + empty model list"
else
  pass "embeddings_model_ready false on HTTP 200 + empty model list (the Task 1 weakness)"
fi

if CURL_STUB_RC=0 CURL_STUB_BODY='{"models":[{"name":"qwen3-embedding:0.6b"}]}' \
   embeddings_model_ready "http://localhost:11434" "qwen3-embedding:0.6b"; then
  pass "embeddings_model_ready true when the configured tag is present"
else
  fail "embeddings_model_ready false when the configured tag is present"
fi

# A variant tag must NOT satisfy the check. Ollama's /api/tags is matched as text,
# so an unanchored substring match reports ready when only "…:0.6b-instruct" is
# pulled — the wiki would then embed against a model of a different dimension, or
# none at all. Quote-bounding the tag is what makes this exact.
if CURL_STUB_RC=0 CURL_STUB_BODY='{"models":[{"name":"qwen3-embedding:0.6b-instruct"}]}' \
   embeddings_model_ready "http://localhost:11434" "qwen3-embedding:0.6b"; then
  fail "embeddings_model_ready true when only a VARIANT tag (:0.6b-instruct) is present"
else
  pass "embeddings_model_ready false when only a variant tag is present"
fi

if CURL_STUB_RC=7 CURL_STUB_BODY='' \
   embeddings_model_ready "http://localhost:19999" "qwen3-embedding:0.6b"; then
  fail "embeddings_model_ready true when the server is unreachable"
else
  pass "embeddings_model_ready false when the server is unreachable (curl fails)"
fi

# --- repair_embedding_base_url(): all properties-file states ---

# State 1: key entirely absent -> appended.
PROPSFILE="$(mktemp)"
printf 'wikantik.applicationName = Wikantik\n' > "$PROPSFILE"
repair_embedding_base_url "$PROPSFILE" "http://localhost:11434" "qwen3-embedding:0.6b"
if grep -qE '^wikantik\.search\.embedding\.base-url[[:space:]]*=[[:space:]]*http://localhost:11434$' "$PROPSFILE"; then
  pass "repair appends the setting when the key is absent"
else
  fail "repair did not append a missing key"
fi
rm -f "$PROPSFILE"

# State 2: key already correct -> left untouched, no warning (the earlier
# "Embeddings available" / "fell back to BM25" check in deploy-local.sh
# already covers this URL; repeating it here would just be noise).
PROPSFILE="$(mktemp)"
printf 'wikantik.search.embedding.base-url = http://localhost:11434\n' > "$PROPSFILE"
: > "$MSGLOG"
repair_embedding_base_url "$PROPSFILE" "http://localhost:11434" "qwen3-embedding:0.6b"
if [ "$(grep -c 'embedding.base-url' "$PROPSFILE")" -eq 1 ] \
   && grep -qE '^wikantik\.search\.embedding\.base-url[[:space:]]*=[[:space:]]*http://localhost:11434$' "$PROPSFILE" \
   && ! grep -q "WARNING" "$MSGLOG"; then
  pass "repair leaves an already-correct value untouched and silent"
else
  fail "repair mishandled an already-correct value"
fi
rm -f "$PROPSFILE"

# State 3a: stale value = the ONE known-dead host -> auto-corrected, and says so.
PROPSFILE="$(mktemp)"
printf 'wikantik.search.embedding.base-url   = http://inference.jakefear.com:11434\n' > "$PROPSFILE"
: > "$MSGLOG"
repair_embedding_base_url "$PROPSFILE" "http://localhost:11434" "qwen3-embedding:0.6b"
if grep -qE '^wikantik\.search\.embedding\.base-url[[:space:]]*=[[:space:]]*http://localhost:11434$' "$PROPSFILE" \
   && grep -q "inference.jakefear.com:11434" "$MSGLOG"; then
  pass "repair auto-corrects the known-dead inference host and names it"
else
  fail "repair did not auto-correct the known-dead host"
fi
rm -f "$PROPSFILE"

# State 3b: stale value = some OTHER endpoint, not currently serving the tag
# -> left untouched (never silently overwritten — Finding 1's core ask), but
# a clear warning names the bad value and the exact fix.
PROPSFILE="$(mktemp)"
printf 'wikantik.search.embedding.base-url = http://my-custom-host:9999\n' > "$PROPSFILE"
: > "$MSGLOG"
CURL_STUB_RC=0 CURL_STUB_BODY='{"models":[]}' \
  repair_embedding_base_url "$PROPSFILE" "http://localhost:11434" "qwen3-embedding:0.6b"
if grep -qE '^wikantik\.search\.embedding\.base-url[[:space:]]*=[[:space:]]*http://my-custom-host:9999$' "$PROPSFILE" \
   && grep -q "WARNING" "$MSGLOG" && grep -q "my-custom-host:9999" "$MSGLOG"; then
  pass "repair warns but never overwrites a stale non-dead custom value"
else
  fail "repair mishandled a stale non-dead custom value"
fi
rm -f "$PROPSFILE"

# State 3c: a DELIBERATE, WORKING custom endpoint (different from the local
# default, but genuinely serving the tag) -> left untouched AND silent. Proves
# the point of Finding 1: a developer's own reachable endpoint is legitimate.
PROPSFILE="$(mktemp)"
printf 'wikantik.search.embedding.base-url = http://my-own-ollama:11500\n' > "$PROPSFILE"
: > "$MSGLOG"
CURL_STUB_RC=0 CURL_STUB_BODY='{"models":[{"name":"qwen3-embedding:0.6b"}]}' \
  repair_embedding_base_url "$PROPSFILE" "http://localhost:11434" "qwen3-embedding:0.6b"
if grep -qE '^wikantik\.search\.embedding\.base-url[[:space:]]*=[[:space:]]*http://my-own-ollama:11500$' "$PROPSFILE" \
   && [ ! -s "$MSGLOG" ]; then
  pass "repair stays silent on a deliberate, working custom endpoint"
else
  fail "repair should not warn about a working custom endpoint"
fi
rm -f "$PROPSFILE"

# --- Finding 3: WIKANTIK_LOCAL_EMBEDDINGS=false must not bake in a URL
# nothing serves. Mirrors the exact sed pair deploy-local.sh runs for the
# opted-out fresh-install branch — if that block's shape changes, update
# this alongside it.
RENDERED_OUT="$(sed -e "s|@@REPO_ROOT@@|/tmp/fake-root|g" \
                     -e "/@@EMBEDDING_BASE_URL@@/s/^/#/" \
                     -e "s|@@EMBEDDING_BASE_URL@@|(unset — WIKANTIK_LOCAL_EMBEDDINGS=false; falls back to the ini-bundle default)|" \
                     "$TEMPLATE")"
if printf '%s\n' "$RENDERED_OUT" | grep -qE '^wikantik\.search\.embedding\.base-url[[:space:]]*=[[:space:]]*http://localhost:11434'; then
  fail "opted-out (WIKANTIK_LOCAL_EMBEDDINGS=false) fresh install still bakes in http://localhost:11434"
else
  pass "opted-out fresh install leaves base-url commented out, matching .env.example"
fi

# --- The key must appear EXACTLY ONCE in the rendered file, in BOTH branches.
# java.util.Properties is last-wins, so a second copy further down silently
# overrides an operator's edit to the documented one. The template briefly
# carried both (documented+commented in the embedding block, live at the
# bottom), which is precisely the silent-stale-config failure this whole
# change set exists to eliminate.
RENDERED_ON="$(sed -e "s|@@REPO_ROOT@@|/tmp/fake-root|g" \
                    -e "s|@@EMBEDDING_BASE_URL@@|http://localhost:11434|g" \
                    "$TEMPLATE")"
live_count()  { printf '%s\n' "$1" | grep -cE '^[[:space:]]*wikantik\.search\.embedding\.base-url[[:space:]]*=' || true; }
total_count() { printf '%s\n' "$1" | grep -cE '^[[:space:]]*#?[[:space:]]*wikantik\.search\.embedding\.base-url[[:space:]]*=' || true; }

# Total occurrences (live OR commented) must be 1 in both branches: a second
# copy anywhere is what an operator uncomments and then finds overridden.
for _branch in "on:$RENDERED_ON" "off:$RENDERED_OUT"; do
  _name="${_branch%%:*}"
  _n="$(total_count "${_branch#*:}")"
  if [ "$_n" -eq 1 ]; then
    pass "rendered file (${_name}) mentions wikantik.search.embedding.base-url exactly once"
  else
    fail "rendered file (${_name}) mentions wikantik.search.embedding.base-url ${_n} times — Properties is last-wins, so a duplicate silently overrides the documented one"
  fi
done

# The live-rendered branch must actually be uncommented, or the whole point of
# rendering it is lost and the install silently inherits the dead ini default.
if [ "$(live_count "$RENDERED_ON")" -eq 1 ] \
   && printf '%s\n' "$RENDERED_ON" | grep -qE '^wikantik\.search\.embedding\.base-url[[:space:]]*=[[:space:]]*http://localhost:11434$'; then
  pass "rendered file (on) sets base-url live exactly once"
else
  fail "rendered file (on) does not set base-url live exactly once"
fi
# The opted-out branch must set NOTHING live — it falls back to the ini default.
if [ "$(live_count "$RENDERED_OUT")" -eq 0 ]; then
  pass "rendered file (off) leaves base-url entirely commented out"
else
  fail "rendered file (off) still declares base-url live"
fi

# --- embedding_url_split(): the naive ${url##*:} it replaces returned
# "//localhost" for a port-less URL and "11434/" for a trailing slash, and
# deploy-local.sh runs under `set -e`, so `docker compose` then aborted the
# ENTIRE deploy before Tomcat was configured.
check_split() { # check_split <url> <expected "host port">
  local got; got="$(embedding_url_split "$1")"
  if [ "$got" = "$2" ]; then
    pass "embedding_url_split '$1' -> '$2'"
  else
    fail "embedding_url_split '$1' -> '$got' (expected '$2')"
  fi
}
check_split "http://localhost:11434"   "localhost 11434"
check_split "http://localhost:11434/"  "localhost 11434"
check_split "http://localhost"         "localhost 11434"
check_split "http://localhost/"        "localhost 11434"
check_split "http://embed.example.com:8080/v1" "embed.example.com 8080"
check_split "localhost:11435"          "localhost 11435"

if embedding_host_is_local "localhost" && embedding_host_is_local "127.0.0.1"; then
  pass "embedding_host_is_local accepts loopback"
else
  fail "embedding_host_is_local rejects loopback"
fi
if embedding_host_is_local "embed.example.com"; then
  fail "embedding_host_is_local accepted a remote host — deploy-local.sh would publish the remote's port locally and then poll the remote"
else
  pass "embedding_host_is_local rejects a remote host"
fi
checkf "deploy-local refuses to containerise a remote base-url" "$DEPLOY" 'embedding_host_is_local'

# --- repair_embedding_base_url() on a file that already carries the key TWICE.
# An unfiltered grep returns both lines, so `existing_line` is multi-line, both
# [[ == ]] comparisons fall through, and the function emits a nonsense
# multi-line warning instead of repairing anything.
PROPSFILE="$(mktemp)"
: > "$MSGLOG"
printf 'wikantik.search.embedding.base-url = http://inference.jakefear.com:11434\nwikantik.applicationName = Wikantik\nwikantik.search.embedding.base-url = http://inference.jakefear.com:11434\n' > "$PROPSFILE"
repair_embedding_base_url "$PROPSFILE" "http://localhost:11434" "qwen3-embedding:0.6b"
if [ "$(grep -c 'inference.jakefear.com' "$PROPSFILE" || true)" -eq 0 ] \
   && [ "$(grep -c 'http://localhost:11434' "$PROPSFILE" || true)" -eq 2 ]; then
  pass "repair rewrites EVERY duplicate of the known-dead URL"
else
  fail "repair left a duplicate known-dead URL behind: $(cat "$PROPSFILE")"
fi
rm -f "$PROPSFILE"

# Duplicates that are NOT the dead URL: the warning must name one value (the
# effective, last-wins one), not a smeared multi-line blob.
PROPSFILE="$(mktemp)"
: > "$MSGLOG"
printf 'wikantik.search.embedding.base-url = http://one.example:11434\nwikantik.search.embedding.base-url = http://two.example:11434\n' > "$PROPSFILE"
CURL_STUB_RC=7 CURL_STUB_BODY='' \
  repair_embedding_base_url "$PROPSFILE" "http://localhost:11434" "qwen3-embedding:0.6b"
if grep -q 'base-url is http://two.example:11434,' "$MSGLOG"; then
  pass "repair warns about the effective (last-wins) value when the key is duplicated"
else
  fail "repair emitted a multi-line/garbled warning for a duplicated key: $(cat "$MSGLOG")"
fi
rm -f "$PROPSFILE"
: > "$MSGLOG"


# =============================================================================
# Task 3: run-tests.sh manages one shared embedder for the whole IT phase.
# Static assertions per spec, plus a behavioural check (docker + curl shimmed
# on PATH, same style as bin/tests/test-container.sh) proving the readiness
# gate really reuses embeddings_model_ready() (bin/lib/embeddings.sh) instead
# of a third bare `curl .../api/tags` HTTP-200 probe — that probe is exactly
# the trap Tasks 1 and 2 both had to fix: it reports ready while the model is
# still downloading in the background.
# =============================================================================
RUNTESTS="${REPO_DIR}/bin/run-tests.sh"
checkf "run-tests starts the shared embedder"                   "$RUNTESTS" 'wikantik-embed-test'
checkf "run-tests uses the test port 11435"                     "$RUNTESTS" '11435'
checkf "run-tests tears the embedder down"                      "$RUNTESTS" 'embed_stop'
checkf "run-tests STOPS the embedder rather than removing it"   "$RUNTESTS" 'docker compose -f "\${EMBED_COMPOSE}" stop'
if grep -qE 'docker compose -f "\$\{EMBED_COMPOSE\}" down' "$RUNTESTS"; then
  fail "run-tests.sh 'down's the embedder — orphans the ollama-models volume (600 MB re-download after any docker volume prune)"
else
  pass "run-tests.sh never 'down's the embedder compose project"
fi
checkf "dense module is in the default gate"                    "$RUNTESTS" 'wikantik-it-test-dense'
checkf "run-tests sources the shared embeddings lib"             "$RUNTESTS" 'lib/embeddings.sh'
checkf "run-tests readiness gate calls embeddings_model_ready"   "$RUNTESTS" 'embeddings_model_ready "\${EMBED_URL}"'

if grep -qE 'curl[^\n]*--max-time[^\n]*"\$\{EMBED_URL\}/api/tags"[[:space:]]*>/dev/null' "$RUNTESTS"; then
  fail "run-tests.sh still has the bare curl \${EMBED_URL}/api/tags reachability probe (the Task 1/2 trap)"
else
  pass "run-tests.sh has no bare curl \${EMBED_URL}/api/tags reachability probe"
fi

# --- Behavioural: exercise the real embed_start/embed_stop code path with
# docker + curl + mvn ALL shimmed on PATH — no real containers, no network, no
# builds. Every external command run-tests.sh can reach must be shimmed: this is
# a shell-unit suite (it runs in the quality-gates `shell-tests` job), so an
# unshimmed binary here means a CI shell job silently spawning the thing it was
# supposed to be a cheap proxy for.
#
# `mvn` in particular: until wikantik-it-test-dense existed, `--module dense`
# died at run-tests.sh's `[ -d "$mod" ]` check and never reached Maven, so the
# shim was omitted. The moment Task 4 created the directory, this block started
# running a REAL Cargo/Tomcat IT reactor — and because the only assertion was
# "exit code is non-zero", it passed on that failure and tested nothing.
# WIKANTIK_TEST_SUITE_LOG_DIR (below) keeps run-tests.sh's own logs inside the
# shim dir so these runs cannot truncate a real run's .test-suite-logs/*.log.
RTSHIM="$(mktemp -d)"
RTCALLS="${RTSHIM}/docker.calls"
RTENV="${RTSHIM}/docker.env"
RTCURLCOUNT="${RTSHIM}/curl.count"
RTMVNARGS="${RTSHIM}/mvn.args"
RTLOGDIR="${RTSHIM}/test-suite-logs"
trap 'cleanup_lib_test; rm -rf "$RTSHIM"' EXIT

cat > "${RTSHIM}/docker" <<EOF
#!/usr/bin/env bash
echo "\$*" >> "${RTCALLS}"
echo "\${COMPOSE_PROJECT_NAME:-UNSET} \${WIKANTIK_EMBEDDING_PORT:-UNSET}" >> "${RTENV}"
exit 0
EOF
chmod +x "${RTSHIM}/docker"

# First call reports an empty model list (still downloading); only the second
# reports the tag present. A bare HTTP-200 check would declare ready after
# call 1 — the curl-call-count assertion below is what makes that regression
# visible.
cat > "${RTSHIM}/curl" <<EOF
#!/usr/bin/env bash
n=0
[ -f "${RTCURLCOUNT}" ] && n=\$(cat "${RTCURLCOUNT}")
n=\$((n+1))
echo "\$n" > "${RTCURLCOUNT}"
if [ "\$n" -lt 2 ]; then
  printf '%s' '{"models":[]}'
else
  printf '%s' '{"models":[{"name":"qwen3-embedding:0.6b"}]}'
fi
exit 0
EOF
chmod +x "${RTSHIM}/curl"

# Records the argv run-tests.sh dispatches, so the assertions below can check
# WHICH build was requested instead of only that something exited non-zero.
# Prints a surefire-shaped line because run_step greps the log for one.
cat > "${RTSHIM}/mvn" <<EOF
#!/usr/bin/env bash
echo "\$*" >> "${RTMVNARGS}"
echo "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0"
exit 0
EOF
chmod +x "${RTSHIM}/mvn"

# set -e is active for this whole file: guard with `|| rt_rc=$?` rather than a
# bare `cmd; rc=$?`, which set -e would abort on before the assignment ever runs.
rt_rc=0
PATH="${RTSHIM}:${PATH}" WIKANTIK_TEST_SUITE_LOG_DIR="${RTLOGDIR}" \
  "$RUNTESTS" --module dense >"${RTSHIM}/out.log" 2>&1 || rt_rc=$?

if [ -f "$RTCALLS" ] && grep -q " up -d" "$RTCALLS" 2>/dev/null; then
  pass "run-tests --module dense invokes docker compose up -d for the embedder"
else
  fail "run-tests --module dense never invoked docker compose up -d"
fi

if [ -f "$RTENV" ] && grep -q "^wikantik-embed-test 11435$" "$RTENV"; then
  pass "run-tests wires the test project name + port 11435 into the docker compose env"
else
  fail "run-tests did not pass COMPOSE_PROJECT_NAME=wikantik-embed-test / WIKANTIK_EMBEDDING_PORT=11435 to docker compose"
fi

if [ -f "$RTCURLCOUNT" ] && [ "$(cat "$RTCURLCOUNT")" -ge 2 ]; then
  pass "run-tests readiness loop retries past an empty model list instead of accepting a bare 200"
else
  fail "run-tests readiness loop accepted the first (empty-model-list) response — reverted to a bare HTTP-200 probe"
fi

# The embedder is STOPPED, never `down`ed. `docker compose down` removes the
# container, and removing the last container that references a named volume
# leaves that volume dangling — which is exactly what `docker volume prune`
# collects. The volume here is ollama-models, holding the ~600 MB
# qwen3-embedding tag, so a routine host cleanup between runs would silently
# cost a full re-download on the next IT phase (and the re-download looks like
# a hang at "waiting for qwen3-embedding:0.6b"). A *stopped* container still
# counts as using its volume, so prune skips it.
if [ -f "$RTCALLS" ] && grep -qE ' stop( |$)' "$RTCALLS" 2>/dev/null; then
  pass "run-tests stops the embedder (docker compose stop) on exit"
else
  fail "run-tests never invoked docker compose stop on exit"
fi

if [ -f "$RTCALLS" ] && grep -qE ' down( |$)' "$RTCALLS" 2>/dev/null; then
  fail "run-tests invoked 'docker compose down' on the embedder — that orphans the ollama-models volume for docker volume prune"
else
  pass "run-tests never 'down's the embedder (ollama-models stays referenced, so prune skips it)"
fi

# The dispatch itself, not just the exit code. `-pl <the dense module>` is the
# only thing that makes `--module dense` mean anything; asserting on it catches a
# typo'd/renamed module path, which "exited non-zero" never could.
if [ -f "$RTMVNARGS" ] && grep -q -- '-pl wikantik-it-tests/wikantik-it-test-dense' "$RTMVNARGS"; then
  pass "run-tests --module dense dispatches maven at -pl wikantik-it-tests/wikantik-it-test-dense"
else
  fail "run-tests --module dense did not dispatch maven at -pl wikantik-it-tests/wikantik-it-test-dense: $( [ -f "$RTMVNARGS" ] && cat "$RTMVNARGS" || echo '(mvn never invoked)' )"
fi

# The embedder port must reach Maven, not just docker: the dense module derives BOTH
# the deployed wiki's wikantik.search.embedding.base-url (resource filtering) and
# DenseBundleIT's readiness probe (failsafe system property) from -Dit.embed.port. If the
# shell starts a container on one port while Maven configures the wiki for the pom's
# fallback, the suite probes a live embedder and the wiki talks to nothing.
if [ -f "$RTMVNARGS" ] && grep -q -- '-Dit.embed.port=11435' "$RTMVNARGS"; then
  pass "run-tests passes the embedder port to maven as -Dit.embed.port"
else
  fail "run-tests did not pass -Dit.embed.port to maven — the wiki and the test can now disagree about the endpoint: $( [ -f "$RTMVNARGS" ] && cat "$RTMVNARGS" || echo '(mvn never invoked)' )"
fi

if [ -f "$RTMVNARGS" ] && grep -q -- '-Pintegration-tests' "$RTMVNARGS"; then
  pass "run-tests --module dense dispatches under -Pintegration-tests"
else
  fail "run-tests --module dense did not activate the integration-tests profile"
fi

if [ "$rt_rc" -eq 0 ]; then
  pass "run-tests --module dense completes normally under the shimmed mvn (dispatch logic alone is what's under test)"
else
  fail "run-tests --module dense failed under the shimmed mvn (rc=${rt_rc}) — see ${RTSHIM}/out.log"
fi

# The shim dir must have absorbed run-tests.sh's logs. If this fails, the suite is
# writing into the developer's real .test-suite-logs and destroying evidence — the
# exact damage this whole block used to do. Keyed on report.txt, which run-tests.sh
# truncates immediately after taking the lock, so this stays a true statement about
# WHERE logs went no matter how far the dispatch under test got.
if [ -f "${RTLOGDIR}/report.txt" ]; then
  pass "run-tests honours WIKANTIK_TEST_SUITE_LOG_DIR (real .test-suite-logs untouched)"
else
  fail "run-tests ignored WIKANTIK_TEST_SUITE_LOG_DIR — this suite is clobbering the real .test-suite-logs/"
fi

# --- Fix round 1 (code review): the embedder must NOT start for a
# single-module run that has nothing to do with embeddings — --fullloop
# (the Authentik SCIM full-loop, ONE_MODULE="scim-fullloop") is the exact
# case the review named. Reuses the recording mvn shim created above (it must
# not be replaced by a non-recording one, or the dense dispatch assertions
# above lose their evidence trail). Reusing $RTCALLS/$RTENV from the dense case
# deliberately proves the NEGATIVE: after this run, the docker shim must have
# logged nothing new — reset both to empty first so a stale hit from the dense
# run above can't leak in and produce a false pass.
: > "$RTCALLS"
: > "$RTENV"
rt_rc2=0
PATH="${RTSHIM}:${PATH}" WIKANTIK_TEST_SUITE_LOG_DIR="${RTLOGDIR}" \
  "$RUNTESTS" --fullloop >"${RTSHIM}/out-fullloop.log" 2>&1 || rt_rc2=$?

# The assertion is on the embedder specifically, not on docker in general: every
# run now also issues the leaked-resource sweep (bin/docker-cleanup.sh), which
# legitimately talks to docker for ANY invocation — and --fullloop is the module
# that most needs sweeping, since it starts five containers. So this proves the
# negative the review actually asked for: no compose `up -d` for the embedder.
if ! grep -q -- ' up -d' "$RTCALLS" 2>/dev/null; then
  pass "run-tests --fullloop (scim-fullloop) does NOT start the embedder — unrelated to embeddings"
else
  fail "run-tests --fullloop started the embedder for a module that has nothing to do with embeddings: $(cat "$RTCALLS")"
fi

if [ "$rt_rc2" -eq 0 ]; then
  pass "run-tests --fullloop completes normally under the shimmed mvn (dispatch logic alone is what's under test)"
else
  fail "run-tests --fullloop unexpectedly failed under the shimmed mvn (rc=${rt_rc2}) — see ${RTSHIM}/out-fullloop.log"
fi

# =============================================================================
# Fix round 2: stale-embedder pre-clean + explicit SIGINT/SIGTERM handling.
#
# Gap 1 — no pre-start sweep. Every IT module's own pom runs a
# pg-cleanup-stale sweep at `initialize` for its pgvector container; nothing
# equivalent exists for the shared embedder, so a container left behind by a
# previously killed run sits on port 11435 forever. run-tests.sh must issue a
# `docker compose … down` sweep BEFORE `embed_start`'s `up -d`, and that
# sweep must never fail the script (idempotent: nothing there is a pass, not
# an error) and must never remove the models volume (-v) — that volume caches
# the ~600 MB qwen3-embedding model; wiping it forces a multi-minute
# re-download on the very next run.
#
# Gap 2 — only EXIT is trapped. `set -uo pipefail` (no -e) installs no signal
# handling on its own. Reuses the $RTSHIM/docker/curl shims + WIKANTIK_TEST_SUITE_LOG_DIR
# redirection already proven above (mvn is swapped per-block below since these
# cases need a SLOW mvn so a signal can land mid-build).
# =============================================================================

# --- Static: the source names the pieces this fix adds, so a later edit that
# silently drops one of them fails loudly here even before the behavioural
# checks below run.
checkf "run-tests pre-cleans a stale embedder before starting" "$RUNTESTS" 'embed_preclean'
checkf "run-tests traps SIGINT explicitly"                     "$RUNTESTS" "trap 'handle_embed_signal INT'  INT"
checkf "run-tests traps SIGTERM explicitly"                    "$RUNTESTS" "trap 'handle_embed_signal TERM' TERM"

if grep -qE "docker compose[^\n]*down[^\n]*(-v\b|--volumes\b)" "$RUNTESTS"; then
  fail "run-tests.sh embedder teardown/pre-clean passes -v/--volumes — this would delete the cached qwen3-embedding model volume"
else
  pass "run-tests.sh embedder teardown/pre-clean never passes -v/--volumes (models cache preserved)"
fi

# --- Behavioural: pre-clean runs BEFORE embed_start. Uses its OWN fresh
# invocation + call log rather than reusing $RTCALLS from the earlier
# --module dense block above: that file gets truncated (`: > "$RTCALLS"`) by
# the --fullloop block further up, which deliberately asserts docker is never
# invoked for it — reusing the same file here would read empty and fail for a
# reason that has nothing to do with pre-clean ordering.
ORDERSHIM="${RTSHIM}/order"
mkdir -p "$ORDERSHIM"
ORDERCALLS="${ORDERSHIM}/docker.calls"
cat > "${ORDERSHIM}/docker" <<EOF
#!/usr/bin/env bash
echo "\$*" >> "${ORDERCALLS}"
exit 0
EOF
chmod +x "${ORDERSHIM}/docker"
cat > "${ORDERSHIM}/curl" <<'EOF'
#!/usr/bin/env bash
printf '%s' '{"models":[{"name":"qwen3-embedding:0.6b"}]}'
exit 0
EOF
chmod +x "${ORDERSHIM}/curl"
cat > "${ORDERSHIM}/mvn" <<'EOF'
#!/usr/bin/env bash
echo "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0"
exit 0
EOF
chmod +x "${ORDERSHIM}/mvn"

order_rc=0
PATH="${ORDERSHIM}:${PATH}" WIKANTIK_TEST_SUITE_LOG_DIR="${RTLOGDIR}" \
  "$RUNTESTS" --module dense >"${ORDERSHIM}/out.log" 2>&1 || order_rc=$?

if [ "$order_rc" -eq 0 ] && [ -f "$ORDERCALLS" ]; then
  down_line="$(grep -n -- ' down$' "$ORDERCALLS" | head -1 | cut -d: -f1 || true)"
  up_line="$(grep -n -- ' up -d$' "$ORDERCALLS" | head -1 | cut -d: -f1 || true)"
  if [ -n "${down_line:-}" ] && [ -n "${up_line:-}" ] && [ "$down_line" -lt "$up_line" ]; then
    pass "run-tests sweeps a stale embedder (docker compose down) BEFORE starting a fresh one (up -d)"
  else
    fail "run-tests did not sweep stale state before starting the embedder (down_line=${down_line:-none} up_line=${up_line:-none}): $(cat "$ORDERCALLS")"
  fi
else
  fail "run-tests --module dense (order check) failed (rc=${order_rc}) or never invoked docker — see ${ORDERSHIM}/out.log"
fi

# --- Behavioural: the pre-clean sweep must be tolerant of "nothing to
# remove" — simulate that (and any other transient docker failure) by making
# every `docker … down` call in this isolated shim dir fail, and prove the
# script still proceeds to dispatch mvn instead of aborting.
PCSHIM="${RTSHIM}/preclean-fail"
mkdir -p "$PCSHIM"
cat > "${PCSHIM}/docker" <<'EOF'
#!/usr/bin/env bash
case "$*" in
  *" down") exit 1 ;;
  *) exit 0 ;;
esac
EOF
chmod +x "${PCSHIM}/docker"
cat > "${PCSHIM}/curl" <<'EOF'
#!/usr/bin/env bash
printf '%s' '{"models":[{"name":"qwen3-embedding:0.6b"}]}'
exit 0
EOF
chmod +x "${PCSHIM}/curl"
PCMVNARGS="${PCSHIM}/mvn.args"
cat > "${PCSHIM}/mvn" <<EOF
#!/usr/bin/env bash
echo "\$*" >> "${PCMVNARGS}"
echo "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0"
exit 0
EOF
chmod +x "${PCSHIM}/mvn"

pc_rc=0
PATH="${PCSHIM}:${PATH}" WIKANTIK_TEST_SUITE_LOG_DIR="${RTLOGDIR}" \
  "$RUNTESTS" --module dense >"${PCSHIM}/out.log" 2>&1 || pc_rc=$?

if [ "$pc_rc" -eq 0 ] && [ -f "$PCMVNARGS" ]; then
  pass "run-tests continues (and still dispatches mvn) when the pre-clean sweep finds nothing to remove / fails harmlessly"
else
  fail "run-tests aborted when the pre-clean docker call failed (rc=${pc_rc}) — see ${PCSHIM}/out.log"
fi

# --- Behavioural: SIGINT and SIGTERM sent to a BACKGROUNDED run-tests.sh must
# (a) actually run embedder teardown and (b) exit with the conventional
# 128+signo status, not be swallowed. Isolated shim dir + a SLOW mvn (sleeps,
# recording its own PID first) so the signal can land while run-tests.sh is
# genuinely blocked inside the mvn step — mirroring where a real IT run would
# be cancelled. The signal goes to BOTH run-tests.sh's PID and the mvn
# shim's PID, exactly as a real terminal Ctrl-C (or `kill` of a whole process
# group) would deliver it to every process in the foreground group at once;
# bash defers a *trapped* signal until the current foreground child returns,
# so signalling only the parent would otherwise force this test to wait out
# the mvn shim's full sleep before the trap could run.
#
# `set -m` (job control) below is load-bearing, not decoration. Per the bash
# manual, "signals ignored upon entry to the shell cannot be trapped or
# reset" — and when job control is OFF (the default for a non-interactive
# script, which this test driver is), bash sets SIGINT/SIGQUIT to IGNORED for
# every `&`-backgrounded child BEFORE it execs, which run-tests.sh's own
# `trap … INT` can then never override, no matter how correct it is: the
# signal is silently swallowed and the run finishes normally. That is a
# property of how *this test* launches the child, not of run-tests.sh's own
# signal handling — a real interactive terminal's Ctrl-C, or `kill` from
# another shell, is delivered to a plain foreground job with no such
# inherited ignore, so the trap fires exactly as intended there. `set -m`
# makes this driver's own background job behave like that real foreground
# case (job control shells do not force the async ignore), which is what
# makes SIGINT genuinely trappable here at all — without it this whole test
# provably cannot pass (verified: reproduces exit 0 / signal swallowed even
# though run-tests.sh's trap is correctly installed).
set -m
SIGSHIM="${RTSHIM}/sig"
mkdir -p "$SIGSHIM"
cat > "${SIGSHIM}/curl" <<'EOF'
#!/usr/bin/env bash
printf '%s' '{"models":[{"name":"qwen3-embedding:0.6b"}]}'
exit 0
EOF
chmod +x "${SIGSHIM}/curl"

run_signal_case() {
  local sig="$1" expected_rc="$2"
  local sigcalls="${SIGSHIM}/docker.calls.${sig}"
  local mvnpid="${SIGSHIM}/mvn.pid.${sig}"
  : > "$sigcalls"
  rm -f "$mvnpid"

  cat > "${SIGSHIM}/docker" <<EOF
#!/usr/bin/env bash
echo "\$*" >> "${sigcalls}"
exit 0
EOF
  chmod +x "${SIGSHIM}/docker"

  cat > "${SIGSHIM}/mvn" <<EOF
#!/usr/bin/env bash
echo "\$\$" > "${mvnpid}"
sleep 5
echo "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0"
exit 0
EOF
  chmod +x "${SIGSHIM}/mvn"

  PATH="${SIGSHIM}:${PATH}" WIKANTIK_TEST_SUITE_LOG_DIR="${RTLOGDIR}" \
    "$RUNTESTS" --module dense >"${SIGSHIM}/out-${sig}.log" 2>&1 &
  local rt_pid=$!

  local tries=0
  while [ ! -s "$mvnpid" ] && [ "$tries" -lt 100 ]; do
    sleep 0.1
    tries=$((tries+1))
  done
  if [ ! -s "$mvnpid" ]; then
    fail "run-tests --module dense (SIG${sig}) never reached the mvn step within 10s — see ${SIGSHIM}/out-${sig}.log"
    kill -9 "$rt_pid" 2>/dev/null || true
    wait "$rt_pid" 2>/dev/null || true
    return
  fi
  local mvn_pid; mvn_pid="$(cat "$mvnpid")"

  kill -s "$sig" "$rt_pid" "$mvn_pid" 2>/dev/null || true
  local got_rc=0
  wait "$rt_pid" || got_rc=$?

  if [ "$got_rc" -eq "$expected_rc" ]; then
    pass "run-tests --module dense exits ${expected_rc} on SIG${sig} (conventional 128+signo, not swallowed)"
  else
    fail "run-tests --module dense exited ${got_rc} on SIG${sig}, expected ${expected_rc} — see ${SIGSHIM}/out-${sig}.log"
  fi

  if grep -q -- ' down$' "$sigcalls" 2>/dev/null; then
    pass "SIG${sig} triggers embedder teardown (docker compose down) before exit"
  else
    fail "SIG${sig} did NOT trigger embedder teardown: $(cat "$sigcalls" 2>/dev/null || echo '(no docker calls recorded)')"
  fi

  if grep -Eq -- '(^| )(-v|--volumes)( |$)' "$sigcalls" 2>/dev/null; then
    fail "SIG${sig} teardown removed the models volume (-v/--volumes present) — forces a re-download next run"
  else
    pass "SIG${sig} teardown never passes -v/--volumes (models cache preserved)"
  fi
}

run_signal_case INT 130
run_signal_case TERM 143

# =============================================================================
# clean_zombies must not kill builds belonging to OTHER checkouts.
#
# The original sweep was `pkill -9 -f "surefire.*booter|plexus.classworlds|
# org.codehaus.cargo"`. `plexus.classworlds` is Maven's launcher class, so that
# marker is on the command line of EVERY mvn on the machine — another repo's
# build, another checkout of this one, or a concurrent build in this tree all
# matched, and run_step calls clean_zombies before every phase and every
# module. Observed 2026-08-19: it SIGKILLed an unrelated `mvn clean install`
# mid-`cyclonedx:makeAggregateBom` (exit 137).
#
# The marker must still match, but a process is only killed when its own
# command line ALSO names this checkout. Both fixtures below carry a real
# marker; only one is inside $REPO_DIR.
#
# The stub PIDs are deliberately above kernel.pid_max, so even if the filter
# regressed and the kill fired, it could not signal a real process.
# =============================================================================
CZSHIM="${RTSHIM}/zombies"
mkdir -p "$CZSHIM"
CZ_MINE=2147480001      # a maven belonging to THIS checkout — must be killed
CZ_THEIRS=2147480002    # a maven belonging to another checkout — must survive

cat > "${CZSHIM}/pgrep" <<EOF
#!/usr/bin/env bash
printf '%s\n%s\n' "${CZ_MINE}" "${CZ_THEIRS}"
EOF
chmod +x "${CZSHIM}/pgrep"

# `ps -o args= -p <pid>` is the portable command-line read (macOS has no /proc).
cat > "${CZSHIM}/ps" <<EOF
#!/usr/bin/env bash
case " \$* " in
  *" ${CZ_MINE} "*)   echo "/usr/bin/java -Dmaven.multiModuleProjectDirectory=${REPO_DIR} -classpath /opt/maven/boot/plexus-classworlds.jar org.codehaus.plexus.classworlds.launcher.Launcher install" ;;
  *" ${CZ_THEIRS} "*) echo "/usr/bin/java -Dmaven.multiModuleProjectDirectory=/home/someone/other-repo -classpath /opt/maven/boot/plexus-classworlds.jar org.codehaus.plexus.classworlds.launcher.Launcher install" ;;
  *) exit 1 ;;
esac
EOF
chmod +x "${CZSHIM}/ps"

cat > "${CZSHIM}/docker" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod +x "${CZSHIM}/docker"
cat > "${CZSHIM}/curl" <<'EOF'
#!/usr/bin/env bash
printf '%s' '{"models":[{"name":"qwen3-embedding:0.6b"}]}'
exit 0
EOF
chmod +x "${CZSHIM}/curl"
cat > "${CZSHIM}/mvn" <<'EOF'
#!/usr/bin/env bash
echo "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0"
exit 0
EOF
chmod +x "${CZSHIM}/mvn"

# A bare `pkill` anywhere in the sweep is the defect itself: it kills by
# pattern with no per-process check, so it can never be checkout-scoped.
if grep -qE '^[^#]*\bpkill\b' "$RUNTESTS"; then
  fail "run-tests.sh still sweeps with pkill — that matches every mvn on the machine, not just this checkout's"
else
  pass "run-tests.sh does not sweep stray JVMs with an unscoped pkill"
fi

# Its OWN log dir, not $RTLOGDIR: .run.lock lives in the log dir and is held via
# an inherited fd, so the slow `mvn` stub backgrounded by the SIGINT/SIGTERM
# cases above can still be holding that lock when this block starts — sharing
# the dir makes this case fail with the lock's exit 3 instead of testing
# anything.
cz_rc=0
PATH="${CZSHIM}:${PATH}" WIKANTIK_TEST_SUITE_LOG_DIR="${CZSHIM}/logs" \
  "$RUNTESTS" --module dense >"${CZSHIM}/out.log" 2>&1 || cz_rc=$?
if [ "$cz_rc" -ne 0 ]; then
  fail "run-tests --module dense exited ${cz_rc} under the zombie-sweep shims — see ${CZSHIM}/out.log"
fi
if grep -q "${CZ_MINE}" "${CZSHIM}/out.log"; then
  pass "clean_zombies reports killing a stray JVM that belongs to this checkout"
else
  fail "clean_zombies did not kill this checkout's own stray JVM (${CZ_MINE}) — see ${CZSHIM}/out.log"
fi
if grep -q "${CZ_THEIRS}" "${CZSHIM}/out.log"; then
  fail "clean_zombies killed a JVM belonging to ANOTHER checkout (${CZ_THEIRS}) — see ${CZSHIM}/out.log"
else
  pass "clean_zombies leaves another checkout's build alone"
fi

# =============================================================================
# Every run sweeps leaked Docker resources.
#
# The per-module pom sweeps only ever clean the module that is about to run, so
# a container or anonymous volume orphaned by a module that is NOT in this
# invocation sits there indefinitely. run-tests.sh therefore calls
# bin/docker-cleanup.sh --apply once at startup, so the routine act of running
# the suite keeps the machine clean.
#
# Ordering is load-bearing: the sweep tears down the wikantik-embed-test compose
# project, so it MUST happen before embed_start, or it would kill the embedder
# this very run just started.
#
# Non-fatal is also load-bearing: a cleanup problem must never turn a green test
# run red.
# =============================================================================
checkf "run-tests invokes the docker-cleanup sweep"      "$RUNTESTS" 'docker-cleanup.sh'
checkf "run-tests runs the sweep in apply mode"          "$RUNTESTS" '\-\-apply'

# The sweep must be dispatched before the unit phase, so that EVERY invocation
# reclaims leaked resources first — including --unit, and including a run whose
# Phase 1 fails (which is exactly when the machine is most likely to be dirty).
# A source-order check, because the behavioural cases below drive --module dense,
# which has no unit phase to order against.
sweep_call_ln="$(grep -n '^sweep_leaked_docker$' "$RUNTESTS" | head -1 | cut -d: -f1 || true)"
unit_phase_ln="$(grep -n 'Phase 1: unit reactor' "$RUNTESTS" | head -1 | cut -d: -f1 || true)"
if [ -n "${sweep_call_ln:-}" ] && [ -n "${unit_phase_ln:-}" ] && [ "$sweep_call_ln" -lt "$unit_phase_ln" ]; then
  pass "the sweep is dispatched before the unit phase, so every run reclaims first"
else
  fail "sweep is not dispatched before the unit phase (sweep=${sweep_call_ln:-none} unit=${unit_phase_ln:-none})"
fi

SWSHIM="${RTSHIM}/sweep"
mkdir -p "$SWSHIM"
# docker fails for EVERY call: proves the sweep cannot fail the run, and that a
# machine with a broken/absent daemon still tests normally.
cat > "${SWSHIM}/docker" <<'EOF'
#!/usr/bin/env bash
exit 1
EOF
chmod +x "${SWSHIM}/docker"
cat > "${SWSHIM}/curl" <<'EOF'
#!/usr/bin/env bash
printf '%s' '{"models":[{"name":"qwen3-embedding:0.6b"}]}'
exit 0
EOF
chmod +x "${SWSHIM}/curl"
SWMVNARGS="${SWSHIM}/mvn.args"
cat > "${SWSHIM}/mvn" <<EOF
#!/usr/bin/env bash
echo "\$*" >> "${SWMVNARGS}"
echo "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0"
exit 0
EOF
chmod +x "${SWSHIM}/mvn"

sw_rc=0
PATH="${SWSHIM}:${PATH}" WIKANTIK_TEST_SUITE_LOG_DIR="${SWSHIM}/logs" \
  "$RUNTESTS" --module dense >"${SWSHIM}/out.log" 2>&1 || sw_rc=$?

if [ "$sw_rc" -eq 0 ] && [ -f "$SWMVNARGS" ]; then
  pass "a failing docker-cleanup sweep never fails the test run"
else
  fail "run-tests exited ${sw_rc} when the sweep could not talk to docker — see ${SWSHIM}/out.log"
fi

sweep_line="$(grep -n 'Sweeping leaked Docker resources' "${SWSHIM}/out.log" | head -1 | cut -d: -f1 || true)"
embed_line="$(grep -n 'Starting shared embedder' "${SWSHIM}/out.log" | head -1 | cut -d: -f1 || true)"
if [ -n "${sweep_line:-}" ]; then
  pass "run-tests announces the leaked-resource sweep"
else
  fail "run-tests never ran the leaked-resource sweep — see ${SWSHIM}/out.log"
fi
if [ -n "${sweep_line:-}" ] && [ -n "${embed_line:-}" ] && [ "$sweep_line" -lt "$embed_line" ]; then
  pass "the sweep runs BEFORE the embedder starts (it would otherwise tear down this run's own embedder)"
else
  fail "sweep/embedder ordering wrong (sweep=${sweep_line:-none} embed=${embed_line:-none}) — see ${SWSHIM}/out.log"
fi

if [ "$fails" -ne 0 ]; then echo "test-embeddings: ${fails} failure(s)"; exit 1; fi
echo "test-embeddings: all passed"
