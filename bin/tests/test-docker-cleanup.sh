#!/usr/bin/env bash
# Tests for bin/docker-cleanup.sh — the dry-run-by-default sweeper for Docker
# resources THIS repo's test suite leaks: stale per-module IT
# postgres/keycloak/authentik containers (when a module's build dies before
# Maven's post-integration-test `pg-stop` teardown runs), a leftover shared
# IT embedder compose stack, and orphaned anonymous IT-database volumes.
#
# Style: PATH-shimmed `docker` (same discipline as test-container.sh /
# test-backup.sh) — no real container, compose stack, or volume is ever
# started or removed. The stub answers docker with fixture files the test
# controls; every assertion is against the recorded argv the script issued.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}" || exit 1

SCRIPT="${REPO_ROOT}/bin/docker-cleanup.sh"

FAILURES=0
fail() { echo "FAIL: $*" >&2; FAILURES=$((FAILURES + 1)); }
ok()   { echo "ok: $*"; }

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

# The real pgvector major version, read the same way the script itself must
# read it — keeps the fixtures correct if the pinned image tag ever changes.
PGVECTOR_MAJOR="$(grep -oE '<pgvector\.image>[^<]+</pgvector\.image>' pom.xml \
    | sed -E 's#.*:pg([0-9]+)</pgvector\.image>#\1#')"
[[ -n "${PGVECTOR_MAJOR}" ]] || fail "test setup: could not read pgvector major version from pom.xml"
MISMATCH_MAJOR=$(( PGVECTOR_MAJOR + 1 ))

# A real IT module artifactId (must exist on disk) and the container names
# its pom actually declares, so the allowlist assertions are grounded in the
# real repo rather than an invented name.
REAL_MODULE="wikantik-it-test-rest"
[[ -d "wikantik-it-tests/${REAL_MODULE}" ]] || fail "test setup: wikantik-it-tests/${REAL_MODULE} missing"
ALLOWED_PG="wikantik-pg-${REAL_MODULE}"

SSO_MODULE="wikantik-it-test-sso"
ALLOWED_KC="wikantik-kc-${SSO_MODULE}"

SCIM_MODULE="wikantik-it-test-scim-fullloop"
ALLOWED_AUTHENTIK="wikantik-authentik-redis-${SCIM_MODULE}"

# ---------------------------------------------------------------------------
# Stub docker: records every invocation, answers read commands from fixture
# files the test populates, never actually touches anything.
# ---------------------------------------------------------------------------
STUB="${TMP}/stub"
mkdir -p "${STUB}"
CALLS="${TMP}/docker.calls"
CONTAINERS_FILE="${TMP}/containers.out"   # `docker ps -a --format {{.Names}}`
VOLUMES_FILE="${TMP}/volumes.out"         # `docker volume ls -f dangling=true -f label=...`
EMBED_PS_FILE="${TMP}/embed_ps.out"       # `docker compose -p wikantik-embed-test ... ps ...`
PGVER_DIR="${TMP}/pgver"                  # PGVER_DIR/<vol>.pgver + .size

reset_fixtures() {
    : > "${CALLS}"
    : > "${CONTAINERS_FILE}"
    : > "${VOLUMES_FILE}"
    : > "${EMBED_PS_FILE}"
    rm -rf "${PGVER_DIR}"
    mkdir -p "${PGVER_DIR}"
    rm -f "${TMP}/docker.info.rc"
}
reset_fixtures

cat > "${STUB}/docker" <<STUBEOF
#!/usr/bin/env bash
echo "\$*" >> "${CALLS}"
case "\$1" in
  info)
    exit "\$(cat "${TMP}/docker.info.rc" 2>/dev/null || echo 0)"
    ;;
  ps)
    cat "${CONTAINERS_FILE}" 2>/dev/null
    exit 0
    ;;
  volume)
    case "\$2" in
      ls) cat "${VOLUMES_FILE}" 2>/dev/null ;;
      rm) : ;;
    esac
    exit 0
    ;;
  compose)
    if [[ "\$*" == *" down"* ]]; then
      exit 0
    fi
    if [[ "\$*" == *" ps"* ]]; then
      cat "${EMBED_PS_FILE}" 2>/dev/null
    fi
    exit 0
    ;;
  run)
    vol=""
    for a in "\$@"; do
      case "\$a" in
        *:/vol:ro) vol="\${a%%:*}" ;;
      esac
    done
    if [[ "\$*" == *"PG_VERSION"* ]]; then
      cat "${PGVER_DIR}/\${vol}.pgver" 2>/dev/null
    elif [[ "\$*" == *"du -sb"* ]]; then
      cat "${PGVER_DIR}/\${vol}.size" 2>/dev/null
    fi
    exit 0
    ;;
  rm) exit 0 ;;
  image) exit 0 ;;
  *) exit 0 ;;
