#!/bin/bash
# One-shot data reconciliation: clear bogus machine verdicts on new-node
# proposals so they can be re-judged with the corrected node prompt.
#
# Background: before the prompt-branch fix, DefaultKgProposalJudgeService had
# a single SYSTEM_PROMPT framed for the (source, target, relationship) edge
# case. New-node proposals — which carry name/nodeType, never source/target/
# relationship — went through the same prompt with null fields interpolated
# as the literal string "null". Empirically this produced 7,295 abstains and
# 5 rejected verdicts on new-node proposals — all rationales centred on
# "missing source/target/relationship to judge factual support", i.e. the LLM
# correctly refused to judge an impossible task. **Zero useful node signal.**
#
# After the fix (DefaultKgProposalJudgeService now branches by proposal_type
# with a dedicated SYSTEM_PROMPT_NODE), the node queue should be re-judged.
#
# What this script does (in a single transaction):
#   1. SELECT all new-node proposals whose machine_status is non-null
#      (i.e. the buggy judge ran on them). Report the count.
#   2. DELETE the corresponding machine review rows from kg_proposal_reviews.
#   3. UPDATE kg_proposals to clear machine_status / machine_judged_at /
#      machine_confidence / machine_model on those proposals.
#
# By default this targets new-node only. Pass --include-edge-abstains to also
# reset the small population of new-edge proposals stuck at machine_status='abstain'
# whose rationales reference page-content the LLM never actually saw — these
# benefit from the tightened "you don't have the source page text" phrasing.
# Edge approved/rejected verdicts are NEVER touched (operator-effort-preserving).
#
# Safe re-run: once cleaned, subsequent runs match nothing.
#
# Usage:
#   bash bin/db/one-shots/reset_node_judge_verdicts.sh                            # dry-run, nodes only
#   bash bin/db/one-shots/reset_node_judge_verdicts.sh --apply                    # commit, nodes only
#   bash bin/db/one-shots/reset_node_judge_verdicts.sh --apply --include-edge-abstains   # also reset edge abstains
#
# Environment (defaults match bin/db/migrate.sh):
#   PGHOST       localhost
#   PGPORT       5432
#   PGUSER       jspwiki
#   PGPASSWORD   (required)
#   DB_NAME      wikantik
set -euo pipefail

case "${1:-}" in
    -h|--help)
        awk '/^#!/{next} !/^#/{exit} {sub(/^# ?/,""); print}' "$0"
        exit 0
        ;;
esac

PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-jspwiki}"
DB_NAME="${DB_NAME:-wikantik}"

if [[ -z "${PGPASSWORD:-}" ]]; then
    echo "PGPASSWORD must be set." >&2
    exit 1
fi

APPLY=0
INCLUDE_EDGES=0
for arg in "$@"; do
    case "$arg" in
        --apply) APPLY=1 ;;
        --include-edge-abstains) INCLUDE_EDGES=1 ;;
        *) echo "Unknown arg: $arg" >&2; exit 1 ;;
    esac
done

echo "Connecting to ${PGUSER}@${PGHOST}:${PGPORT}/${DB_NAME}"
echo "Mode: $([[ $APPLY -eq 1 ]] && echo 'APPLY (transactional)' || echo 'DRY-RUN (no writes)')"
echo "Edge abstains: $([[ $INCLUDE_EDGES -eq 1 ]] && echo 'INCLUDED' || echo 'preserved (run with --include-edge-abstains to reset)')"
echo

# Build the WHERE clause for the affected-proposals selector.
# Always matches every node proposal that has been judged at all.
# When --include-edge-abstains is set, also matches edge proposals stuck at abstain.
PREDICATE="(proposal_type = 'new-node' AND machine_status IS NOT NULL)"
if [[ $INCLUDE_EDGES -eq 1 ]]; then
    PREDICATE="$PREDICATE OR (proposal_type = 'new-edge' AND machine_status = 'abstain')"
fi

psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$DB_NAME" -v ON_ERROR_STOP=1 <<SQL
SELECT
    proposal_type,
    machine_status,
    COUNT(*) AS proposals_to_reset
FROM kg_proposals
WHERE $PREDICATE
GROUP BY 1, 2
ORDER BY 1, 2;
SQL

if [[ $APPLY -eq 0 ]]; then
    echo
    echo "Dry-run only. Re-run with --apply to commit."
    exit 0
fi

# Transaction: delete machine review rows for affected proposals, then null
# out their machine_* columns so the runner picks them up again.
psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$DB_NAME" -v ON_ERROR_STOP=1 <<SQL
BEGIN;

CREATE TEMP TABLE _affected ON COMMIT DROP AS
SELECT id AS proposal_id
FROM kg_proposals
WHERE $PREDICATE;

SELECT COUNT(*) AS proposals_affected FROM _affected;

DELETE FROM kg_proposal_reviews r
USING _affected a
WHERE r.proposal_id = a.proposal_id
  AND r.reviewer_kind = 'machine';

UPDATE kg_proposals p
SET machine_status     = NULL,
    machine_judged_at  = NULL,
    machine_confidence = NULL,
    machine_model      = NULL
FROM _affected a
WHERE p.id = a.proposal_id;

COMMIT;
SQL

echo
echo "Done. The next judge cron pass (or 'Run judge runner' button) will"
echo "re-evaluate the cleared proposals with the corrected per-type prompts."
