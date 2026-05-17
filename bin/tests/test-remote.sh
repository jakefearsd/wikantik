#!/usr/bin/env bash
# Smoke tests for bin/remote.sh and the prod compose change.
# Pure dry-run + grep style — no real ssh, no real docker.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

# Track tmpdirs created by with_fake_env so fail()/exit paths still clean up.
WITH_FAKE_ENV_TMPS=()
_cleanup_fake_envs() {
    local d
    for d in "${WITH_FAKE_ENV_TMPS[@]+"${WITH_FAKE_ENV_TMPS[@]}"}"; do
        if [[ -n "${d}" && -d "${d}" ]]; then rm -rf "${d}"; fi
    done
    return 0
}
trap _cleanup_fake_envs EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
ok()   { echo "ok: $*"; }

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

# with_fake_env BODY_FN [ARGS...]
#
# Provisions a sandbox containing a copy of bin/remote.sh and a fake
# remote.env, then invokes BODY_FN with FAKE_ENV set to the sandbox dir.
# Tmpdir is cleaned up on RETURN (success path) and via the global EXIT
# trap (covers fail()/exit paths in BODY_FN).
with_fake_env() {
    local body_fn="$1"; shift
    local tmp; tmp="$(mktemp -d)"
    WITH_FAKE_ENV_TMPS+=("${tmp}")
    # Cleanup-on-return — survives normal exits from BODY_FN. The EXIT
    # trap catches fail()/set -e bailouts.
    trap "rm -rf '${tmp}'" RETURN
    mkdir -p "${tmp}/bin"
    cp bin/remote.sh "${tmp}/bin/remote.sh"
    make_fake_remote_env "${tmp}"
    FAKE_ENV="${tmp}" "${body_fn}" "$@"
}

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

# --- compose: the base compose must carry the wikantik build context ---
# Regression guard. The build context used to live only in
# docker-compose.prod.yml; both `container.sh build` and `remote.sh deploy`
# build against the *base* compose, so a base with no wikantik `build:` makes
# the build a silent no-op ("No services to build") that ships a stale image.
test_base_compose_has_wikantik_build() {
    local out
    out="$(docker compose -f docker-compose.yml config 2>/dev/null)" \
        || fail "docker compose config rejected the base compose"
    echo "${out}" | grep -q "context: ${REPO_ROOT}" \
        || fail "base docker-compose.yml has no wikantik build context"
    ok "base compose carries the wikantik build context"
}
test_base_compose_has_wikantik_build

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
_body_missing_env() {
    # with_fake_env populates remote.env; remove it to exercise the missing case.
    rm -f "${FAKE_ENV}/remote.env"
    local out exit_code=0
    out="$("${FAKE_ENV}/bin/remote.sh" status 2>&1)" || exit_code=$?
    [[ "${exit_code}" -ne 0 ]] || fail "status with no remote.env returned 0; expected non-zero"
    echo "${out}" | grep -q "remote.env" \
        || fail "missing-env error did not mention remote.env: ${out}"
}
test_missing_env_clear_error() {
    with_fake_env _body_missing_env
    ok "missing remote.env yields a clear error"
}

# --- required var missing → clear error naming the var ---
_body_missing_required_var() {
    : > "${FAKE_ENV}/remote.env"     # truncate to empty
    local out exit_code=0
    out="$("${FAKE_ENV}/bin/remote.sh" status 2>&1)" || exit_code=$?
    [[ "${exit_code}" -ne 0 ]] || fail "expected non-zero on empty remote.env"
    echo "${out}" | grep -q "REMOTE_HOST" \
        || fail "missing-var error did not name REMOTE_HOST: ${out}"
}
test_missing_required_var_clear_error() {
    with_fake_env _body_missing_required_var
    ok "missing REMOTE_HOST yields clear error"
}

test_remote_sh_present_and_parses
test_remote_sh_help_lists_subcommands
test_missing_env_clear_error
test_missing_required_var_clear_error

# --- dry-run for an arbitrary helper emits the ssh command without running ---
_body_helpers_emit_ssh() {
    local out exit_code=0
    out="$("${FAKE_ENV}/bin/remote.sh" --dry-run __selftest 2>&1)" || exit_code=$?
    [[ "${exit_code}" -eq 0 ]] || fail "__selftest dry-run exit ${exit_code}: ${out}"
    echo "${out}" | grep -q "ControlMaster=auto" \
        || fail "_ssh did not include ControlMaster=auto: ${out}"
    echo "${out}" | grep -q "tester@test.example.invalid" \
        || fail "_ssh did not target REMOTE_USER@REMOTE_HOST: ${out}"
}
test_helpers_emit_ssh_with_controlmaster() {
    with_fake_env _body_helpers_emit_ssh
    ok "_ssh helper emits ControlMaster and target"
}
test_helpers_emit_ssh_with_controlmaster