esac
STUBEOF
chmod +x "${STUB}/docker"

run_cleanup() {
    PATH="${STUB}:${PATH}" "${SCRIPT}" "$@"
}

add_anon_volume() { # add_anon_volume NAME PG_VERSION_CONTENT [SIZE_BYTES]
    echo "$1" >> "${VOLUMES_FILE}"
    [[ -n "${2:-}" ]] && printf '%s' "$2" > "${PGVER_DIR}/$1.pgver"
    printf '%s' "${3:-1048576}" > "${PGVER_DIR}/$1.size"
}

# ===========================================================================
# 1. Dry-run is the default: no destructive docker command, exit 0.
# ===========================================================================
test_dry_run_is_default_and_nondestructive() {
    reset_fixtures
    echo "${ALLOWED_PG}" > "${CONTAINERS_FILE}"
    add_anon_volume "deadbeef1111" "${PGVECTOR_MAJOR}"

    local out rc=0
    out="$(run_cleanup)" || rc=$?
    [[ "${rc}" -eq 0 ]] || { fail "dry-run (no args) exited ${rc}"; return; }

    grep -qE '(^| )rm( |$)' "${CALLS}" && { fail "dry-run issued a docker rm"; return; }
    grep -q "volume rm" "${CALLS}" && { fail "dry-run issued a docker volume rm"; return; }
    grep -q " down" "${CALLS}" && { fail "dry-run issued a docker compose down"; return; }
    echo "${out}" | grep -q -- "--apply" || fail "dry-run output doesn't mention --apply"
    echo "${out}" | grep -q "${ALLOWED_PG}" || fail "dry-run output doesn't name the stale container it found"
    ok "dry-run (default, no --apply) issues no destructive docker command"
}
test_dry_run_is_default_and_nondestructive

# ===========================================================================
# 2. --apply removes only allowlisted names — a decoy container present in
#    `docker ps -a` output must never be touched.
# ===========================================================================
test_apply_removes_only_allowlisted_names() {
    reset_fixtures
    printf '%s\n%s\n' "${ALLOWED_PG}" "roller-postgres-1" > "${CONTAINERS_FILE}"

    run_cleanup --apply >/dev/null || { fail "--apply exited non-zero"; return; }

    grep -q "rm -f -v ${ALLOWED_PG}" "${CALLS}" \
        || { fail "--apply never removed the allowlisted container ${ALLOWED_PG}"; return; }
    grep -q "roller-postgres-1" "${CALLS}" \
        && { fail "--apply touched roller-postgres-1 (not an allowlisted name)"; return; }
    ok "--apply removes only the allowlisted container name, ignoring a decoy in the same ps -a listing"
}
test_apply_removes_only_allowlisted_names

# ===========================================================================
# 3. A resource belonging to another project is never selected, even if it
#    somehow appeared in the raw docker output the script reads.
# ===========================================================================
test_other_project_resources_never_selected() {
    reset_fixtures
    printf '%s\n%s\n' "crafter-delivery" "roller-postgres-1" > "${CONTAINERS_FILE}"
    # Named volume from another project's stack — no PG_VERSION fixture, so
    # even if it slipped past docker's real anonymous/dangling filters this
    # is the script's second line of defense.
    echo "crafter_authoring_data" >> "${VOLUMES_FILE}"

    run_cleanup --apply >/dev/null || { fail "--apply exited non-zero"; return; }

    grep -q "crafter-delivery" "${CALLS}" && { fail "touched crafter-delivery"; return; }
    grep -q "roller-postgres-1" "${CALLS}" && { fail "touched roller-postgres-1"; return; }
    grep -q "volume rm crafter_authoring_data" "${CALLS}" \
        && { fail "removed the named volume crafter_authoring_data"; return; }

    # Load-bearing: the actual safety mechanism is the real docker-side
    # filter flags the script passes to `volume ls` — assert they're there.
    grep -q "volume ls -f dangling=true -f label=com.docker.volume.anonymous" "${CALLS}" \
        || fail "docker volume ls was not called with the dangling+anonymous-label filter"
    ok "another project's container/named-volume is never selected (name allowlist + real docker filter flags)"
}
test_other_project_resources_never_selected

