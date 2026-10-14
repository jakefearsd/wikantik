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
# Offline regression guard for bin/db/one-shots/dedupe_display_names.sh (+ .sql). No database: psql is
# a stub on PATH that records its arguments. It pins what is easy to break silently:
#   * the dry run ends in ROLLBACK and never COMMITs; --apply COMMITs and never ROLLBACKs;
#   * the data-changing SQL cannot run before the dedupe_apply setting is switched on;
#   * the SQL collapses to one line (no trailing "--" comment) and its report never selects a login
#     name or an email address;
#   * PSQL_COMMAND routes psql through a wrapper (the production path is `bin/remote.sh psql --`);
#   * psql runs with VERBOSITY=terse on both paths, so an error never prints the failing account row.
# The behavioural proof against a real PostgreSQL is wikantik-main DedupeDisplayNamesSqlTest.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SH="${REPO_ROOT}/bin/db/one-shots/dedupe_display_names.sh"
SQL="${REPO_ROOT}/bin/db/one-shots/dedupe_display_names.sql"

TMPS=()
cleanup() { local d; for d in "${TMPS[@]+"${TMPS[@]}"}"; do [[ -d "$d" ]] && rm -rf "$d"; done; return 0; }
trap cleanup EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }
ok()   { echo "ok: $*"; }

# A sandbox mirroring bin/db/one-shots/ (so the script's ROOT.xml fallback finds nothing) with a stub
# psql on PATH. Each psql call appends one "CALL" block of its arguments, one per line, to $CMDLOG.
make_sandbox() {
    local tmp; tmp="$(mktemp -d)"; TMPS+=("$tmp")
    mkdir -p "${tmp}/bin/db/one-shots" "${tmp}/stubs"
    cp "$SH" "$SQL" "${tmp}/bin/db/one-shots/"
    cat > "${tmp}/stubs/psql" <<'STUB'
#!/usr/bin/env bash
{ echo "CALL"; for a in "$@"; do printf 'ARG:%s\n' "$a"; done; } >> "$CMDLOG"
exit "${PSQL_RC:-0}"
STUB
    cat > "${tmp}/stubs/fake-remote" <<'STUB'
#!/usr/bin/env bash
{ echo "REMOTE"; for a in "$@"; do printf 'ARG:%s\n' "$a"; done; } >> "$CMDLOG"
exit 0
STUB
    chmod +x "${tmp}/stubs/psql" "${tmp}/stubs/fake-remote"
    echo "$tmp"
}

# run_script SANDBOX [args...] -- runs the sandboxed script with the stub on PATH, a fresh CMDLOG.
run_script() {
    local sb="$1"; shift
    export CMDLOG="${sb}/cmdlog"; : > "$CMDLOG"
    PATH="${sb}/stubs:${PATH}" PGPASSWORD=secret "${sb}/bin/db/one-shots/dedupe_display_names.sh" "$@"
}

# The SQL without full-line comments, one line.
sql_statements() { sed -e '/^[[:space:]]*--/d' "$SQL" | tr '\n' ' ' | tr -s '[:space:]' ' '; }

test_sql_is_guarded_and_collapsible() {
    [[ -f "$SQL" ]] || fail "${SQL} is missing"
    local sql; sql="$(sql_statements)"
    [[ "$sql" == *"wikantik.dedupe_apply"* ]] || fail "the SQL no longer checks wikantik.dedupe_apply"
    [[ "$sql" == *"RAISE EXCEPTION"* ]] || fail "the SQL no longer raises when the apply setting is off"
    [[ "$sql" != *--* ]] || fail "the SQL has a trailing '--' comment; the .sh would comment out the rest of the statement"
    ok "SQL is apply-guarded and survives being collapsed to one line"
}

test_report_never_selects_logins_or_emails() {
    local inserts; inserts="$(sql_statements | grep -oE 'INSERT INTO dedupe_display_names_[a-z_]+[^;]*;' || true)"
    [[ -n "$inserts" ]] || fail "no INSERT INTO dedupe_display_names_* found"
    # String literals ('email-shaped') are labels, not columns, so drop them before looking.
    local columns; columns="$(sed -E "s/'[^']*'//g" <<<"$inserts")"
    if grep -qiE 'login_name|email' <<<"$columns"; then
        fail "a report INSERT references login_name/email: ${inserts}"
    fi
    ok "report rows are built from display names only"
}

test_dry_run_rolls_back() {
    local sb; sb="$(make_sandbox)"
    run_script "$sb" > "${sb}/out"
    grep -q '^ARG:ROLLBACK$' "${sb}/cmdlog" || fail "dry run did not ROLLBACK"
    if grep -q '^ARG:COMMIT$' "${sb}/cmdlog"; then fail "dry run COMMITs"; fi
    grep -q 'DRY-RUN' "${sb}/out" || fail "dry run does not say so"
    ok "default is a dry run that ends in ROLLBACK"
}

