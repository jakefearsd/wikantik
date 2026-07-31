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
checkf "dense module is in the default gate"                    "$RUNTESTS" 'wikantik-it-test-dense'
checkf "run-tests sources the shared embeddings lib"             "$RUNTESTS" 'lib/embeddings.sh'
checkf "run-tests readiness gate calls embeddings_model_ready"   "$RUNTESTS" 'embeddings_model_ready "\${EMBED_URL}"'

if grep -qE 'curl[^\n]*--max-time[^\n]*"\$\{EMBED_URL\}/api/tags"[[:space:]]*>/dev/null' "$RUNTESTS"; then
  fail "run-tests.sh still has the bare curl \${EMBED_URL}/api/tags reachability probe (the Task 1/2 trap)"
else
  pass "run-tests.sh has no bare curl \${EMBED_URL}/api/tags reachability probe"
fi

# --- Behavioural: exercise the real embed_start/embed_stop code path with
# docker + curl shimmed on PATH — no real containers, no network. `--module
# dense` hits embed_start/trap before failing on the not-yet-created Task 4
# module directory (that module ships in Task 4), so this run is expected to
# exit non-zero; only the embedder wiring is under test here.
RTSHIM="$(mktemp -d)"
RTCALLS="${RTSHIM}/docker.calls"
RTENV="${RTSHIM}/docker.env"
RTCURLCOUNT="${RTSHIM}/curl.count"
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

# set -e is active for this whole file: guard with `|| rt_rc=$?` rather than a
# bare `cmd; rc=$?`, which set -e would abort on before the assignment ever runs.
rt_rc=0
PATH="${RTSHIM}:${PATH}" "$RUNTESTS" --module dense >"${RTSHIM}/out.log" 2>&1 || rt_rc=$?

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

if [ -f "$RTCALLS" ] && grep -q " down" "$RTCALLS" 2>/dev/null; then
  pass "run-tests tears the embedder down (docker compose down) on exit"
else
  fail "run-tests never invoked docker compose down on exit"
fi

if [ "$rt_rc" -ne 0 ]; then
  pass "run-tests --module dense exits non-zero (Task 4's module does not exist yet)"
else
  fail "run-tests --module dense unexpectedly succeeded — did wikantik-it-test-dense get created without updating this test?"
fi

# --- Fix round 1 (code review): the embedder must NOT start for a
# single-module run that has nothing to do with embeddings — --fullloop
# (the Authentik SCIM full-loop, ONE_MODULE="scim-fullloop") is the exact
# case the review named. wikantik-it-test-scim-fullloop is a real module
# directory (unlike the not-yet-created dense one), so without shimming mvn
# too this would launch a real Maven/Testcontainers IT build; the mvn shim
# fakes instant success so run-tests.sh's own dispatch logic is what's under
# test, not the build it would otherwise kick off. Reusing $RTCALLS/$RTENV
# from the dense case above deliberately proves the NEGATIVE: after this run,
# the docker shim must have logged nothing new — reset both to empty first so
# a stale hit from the dense run above can't leak in and produce a false pass.
cat > "${RTSHIM}/mvn" <<'EOF'
#!/usr/bin/env bash
echo "Tests run: 1, Failures: 0, Errors: 0"
exit 0
EOF
chmod +x "${RTSHIM}/mvn"

: > "$RTCALLS"
: > "$RTENV"
rt_rc2=0
PATH="${RTSHIM}:${PATH}" "$RUNTESTS" --fullloop >"${RTSHIM}/out-fullloop.log" 2>&1 || rt_rc2=$?

if [ ! -s "$RTCALLS" ]; then
  pass "run-tests --fullloop (scim-fullloop) does NOT start the embedder — unrelated to embeddings"
else
  fail "run-tests --fullloop invoked docker (embedder started for a module that has nothing to do with embeddings): $(cat "$RTCALLS")"
fi

if [ "$rt_rc2" -eq 0 ]; then
  pass "run-tests --fullloop completes normally under the shimmed mvn (dispatch logic alone is what's under test)"
else
  fail "run-tests --fullloop unexpectedly failed under the shimmed mvn (rc=${rt_rc2}) — see ${RTSHIM}/out-fullloop.log"
fi

if [ "$fails" -ne 0 ]; then echo "test-embeddings: ${fails} failure(s)"; exit 1; fi
echo "test-embeddings: all passed"