# ===========================================================================
# 4. The embedder's cached-model volume and the dev embedder are untouchable.
# ===========================================================================
test_ollama_models_and_dev_embedder_untouched() {
    reset_fixtures
    echo "container-id-123" > "${EMBED_PS_FILE}"
    # Even if a compose-managed named volume somehow appeared here, it must
    # never be removed by the anonymous-volume sweep (no PG_VERSION content).
    echo "wikantik-embed-test_ollama-models" >> "${VOLUMES_FILE}"

    run_cleanup --apply >/dev/null || { fail "--apply exited non-zero"; return; }

    grep -q "volume rm wikantik-embed-test_ollama-models" "${CALLS}" \
        && { fail "removed the ollama-models volume"; return; }

    local down_call
    down_call="$(grep " down" "${CALLS}" || true)"
    [[ -n "${down_call}" ]] || { fail "expected embedder compose down was never called"; return; }
    echo "${down_call}" | grep -q -- "-v" \
        && { fail "compose down was called WITH -v (would delete the cached model volume): ${down_call}"; return; }
    echo "${down_call}" | grep -q "wikantik-embed-test" \
        || { fail "compose down did not target project wikantik-embed-test: ${down_call}"; return; }

    grep -q "wikantik-embed-dev" "${CALLS}" \
        && { fail "the script talked to wikantik-embed-dev (the developer's own running embedder)"; return; }
    # Checked against executable lines only — the header comment legitimately
    # names wikantik-embed-dev to document that it is deliberately never
    # referenced; what must never happen is the string reaching an actual
    # command.
    grep -v '^[[:space:]]*#' "${SCRIPT}" | grep -q "wikantik-embed-dev" \
        && { fail "bin/docker-cleanup.sh references wikantik-embed-dev outside a comment"; return; }

    ok "ollama-models volume and the dev embedder (wikantik-embed-dev) are never touched; down runs without -v"
}
test_ollama_models_and_dev_embedder_untouched

# ===========================================================================
# 5. An anonymous dangling volume whose PG_VERSION does not match the IT
#    major version is not selected.
# ===========================================================================
test_kc_and_authentik_sidecars_are_per_module() {
    reset_fixtures
    # ALLOWED_KC / ALLOWED_AUTHENTIK are real per-module sidecar container
    # names (sso's Keycloak, scim-fullloop's Authentik redis) — present them
    # alongside a decoy that LOOKS like a kc sidecar but for a module whose
    # pom never declares one (rest has no Keycloak sidecar).
    local decoy_kc="wikantik-kc-${REAL_MODULE}"
    printf '%s\n%s\n%s\n' "${ALLOWED_KC}" "${ALLOWED_AUTHENTIK}" "${decoy_kc}" > "${CONTAINERS_FILE}"

    run_cleanup --apply >/dev/null || { fail "--apply exited non-zero"; return; }

    grep -q "rm -f -v ${ALLOWED_KC}" "${CALLS}" \
        || { fail "did not remove the real Keycloak sidecar ${ALLOWED_KC} (sso module declares it)"; return; }
    grep -q "rm -f -v ${ALLOWED_AUTHENTIK}" "${CALLS}" \
        || { fail "did not remove the real Authentik sidecar ${ALLOWED_AUTHENTIK} (scim-fullloop declares it)"; return; }
    grep -q "rm -f -v ${decoy_kc}" "${CALLS}" \
        && { fail "removed ${decoy_kc} — ${REAL_MODULE}'s pom does not declare a wikantik-kc sidecar, so this name must never be a candidate"; return; }
    ok "kc/authentik sidecar container names are derived per-module from each module's own pom, not assumed for every module"
}
test_kc_and_authentik_sidecars_are_per_module