_body_passthrough_dry_run() {
    local out
    for cmd in up down restart "logs -f" "shell" "psql -- -c \\dt" "migrate"; do
        out="$(eval "${FAKE_ENV}/bin/remote.sh --dry-run ${cmd}" 2>&1)" \
            || fail "dry-run ${cmd} non-zero"
        echo "${out}" | grep -q "container.sh -e prod" \
            || fail "dry-run ${cmd} did not invoke remote container.sh -e prod: ${out}"
    done

    # logs default service: wikantik
    out="$("${FAKE_ENV}/bin/remote.sh" --dry-run logs 2>&1)"
    echo "${out}" | grep -q "logs wikantik" \
        || fail "default logs target should be wikantik: ${out}"
}
test_passthrough_dry_run() {
    with_fake_env _body_passthrough_dry_run
    ok "pass-through subcommands invoke remote container.sh"
}
test_passthrough_dry_run

_body_bootstrap_dry_run() {
    local out
    out="$("${FAKE_ENV}/bin/remote.sh" --dry-run bootstrap 2>&1)" \
        || fail "bootstrap dry-run non-zero: ${out}"

    # Must check for docker on remote
    echo "${out}" | grep -q "command -v docker" \
        || fail "bootstrap did not check for remote docker: ${out}"
    # Must create the three remote dirs
    echo "${out}" | grep -q "mkdir -p" \
        || fail "bootstrap did not create remote dirs: ${out}"
    echo "${out}" | grep -q "/tmp/pages" \
        || fail "bootstrap did not reference REMOTE_PAGES_DIR: ${out}"
    echo "${out}" | grep -q "/tmp/backups" \
        || fail "bootstrap did not reference REMOTE_BACKUP_DIR: ${out}"
    # Must rsync compose files
    echo "${out}" | grep -q "docker-compose.yml" \
        || fail "bootstrap did not rsync compose files: ${out}"
    echo "${out}" | grep -q "docker-compose.prod.yml" \
        || fail "bootstrap did not rsync prod overlay: ${out}"
    # Must NOT run up -d
    if echo "${out}" | grep -q "container.sh -e prod up"; then
        fail "bootstrap must NOT run up -d (that's deploy's job): ${out}"
    fi
}
test_bootstrap_dry_run() {
    with_fake_env _body_bootstrap_dry_run
    ok "bootstrap dry-run does the right things, does not up -d"
}
test_bootstrap_dry_run

_body_deploy_dry_run_shape() {
    local out
    # --skip-build avoids running mvn / docker build in dry-run mode.
    out="$("${FAKE_ENV}/bin/remote.sh" --dry-run deploy --skip-build 2>&1)" \
        || fail "deploy dry-run non-zero: ${out}"

    # Tag-rollback step
    echo "${out}" | grep -q "docker tag wikantik:latest wikantik:rollback" \
        || fail "deploy did not tag prior image as :rollback: ${out}"
    # Image stream
    echo "${out}" | grep -q "docker save wikantik:latest" \
        || fail "deploy did not docker-save the image: ${out}"
    echo "${out}" | grep -q "docker load" \
        || fail "deploy did not docker-load on the remote: ${out}"
    # Remote up
    echo "${out}" | grep -q "container.sh -e prod up" \
        || fail "deploy did not up -d on remote: ${out}"
    # Lock acquisition
    echo "${out}" | grep -q "flock" \
        || fail "deploy did not acquire flock: ${out}"
    # rsync .env + compose
    echo "${out}" | grep -q "docker-compose.prod.yml" \
        || fail "deploy did not rsync prod overlay: ${out}"
}
test_deploy_dry_run_shape() {
    with_fake_env _body_deploy_dry_run_shape
    ok "deploy dry-run includes lock, tag-rollback, image stream, up, sync"
}

_body_deploy_help() {
    local out
    out="$("${FAKE_ENV}/bin/remote.sh" deploy --help 2>&1)" \
        || fail "deploy --help non-zero"
    echo "${out}" | grep -q "health-timeout" \
        || fail "deploy --help does not document --health-timeout"
}
test_deploy_help() {
    with_fake_env _body_deploy_help
    ok "deploy --help documents flags"
}
test_deploy_dry_run_shape
test_deploy_help

# --- deploy without --skip-build runs the Maven + image build ---
_body_deploy_dry_run_builds() {
    local out
    out="$("${FAKE_ENV}/bin/remote.sh" --dry-run deploy 2>&1)" \
        || fail "deploy dry-run (with build) non-zero: ${out}"
    echo "${out}" | grep -q "mvn clean install" \
        || fail "deploy did not run the Maven build: ${out}"
    echo "${out}" | grep -q "container.sh build" \
        || fail "deploy did not build the image via container.sh: ${out}"
}
test_deploy_dry_run_builds() {
    with_fake_env _body_deploy_dry_run_builds
    ok "deploy (no --skip-build) runs mvn + container.sh build"
}
test_deploy_dry_run_builds

