#!/usr/bin/env bash
# Smoke tests for bin/remote.sh and the prod compose change.
# Pure dry-run + grep style — no real ssh, no real docker.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

fail() { echo "FAIL: $*" >&2; exit 1; }
ok()   { echo "ok: $*"; }

# --- compose: prod pages must bind-mount a host path ---
test_prod_pages_bind_mount() {
    local out
    out="$(WIKANTIK_PAGES_DIR=/srv/wikantik/pages BACKUP_DIR=/srv/wikantik/backups \
           docker compose -f docker-compose.yml -f docker-compose.prod.yml config 2>/dev/null)" \
        || fail "docker compose config rejected prod overlay"
    echo "${out}" | grep -E '^\s+source: /srv/wikantik/pages' >/dev/null \
        || fail "prod overlay did not expand WIKANTIK_PAGES_DIR into a bind mount"
    echo "${out}" | grep -E 'wikantik-pages' >/dev/null \
        && fail "prod overlay still references the wikantik-pages named volume"
    ok "prod compose binds host pages dir"
}

test_prod_pages_bind_mount

# --- remote.env.example documents all required vars ---
test_remote_env_example_complete() {
    [[ -f remote.env.example ]] || fail "remote.env.example missing"
    for var in REMOTE_HOST REMOTE_USER REMOTE_REPO_DIR REMOTE_PAGES_DIR REMOTE_BACKUP_DIR; do
        grep -q "^${var}=" remote.env.example \
            || fail "remote.env.example missing required var ${var}"
    done
    ok "remote.env.example documents required vars"
}

# --- .gitignore must ignore remote.env (not matched by .env.* glob) ---
test_gitignore_blocks_remote_env() {
    grep -qE '^/?remote\.env$' .gitignore \
        || fail ".gitignore does not block remote.env (the .env.* glob does NOT match it)"
    ok ".gitignore blocks remote.env"
}

test_remote_env_example_complete
test_gitignore_blocks_remote_env

# --- remote.sh exists, is executable, passes bash -n ---
test_remote_sh_present_and_parses() {
    [[ -x bin/remote.sh ]] || fail "bin/remote.sh missing or not executable"
    bash -n bin/remote.sh || fail "bin/remote.sh has syntax errors"
    ok "bin/remote.sh present and parses"
}

# --- --help prints the subcommand list ---
test_remote_sh_help_lists_subcommands() {
    local out
    out="$(bin/remote.sh --help 2>&1)" || fail "remote.sh --help returned non-zero"
    for sub in bootstrap deploy rollback up down restart logs shell psql migrate \
               status pages-push pages-pull backup-trigger backup-pull restore; do
        echo "${out}" | grep -qw "${sub}" \
            || fail "--help did not mention subcommand ${sub}"
    done
    ok "--help lists all subcommands"
}

# --- missing remote.env produces a clear error (no ssh attempt) ---
test_missing_env_clear_error() {
    local tmp; tmp="$(mktemp -d)"
    mkdir -p "${tmp}/bin"
    cp bin/remote.sh "${tmp}/bin/remote.sh"
    # No remote.env in tmp. Calling a subcommand must error clearly.
    local out exit_code=0
    out="$("${tmp}/bin/remote.sh" status 2>&1)" || exit_code=$?
    [[ "${exit_code}" -ne 0 ]] || fail "status with no remote.env returned 0; expected non-zero"
    echo "${out}" | grep -q "remote.env" \
        || fail "missing-env error did not mention remote.env: ${out}"
    rm -rf "${tmp}"
    ok "missing remote.env yields a clear error"
}

# --- required var missing → clear error naming the var ---
test_missing_required_var_clear_error() {
    local tmp; tmp="$(mktemp -d)"
    mkdir -p "${tmp}/bin"
    cp bin/remote.sh "${tmp}/bin/remote.sh"
    # Empty remote.env at the repo-root equivalent: nothing defined.
    : > "${tmp}/remote.env"
    local out exit_code=0
    out="$("${tmp}/bin/remote.sh" status 2>&1)" || exit_code=$?
    [[ "${exit_code}" -ne 0 ]] || fail "expected non-zero on empty remote.env"
    echo "${out}" | grep -q "REMOTE_HOST" \
        || fail "missing-var error did not name REMOTE_HOST: ${out}"
    rm -rf "${tmp}"
    ok "missing REMOTE_HOST yields clear error"
}

test_remote_sh_present_and_parses
test_remote_sh_help_lists_subcommands
test_missing_env_clear_error
test_missing_required_var_clear_error

# --- dry-run for an arbitrary helper emits the ssh command without running ---
make_fake_remote_env() {
    local dir="$1"
    cat > "${dir}/remote.env" <<EOF
REMOTE_HOST=test.example.invalid
REMOTE_USER=tester
REMOTE_REPO_DIR=/tmp/repo
REMOTE_PAGES_DIR=/tmp/pages
REMOTE_BACKUP_DIR=/tmp/backups
SSH_CONTROL_DIR=/tmp/cm
EOF
}

test_helpers_emit_ssh_with_controlmaster() {
    local tmp; tmp="$(mktemp -d)"
    mkdir -p "${tmp}/bin"
    cp bin/remote.sh "${tmp}/bin/remote.sh"
    make_fake_remote_env "${tmp}"
    local out exit_code=0
    out="$("${tmp}/bin/remote.sh" --dry-run __selftest 2>&1)" || exit_code=$?
    [[ "${exit_code}" -eq 0 ]] || fail "__selftest dry-run exit ${exit_code}: ${out}"
    echo "${out}" | grep -q "ControlMaster=auto" \
        || fail "_ssh did not include ControlMaster=auto: ${out}"
    echo "${out}" | grep -q "tester@test.example.invalid" \
        || fail "_ssh did not target REMOTE_USER@REMOTE_HOST: ${out}"
    rm -rf "${tmp}"
    ok "_ssh helper emits ControlMaster and target"
}
test_helpers_emit_ssh_with_controlmaster

test_passthrough_dry_run() {
    local tmp out
    tmp="$(mktemp -d)"
    mkdir -p "${tmp}/bin"
    cp bin/remote.sh "${tmp}/bin/remote.sh"
    make_fake_remote_env "${tmp}"

    for cmd in up down restart "logs -f" "shell" "psql -- -c \\dt" "migrate"; do
        out="$(eval "${tmp}/bin/remote.sh --dry-run ${cmd}" 2>&1)" \
            || fail "dry-run ${cmd} non-zero"
        echo "${out}" | grep -q "container.sh -e prod" \
            || fail "dry-run ${cmd} did not invoke remote container.sh -e prod: ${out}"
    done

    # logs default service: wikantik
    out="$("${tmp}/bin/remote.sh" --dry-run logs 2>&1)"
    echo "${out}" | grep -q "logs wikantik" \
        || fail "default logs target should be wikantik: ${out}"

    rm -rf "${tmp}"
    ok "pass-through subcommands invoke remote container.sh"
}
test_passthrough_dry_run