test_pg_version_mismatch_not_selected() {
    reset_fixtures
    add_anon_volume "wrongversionvol" "${MISMATCH_MAJOR}"
    add_anon_volume "matchingversionvol" "${PGVECTOR_MAJOR}"

    run_cleanup --apply >/dev/null || { fail "--apply exited non-zero"; return; }

    grep -q "volume rm wrongversionvol" "${CALLS}" \
        && { fail "removed a volume whose PG_VERSION (${MISMATCH_MAJOR}) does not match the IT major (${PGVECTOR_MAJOR})"; return; }
    grep -q "volume rm matchingversionvol" "${CALLS}" \
        || { fail "did not remove the volume whose PG_VERSION genuinely matches"; return; }
    ok "a PG_VERSION mismatch excludes the volume; a genuine match is removed"
}
test_pg_version_mismatch_not_selected

# ===========================================================================
# 6. Absent resources are tolerated — nothing to clean is not a failure.
# ===========================================================================
test_absent_resources_tolerated() {
    reset_fixtures
    local rc=0
    run_cleanup --apply >/dev/null 2>"${TMP}/stderr.log" || rc=$?
    [[ "${rc}" -eq 0 ]] || { fail "--apply with nothing to clean exited ${rc}: $(cat "${TMP}/stderr.log")"; return; }
    [[ -s "${CALLS}" ]] || { fail "docker was never even queried (expected discovery calls)"; return; }
    grep -qE '(^| )rm( |$)|volume rm|compose.* down' "${CALLS}" \
        && { fail "issued a destructive call despite nothing existing to clean"; return; }
    ok "absent resources are tolerated: exit 0, no destructive call, no crash"
}
test_absent_resources_tolerated

# ===========================================================================
# 7. --prune-images is opt-in; default run never prunes images.
# ===========================================================================
test_prune_images_is_opt_in() {
    reset_fixtures
    run_cleanup --apply >/dev/null || { fail "--apply exited non-zero"; return; }
    grep -q "image prune" "${CALLS}" && { fail "pruned images without --prune-images"; return; }

    reset_fixtures
    run_cleanup --apply --prune-images >/dev/null || { fail "--apply --prune-images exited non-zero"; return; }
    grep -q "image prune" "${CALLS}" || fail "--prune-images did not call docker image prune"
    ok "--prune-images is opt-in: absent by default, invoked only when passed"
}
test_prune_images_is_opt_in

# ===========================================================================
# 8. docker unreachable is tolerated, not a crash.
# ===========================================================================
test_docker_unreachable_tolerated() {
    reset_fixtures
    echo 1 > "${TMP}/docker.info.rc"
    local rc=0
    run_cleanup >/dev/null 2>&1 || rc=$?
    [[ "${rc}" -eq 0 ]] || fail "unreachable docker daemon caused a non-zero exit (${rc}) instead of a graceful no-op"
    ok "an unreachable docker daemon is tolerated (graceful no-op, exit 0)"
}
test_docker_unreachable_tolerated

# ===========================================================================
# 9. --help / usage error behaviour.
# ===========================================================================
test_help_and_usage_error() {
    local out rc=0
    out="$(run_cleanup --help)" || rc=$?
    [[ "${rc}" -eq 0 ]] || fail "--help exited ${rc}"
    echo "${out}" | grep -q -- "--apply" || fail "--help output doesn't document --apply"

    rc=0
    run_cleanup --bogus-flag >/dev/null 2>&1 || rc=$?
    [[ "${rc}" -eq 2 ]] || fail "an unknown flag exited ${rc}, expected 2 (usage error)"
    ok "--help documents --apply; an unknown flag exits 2"
}
test_help_and_usage_error

# ===========================================================================
# 10. Never a blanket prune anywhere in the source.
# ===========================================================================
test_no_blanket_prune_in_source() {
    # Checked against executable lines only — the header comment legitimately
    # names these commands to document that the script never calls them.
    local code_only
    code_only="$(grep -v '^[[:space:]]*#' "${SCRIPT}")"
    echo "${code_only}" | grep -qE 'docker (volume prune|system prune)' \
        && fail "bin/docker-cleanup.sh calls a blanket docker prune"
    echo "${code_only}" | grep -qE 'docker container prune' \
        && fail "bin/docker-cleanup.sh calls docker container prune"
    ok "no blanket docker prune anywhere in the script's executable code"
}
test_no_blanket_prune_in_source

if [[ "${FAILURES}" -ne 0 ]]; then
    echo "test-docker-cleanup: ${FAILURES} failure(s)"
    exit 1
fi
echo "test-docker-cleanup: all passed"
