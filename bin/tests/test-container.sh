#!/usr/bin/env bash
# Tests for bin/container.sh — the test env / smoke-test compose invocation.
#
# docker-compose.test.yml is an OVERLAY (deltas only: alt DB port, dev-user
# seed). It is not runnable standalone: it must ride on the base compose,
# be namespaced with -p wikantik-test so `down -v` can't touch dev/prod
# volumes, and set WIKANTIK_HOST_PORT=18080 so the stack stays clear of a
# bare-metal Tomcat on 8080. These tests pin all three requirements onto
# `container.sh smoke-test` and `container.sh -e test`.
#
# Style: PATH-shimmed `docker`/`curl` (like test-backup.sh) — no containers
# are started. The two compose `config` checks use the real docker CLI but
# never bring anything up.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

fail() { echo "FAIL: $*" >&2; exit 1; }
ok()   { echo "ok: $*"; }

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

# --- shim: record every docker/curl call (args + WIKANTIK_HOST_PORT), succeed ---
mkdir -p "${TMP}/shim"
cat > "${TMP}/shim/docker" <<EOF
#!/usr/bin/env bash
echo "\$*" >> "${TMP}/docker.calls"
echo "\${WIKANTIK_HOST_PORT:-UNSET}" >> "${TMP}/docker.env"
exit 0
EOF
cat > "${TMP}/shim/curl" <<EOF
#!/usr/bin/env bash
echo "\$*" >> "${TMP}/curl.calls"
exit 0
EOF
chmod +x "${TMP}/shim/docker" "${TMP}/shim/curl"

# run_shimmed [ENV_VAR=VAL ...] -- ARGS...  → runs bin/container.sh with the
# shims first on PATH, recording into fresh call logs.
run_shimmed() {
    rm -f "${TMP}/docker.calls" "${TMP}/docker.env" "${TMP}/curl.calls"
    local envs=()
    while [[ "$1" != "--" ]]; do envs+=("$1"); shift; done
    shift
    env "${envs[@]+"${envs[@]}"}" PATH="${TMP}/shim:${PATH}" bin/container.sh "$@"
}

# --- smoke-test must run base + overlay, namespaced, on port 18080 ---
test_smoke_test_invocation() {
    run_shimmed -- smoke-test >/dev/null || fail "smoke-test returned non-zero under shims"

    local up_call
    up_call="$(grep -E '(^| )up( |$)' "${TMP}/docker.calls")" \
        || fail "smoke-test never invoked docker compose up"
    echo "${up_call}" | grep -q -- "-f docker-compose.yml" \
        || fail "smoke-test up is missing the BASE compose file (overlay is not standalone)"
    echo "${up_call}" | grep -q -- "-f docker-compose.test.yml" \
        || fail "smoke-test up is missing the test overlay"
    echo "${up_call}" | grep -q -- "-p wikantik-test" \
        || fail "smoke-test up is missing -p wikantik-test (down -v could hit dev/prod volumes)"

    grep -q "^18080$" "${TMP}/docker.env" \
        || fail "smoke-test did not export WIKANTIK_HOST_PORT=18080 (collides with bare-metal Tomcat on 8080)"
    grep -q "localhost:18080/api/health" "${TMP}/curl.calls" \
        || fail "smoke-test did not poll health on port 18080"

    local down_call
    down_call="$(grep -E '(^| )down( |$)' "${TMP}/docker.calls")" \
        || fail "smoke-test never tore the stack down"
    echo "${down_call}" | grep -q -- "-f docker-compose.yml" \
        || fail "smoke-test down is missing the base compose file"
    echo "${down_call}" | grep -q -- "-p wikantik-test" \
        || fail "smoke-test down is missing -p wikantik-test"
    ok "smoke-test runs base + overlay under -p wikantik-test on 18080"
}
test_smoke_test_invocation

# --- an operator-chosen WIKANTIK_HOST_PORT must win over the 18080 default ---
test_smoke_test_respects_port_override() {
    run_shimmed WIKANTIK_HOST_PORT=12345 -- smoke-test >/dev/null \
        || fail "smoke-test returned non-zero under shims (port override)"
    grep -q "localhost:12345/api/health" "${TMP}/curl.calls" \
        || fail "smoke-test ignored an explicit WIKANTIK_HOST_PORT override"
    ok "smoke-test respects an explicit WIKANTIK_HOST_PORT"
}
test_smoke_test_respects_port_override

# --- -e test must select base + overlay + project, same as smoke-test ---
test_env_test_invocation() {
    run_shimmed -- -e test ps >/dev/null || fail "-e test ps returned non-zero under shims"
    local call
    call="$(cat "${TMP}/docker.calls")"
    echo "${call}" | grep -q -- "-f docker-compose.yml" \
        || fail "-e test is missing the base compose file"
    echo "${call}" | grep -q -- "-f docker-compose.test.yml" \
        || fail "-e test is missing the test overlay"
    echo "${call}" | grep -q -- "-p wikantik-test" \
        || fail "-e test is missing -p wikantik-test"
    ok "-e test selects base + overlay under -p wikantik-test"
}
test_env_test_invocation

# --- real compose: the combined test stack must resolve to the alt ports ---
test_combined_config_resolves_alt_ports() {
    local out
    out="$(WIKANTIK_HOST_PORT=18080 docker compose -p wikantik-test \
           -f docker-compose.yml -f docker-compose.test.yml config 2>/dev/null)" \
        || fail "combined base + test overlay was rejected by docker compose config"
    echo "${out}" | grep -q 'published: "18080"' \
        || fail "combined config does not publish wikantik on 18080"
    echo "${out}" | grep -q 'published: "15432"' \
        || fail "combined config does not publish db on 15432"
    echo "${out}" | grep -q 'published: "8080"' \
        && fail "combined config still publishes something on 8080 (bare-metal Tomcat collision)"
    ok "combined test stack publishes 18080/15432 only"
}
test_combined_config_resolves_alt_ports

# --- real compose: the overlay alone must stay invalid (guards the premise) ---
test_overlay_is_not_standalone() {
    docker compose -f docker-compose.test.yml config >/dev/null 2>&1 \
        && fail "docker-compose.test.yml validates standalone — if that is now intended, update container.sh and this suite together"
    ok "test overlay is (still) not standalone"
}
test_overlay_is_not_standalone

echo "ALL PASSED"