test_apply_commits() {
    local sb; sb="$(make_sandbox)"
    run_script "$sb" --apply > "${sb}/out"
    grep -q '^ARG:COMMIT$' "${sb}/cmdlog" || fail "--apply did not COMMIT"
    if grep -q '^ARG:ROLLBACK$' "${sb}/cmdlog"; then fail "--apply ROLLBACKs"; fi
    ok "--apply ends in COMMIT"
}

test_setting_is_switched_on_before_the_sql() {
    local sb; sb="$(make_sandbox)"
    run_script "$sb" > /dev/null
    local begin set_on sql
    begin="$(grep -n '^ARG:BEGIN$' "${sb}/cmdlog" | head -1 | cut -d: -f1 || true)"
    set_on="$(grep -n "^ARG:SET LOCAL wikantik.dedupe_apply = 'on'$" "${sb}/cmdlog" | head -1 | cut -d: -f1 || true)"
    # shellcheck disable=SC2016
    sql="$(grep -n '^ARG: *DO \$dedupe\$' "${sb}/cmdlog" | head -1 | cut -d: -f1 || true)"
    [[ -n "$begin" && -n "$set_on" && -n "$sql" ]] || fail "BEGIN / SET LOCAL / DO not all issued (${begin}/${set_on}/${sql})"
    [[ "$begin" -lt "$set_on" && "$set_on" -lt "$sql" ]] || fail "order must be BEGIN, SET LOCAL, DO (${begin}/${set_on}/${sql})"
    ok "BEGIN, then the apply setting, then the SQL"
}

test_psql_failure_fails_the_script() {
    local sb; sb="$(make_sandbox)"
    if PSQL_RC=3 run_script "$sb" --apply > "${sb}/out" 2>&1; then
        fail "a failing psql must fail the script"
    fi
    ok "a psql failure fails the script"
}

test_psql_command_wrapper_is_used() {
    local sb; sb="$(make_sandbox)"
    PSQL_COMMAND="${sb}/stubs/fake-remote psql --" run_script "$sb" > "${sb}/out"
    [[ "$(sed -n 1p "${sb}/cmdlog")" == "REMOTE" ]] || fail "PSQL_COMMAND wrapper was not used"
    [[ "$(sed -n 2p "${sb}/cmdlog")" == "ARG:psql" && "$(sed -n 3p "${sb}/cmdlog")" == "ARG:--" ]] \
        || fail "wrapper must receive its own leading arguments first: $(sed -n 1,4p "${sb}/cmdlog" | tr '\n' ' ')"
    if grep -q '^CALL$' "${sb}/cmdlog"; then fail "psql was called directly despite PSQL_COMMAND"; fi
    ok "PSQL_COMMAND routes psql through the wrapper"
}

test_psql_runs_with_terse_verbosity() {
    # A constraint error otherwise prints "DETAIL: Failing row contains (...)": a whole account row (uid,
    # email, login name). VERBOSITY=terse keeps only the ERROR line. Both ways of reaching psql must set it.
    local sb; sb="$(make_sandbox)"
    run_script "$sb" > "${sb}/out"
    grep -qx 'ARG:VERBOSITY=terse' "${sb}/cmdlog" || fail "direct psql is not run with -v VERBOSITY=terse"
    PSQL_COMMAND="${sb}/stubs/fake-remote psql --" run_script "$sb" > "${sb}/out"
    grep -qx 'ARG:VERBOSITY=terse' "${sb}/cmdlog" || fail "psql via PSQL_COMMAND is not run with -v VERBOSITY=terse"
    ok "psql runs with VERBOSITY=terse on both paths"
}

test_unknown_argument_and_help() {
    local sb; sb="$(make_sandbox)"
    local rc=0
    run_script "$sb" --bogus > "${sb}/out" 2>&1 || rc=$?
    [[ "$rc" -eq 2 ]] || fail "unknown argument must exit 2 (got ${rc})"
    run_script "$sb" --help | grep -q -- '--apply' || fail "--help does not describe --apply"
    ok "unknown arguments are refused; --help works"
}

test_missing_password_is_refused_without_calling_psql() {
    local sb; sb="$(make_sandbox)"
    export CMDLOG="${sb}/cmdlog"; : > "$CMDLOG"
    local rc=0
    PATH="${sb}/stubs:${PATH}" PGPASSWORD='' "${sb}/bin/db/one-shots/dedupe_display_names.sh" > "${sb}/out" 2>&1 || rc=$?
    [[ "$rc" -eq 1 ]] || fail "a missing password must exit 1 (got ${rc})"
    [[ ! -s "$CMDLOG" ]] || fail "psql was called without a password"
    ok "no password, no psql"
}

test_sql_is_guarded_and_collapsible
test_report_never_selects_logins_or_emails
test_dry_run_rolls_back
test_apply_commits
test_setting_is_switched_on_before_the_sql
test_psql_failure_fails_the_script
test_psql_command_wrapper_is_used
test_psql_runs_with_terse_verbosity
test_unknown_argument_and_help
test_missing_password_is_refused_without_calling_psql

echo "PASS: test-dedupe-display-names.sh (10 cases)"