_body_rollback_dry_run() {
    local out
    out="$("${FAKE_ENV}/bin/remote.sh" --dry-run rollback 2>&1)" \
        || fail "rollback dry-run non-zero: ${out}"
    echo "${out}" | grep -q "docker tag wikantik:rollback wikantik:latest" \
        || fail "rollback did not re-promote :rollback: ${out}"
    echo "${out}" | grep -q "force-recreate wikantik" \
        || fail "rollback did not force-recreate wikantik: ${out}"
    echo "${out}" | grep -q "flock" \
        || fail "rollback did not acquire deploy lock: ${out}"
}
test_rollback_dry_run() {
    with_fake_env _body_rollback_dry_run
    ok "rollback dry-run re-promotes :rollback, recreates, acquires lock"
}
test_rollback_dry_run

_body_pages_push_no_delete() {
    local out
    mkdir -p "${FAKE_ENV}/some-pages"
    out="$("${FAKE_ENV}/bin/remote.sh" --dry-run pages-push "${FAKE_ENV}/some-pages" 2>&1)" \
        || fail "pages-push dry-run non-zero: ${out}"
    echo "${out}" | grep -q "rsync" \
        || fail "pages-push did not call rsync: ${out}"
    if echo "${out}" | grep -q -- "--delete"; then
        fail "pages-push must NOT pass --delete by default: ${out}"
    fi
    echo "${out}" | grep -q ":/tmp/pages" \
        || fail "pages-push did not target REMOTE_PAGES_DIR: ${out}"
}
test_pages_push_no_delete_by_default() {
    with_fake_env _body_pages_push_no_delete
    ok "pages-push (default) does not pass --delete"
}

_body_pages_push_mirror() {
    local out
    mkdir -p "${FAKE_ENV}/some-pages"
    # --mirror --yes skips the confirmation prompt in scripted use.
    out="$("${FAKE_ENV}/bin/remote.sh" --dry-run pages-push "${FAKE_ENV}/some-pages" --mirror --yes 2>&1)" \
        || fail "pages-push --mirror dry-run non-zero: ${out}"
    echo "${out}" | grep -q -- "--delete" \
        || fail "pages-push --mirror must pass --delete: ${out}"
}
test_pages_push_mirror_passes_delete() {
    with_fake_env _body_pages_push_mirror
    ok "pages-push --mirror --yes passes --delete"
}

_body_pages_pull_dry_run() {
    local out
    out="$("${FAKE_ENV}/bin/remote.sh" --dry-run pages-pull "${FAKE_ENV}/dest" 2>&1)" \
        || fail "pages-pull dry-run non-zero: ${out}"
    echo "${out}" | grep -q ":/tmp/pages/" \
        || fail "pages-pull did not read from REMOTE_PAGES_DIR: ${out}"
    if echo "${out}" | grep -q -- "--delete"; then
        fail "pages-pull must never pass --delete (read-only): ${out}"
    fi
}
test_pages_pull_dry_run() {
    with_fake_env _body_pages_pull_dry_run
    ok "pages-pull is read-only"
}
test_pages_push_no_delete_by_default
test_pages_push_mirror_passes_delete
test_pages_pull_dry_run

_body_backup_subcommands() {
    local out
    out="$("${FAKE_ENV}/bin/remote.sh" --dry-run backup-trigger 2>&1)" \
        || fail "backup-trigger dry-run non-zero: ${out}"
    echo "${out}" | grep -q "container.sh -e prod backup" \
        || fail "backup-trigger did not invoke remote backup: ${out}"
    echo "${out}" | grep -q "daily" \
        || fail "backup-trigger default tier should be daily: ${out}"

    out="$("${FAKE_ENV}/bin/remote.sh" --dry-run backup-pull 2026-05-14 2>&1)" \
        || fail "backup-pull dry-run non-zero: ${out}"
    echo "${out}" | grep -q "/tmp/backups/" \
        || fail "backup-pull did not source REMOTE_BACKUP_DIR: ${out}"

    out="$("${FAKE_ENV}/bin/remote.sh" --dry-run restore /backups/daily/2026-05-14 2>&1)" \
        || fail "restore dry-run non-zero: ${out}"
    echo "${out}" | grep -q "container.sh -e prod restore" \
        || fail "restore did not invoke remote restore: ${out}"
    echo "${out}" | grep -q "flock" \
        || fail "restore did not acquire deploy lock: ${out}"
}
test_backup_subcommands_dry_run() {
    with_fake_env _body_backup_subcommands
    ok "backup-trigger / backup-pull / restore dry-runs are well-formed"
}
test_backup_subcommands_dry_run

