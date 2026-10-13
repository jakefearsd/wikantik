#!/usr/bin/env bash
# Offline regression guard for `bin/kg-rebuild.sh --reset-kg`.
#
# The defect: the reset deleted `kg_nodes WHERE provenance = 'ai-inferred'`, but approving a
# proposal flips a node's TIER to 'human' while its PROVENANCE stays 'ai-inferred' -- so the reset
# also destroyed every human-approved node (and, by ON DELETE CASCADE, their edges). The
# destructive SQL now lives in bin/db/kg-reset-machine-tier.sql and must delete only
# tier = 'machine' rows.
#
# This suite needs no database: it asserts the guard is textually present in the SQL and that the
# script actually runs that file. The behavioural proof against a real PostgreSQL is
# wikantik-main KgResetMachineTierSqlTest (runs in the unit-test job).
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "${REPO_ROOT}"

SQL="bin/db/kg-reset-machine-tier.sql"
SCRIPT="bin/kg-rebuild.sh"

fail() { echo "FAIL: $*" >&2; exit 1; }
ok()   { echo "ok: $*"; }

# The SQL file with `--` comments removed and whitespace collapsed to single spaces, so
# assertions look at statements only, never at explanatory comments.
sql_statements() {
    sed -e 's/--.*$//' "${SQL}" | tr '\n' ' ' | tr -s '[:space:]' ' '
}

# One DELETE statement (up to and including its terminating semicolon) from the stripped SQL.
delete_statement() {
    sql_statements | grep -oE "DELETE FROM $1[^;]*;" || true
}

test_sql_file_exists() {
    [[ -f "${SQL}" ]] || fail "${SQL} is missing"
    ok "${SQL} exists"
}

test_node_delete_is_tier_guarded() {
    local stmt; stmt="$(delete_statement kg_nodes)"
    [[ -n "${stmt}" ]] || fail "${SQL} has no DELETE FROM kg_nodes statement"
    grep -qE "tier = 'machine'" <<<"${stmt}" \
        || fail "DELETE FROM kg_nodes is not restricted to tier = 'machine': ${stmt}"
    grep -qE "provenance = 'ai-inferred'" <<<"${stmt}" \
        || fail "DELETE FROM kg_nodes lost its provenance = 'ai-inferred' condition: ${stmt}"
    ok "DELETE FROM kg_nodes requires provenance = 'ai-inferred' AND tier = 'machine'"
}

test_human_edge_endpoints_are_kept() {
    # A machine node that is an endpoint of a human-tier edge would take that edge with it
    # (kg_edges FKs are ON DELETE CASCADE), so it has to be excluded.
    local stmt; stmt="$(delete_statement kg_nodes)"
    grep -qE "NOT EXISTS" <<<"${stmt}" && grep -qE "e\.tier = 'human'" <<<"${stmt}" \
        || fail "DELETE FROM kg_nodes no longer spares endpoints of human-tier edges: ${stmt}"
    ok "machine nodes attached to a human-tier edge are spared"
}

test_only_pending_proposals_are_deleted() {
    local stmt; stmt="$(delete_statement kg_proposals)"
    grep -qE "status = 'pending'" <<<"${stmt}" \
        || fail "DELETE FROM kg_proposals is not restricted to status = 'pending': ${stmt}"
    ok "DELETE FROM kg_proposals is restricted to pending proposals"
}

test_sql_deletes_nothing_else() {
    local stmts; stmts="$(sql_statements)"
    local bad
    bad="$(grep -oiE '(TRUNCATE|DROP|ALTER)[^;]*;|DELETE FROM [a-z_]+' <<<"${stmts}" \
        | grep -viE '^DELETE FROM (kg_nodes|kg_proposals)$' || true)"
    [[ -z "${bad}" ]] || fail "${SQL} touches more than kg_nodes/kg_proposals: ${bad}"
    ok "the SQL only deletes from kg_nodes and kg_proposals"
}

test_script_runs_the_sql_file_atomically() {
    grep -q 'db/kg-reset-machine-tier.sql' "${SCRIPT}" \
        || fail "${SCRIPT} does not run ${SQL}"
    grep -qE -- '--single-transaction' "${SCRIPT}" \
        || fail "${SCRIPT} must run the reset in one transaction (--single-transaction)"
    ok "${SCRIPT} runs ${SQL} in a single transaction"
}

test_script_has_no_unguarded_inline_delete() {
    local offenders
    offenders="$(grep -nE "DELETE[[:space:]]+FROM[[:space:]]+kg_nodes" "${SCRIPT}" || true)"
    [[ -z "${offenders}" ]] \
        || fail "${SCRIPT} deletes kg_nodes inline instead of via ${SQL}:"$'\n'"${offenders}"
    ok "${SCRIPT} has no inline DELETE FROM kg_nodes"
}

test_prompt_no_longer_claims_ai_reviewed_is_what_survives() {
    # The old prompt said "Human-authored and AI-reviewed nodes are preserved" while deleting every
    # ai-inferred node, human-approved or not. The prompt must explain the tier rule instead.
    if grep -qi 'AI-reviewed nodes are preserved' "${SCRIPT}"; then
        fail "${SCRIPT} still claims 'AI-reviewed nodes are preserved'"
    fi
    grep -q "tier='human'" "${SCRIPT}" \
        || fail "${SCRIPT} prompt/help must say tier='human' rows are preserved"
    ok "prompt describes the tier-based preservation rule"
}

test_script_parses() {
    bash -n "${SCRIPT}" || fail "${SCRIPT} has a syntax error"
    ok "${SCRIPT} parses"
}

echo "== test-kg-rebuild-reset.sh =="
test_sql_file_exists
test_node_delete_is_tier_guarded
test_human_edge_endpoints_are_kept
test_only_pending_proposals_are_deleted
test_sql_deletes_nothing_else
test_script_runs_the_sql_file_atomically
test_script_has_no_unguarded_inline_delete
test_prompt_no_longer_claims_ai_reviewed_is_what_survives
test_script_parses
echo "All kg-rebuild reset tests passed."
