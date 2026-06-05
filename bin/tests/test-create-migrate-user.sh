#!/usr/bin/env bash
# Tests for create-migrate-user.sh default resolution. Pure offline style —
# stubs psql on PATH so the script runs end-to-end without a real PostgreSQL,
# then asserts which database name it resolves to. Guards against the default
# drifting away from its sibling scripts (install-fresh.sh / migrate.sh), all
# of which must agree on `wikantik`.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

SCRIPT="bin/db/create-migrate-user.sh"

TMPS=()
cleanup() { local d; for d in "${TMPS[@]+"${TMPS[@]}"}"; do [[ -d "$d" ]] && rm -rf "$d"; done; return 0; }
trap cleanup EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }
ok()   { echo "ok: $*"; }

# Build a sandbox with a stub psql on PATH. The stub echoes "1" for every
# tuples-only query (so db-exists / role-exists / app-exists all pass) and
# exits 0, letting the script run to its summary block offline.
make_psql_stub() {
    local tmp; tmp="$(mktemp -d)"; TMPS+=("${tmp}")
    mkdir -p "${tmp}/bin"
    cat > "${tmp}/bin/psql" <<'STUB'
#!/bin/sh
# Drain any heredoc on stdin so the DO block does not error.
cat >/dev/null 2>&1 || true
echo 1
exit 0
STUB
    chmod +x "${tmp}/bin/psql"
    echo "${tmp}"
}

# run_script "ENV=overrides ..." — runs create-migrate-user.sh with the psql
# stub on PATH and DB_MIGRATE_PASSWORD set. Echoes combined stdout+stderr.
run_script() {
    local sb; sb="$(make_psql_stub)"
    env -i PATH="${sb}/bin:/usr/bin:/bin" \
        DB_MIGRATE_PASSWORD=stub-pw "$@" \
        bash "${SCRIPT}" 2>&1
}

# Resolved "Database:" value from the summary block.
resolved_db() {
    run_script "$@" | sed -n 's/^[[:space:]]*Database:[[:space:]]*//p' | tail -1
}

test_default_db_name_is_wikantik() {
    local got; got="$(resolved_db)"
    [[ "${got}" == "wikantik" ]] \
        || fail "default DB_NAME resolved to '${got}', expected 'wikantik'"
    ok "default DB_NAME resolves to wikantik"
}

test_env_override_still_wins() {
    local got; got="$(resolved_db DB_NAME=somecustomdb)"
    [[ "${got}" == "somecustomdb" ]] \
        || fail "DB_NAME override resolved to '${got}', expected 'somecustomdb'"
    ok "DB_NAME env override is honoured"
}

test_default_matches_sibling_scripts() {
    # All three DB scripts must default to the same database name.
    local self runner installer
    self="$(grep -oE 'DB_NAME="\$\{DB_NAME:-[a-z]+\}"' bin/db/create-migrate-user.sh | grep -oE ':-[a-z]+' | cut -c3-)"
    runner="$(grep -oE 'DB_NAME="\$\{DB_NAME:-[a-z]+\}"' bin/db/migrate.sh | grep -oE ':-[a-z]+' | cut -c3-)"
    installer="$(grep -oE 'DB_NAME="\$\{DB_NAME:-[a-z]+\}"' bin/db/install-fresh.sh | grep -oE ':-[a-z]+' | cut -c3-)"
    [[ "${self}" == "${runner}" && "${self}" == "${installer}" ]] \
        || fail "DB_NAME defaults disagree: create-migrate-user=${self}, migrate=${runner}, install-fresh=${installer}"
    ok "DB_NAME default matches migrate.sh and install-fresh.sh (${self})"
}

test_no_bin_script_defaults_to_jspwiki_db() {
    # Repo-wide guard: no shell script under bin/ may default DB_NAME to
    # anything other than `wikantik`. The app *role* is jspwiki (DB_APP_USER /
    # PGUSER); the *database* is wikantik. These drifted independently before.
    local offenders
    offenders="$(grep -rnE 'DB_NAME="\$\{DB_NAME:-[a-z]+\}"' bin/ \
        | grep -vE ':-wikantik\}' || true)"
    if [[ -n "${offenders}" ]]; then
        fail "bin/ scripts with a non-wikantik DB_NAME default:"$'\n'"${offenders}"
    fi
    ok "no bin/ script defaults DB_NAME to anything but wikantik"
}

echo "== test-create-migrate-user.sh =="
test_default_db_name_is_wikantik
test_env_override_still_wins
test_default_matches_sibling_scripts
test_no_bin_script_defaults_to_jspwiki_db
echo "All create-migrate-user tests passed."