_body_status_dry_run() {
    local out
    out="$("${FAKE_ENV}/bin/remote.sh" --dry-run status 2>&1)" \
        || fail "status dry-run non-zero: ${out}"
    for needle in "container.sh -e prod ps" "/api/health" "df " "du " "logs --tail"; do
        echo "${out}" | grep -qF "${needle}" \
            || fail "status missing '${needle}': ${out}"
    done
}
test_status_dry_run() {
    with_fake_env _body_status_dry_run
    ok "status dry-run gathers ps + health + df + pages-size + log tail"
}
test_status_dry_run

# --- Error-path tests (Phase 6, Task C) ---

_body_unknown_subcommand() {
    local out exit_code=0
    out="$("${FAKE_ENV}/bin/remote.sh" foobar 2>&1)" || exit_code=$?
    [[ "${exit_code}" -eq 2 ]] || fail "unknown subcommand should exit 2, got ${exit_code}: ${out}"
    echo "${out}" | grep -q "foobar" \
        || fail "unknown-subcommand error did not echo the bad name: ${out}"
    echo "${out}" | grep -qi "unknown subcommand" \
        || fail "unknown-subcommand error did not say 'unknown subcommand': ${out}"
}
test_unknown_subcommand_errors() {
    with_fake_env _body_unknown_subcommand
    ok "unknown subcommand exits 2 and names the offender"
}
test_unknown_subcommand_errors

_body_pages_push_missing_local_dir() {
    local out exit_code=0
    out="$("${FAKE_ENV}/bin/remote.sh" pages-push 2>&1)" || exit_code=$?
    [[ "${exit_code}" -eq 2 ]] || fail "pages-push (no LOCAL_DIR) should exit 2, got ${exit_code}: ${out}"
    echo "${out}" | grep -q "missing LOCAL_DIR" \
        || fail "pages-push error did not mention 'missing LOCAL_DIR': ${out}"
}
test_pages_push_missing_local_dir() {
    with_fake_env _body_pages_push_missing_local_dir
    ok "pages-push without LOCAL_DIR exits 2 with clear error"
}
test_pages_push_missing_local_dir

_body_pages_push_not_a_directory() {
    local out exit_code=0
    out="$("${FAKE_ENV}/bin/remote.sh" pages-push /nonexistent/path 2>&1)" || exit_code=$?
    [[ "${exit_code}" -eq 2 ]] || fail "pages-push (bad dir) should exit 2, got ${exit_code}: ${out}"
    echo "${out}" | grep -q "not a directory" \
        || fail "pages-push error did not mention 'not a directory': ${out}"
}
test_pages_push_not_a_directory() {
    with_fake_env _body_pages_push_not_a_directory
    ok "pages-push with non-directory exits 2 with clear error"
}
test_pages_push_not_a_directory

_body_restore_missing_path() {
    local out exit_code=0
    out="$("${FAKE_ENV}/bin/remote.sh" restore 2>&1)" || exit_code=$?
    [[ "${exit_code}" -eq 2 ]] || fail "restore (no path) should exit 2, got ${exit_code}: ${out}"
    echo "${out}" | grep -q "missing REMOTE_PATH" \
        || fail "restore error did not mention 'missing REMOTE_PATH': ${out}"
}
test_restore_missing_path_errors() {
    with_fake_env _body_restore_missing_path
    ok "restore without REMOTE_PATH exits 2 with clear error"
}
test_restore_missing_path_errors

_body_deploy_health_timeout_equals() {
    local out
    out="$("${FAKE_ENV}/bin/remote.sh" --dry-run deploy --skip-build --health-timeout=120 2>&1)" \
        || fail "deploy --health-timeout=120 dry-run non-zero: ${out}"
    # cmd_deploy emits a "[dry-run] poll ... up to <N>s" line — assert 120 lands there.
    echo "${out}" | grep -qE "up to 120s" \
        || fail "deploy did not propagate --health-timeout=120 (no 'up to 120s' line): ${out}"
}
test_deploy_health_timeout_flag_equals() {
    with_fake_env _body_deploy_health_timeout_equals
    ok "deploy --health-timeout=120 parses (equals form)"
}
test_deploy_health_timeout_flag_equals

_body_deploy_health_timeout_space() {
    local out
    out="$("${FAKE_ENV}/bin/remote.sh" --dry-run deploy --skip-build --health-timeout 120 2>&1)" \
        || fail "deploy --health-timeout 120 dry-run non-zero: ${out}"
    echo "${out}" | grep -qE "up to 120s" \
        || fail "deploy did not propagate --health-timeout 120 (no 'up to 120s' line): ${out}"
}
test_deploy_health_timeout_flag_space() {
    with_fake_env _body_deploy_health_timeout_space
    ok "deploy --health-timeout 120 parses (space form)"
}
test_deploy_health_timeout_flag_space
