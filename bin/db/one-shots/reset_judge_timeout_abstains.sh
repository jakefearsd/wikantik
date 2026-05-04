#!/bin/bash
# One-shot data reconciliation: clear judge timeout-abstains so affected
# proposals get re-judged on the next cron pass.
#
# Background: before commit 37c209587, DefaultKgProposalJudgeService didn't
# set keep_alive on the Ollama /api/chat body. Ollama unloaded models after
# its 5-minute idle default — exactly the judge cron interval — so the first
# request of each batch cold-loaded the model and timed out (30 s default).
# Each timeout wrote an "abstain" review with rationale starting
# "judge_unavailable: ..." and stamped machine_status=abstain on the proposal.
#
# These proposals look "decided" in the operator queue when they were never
# actually shown to the model. With the keep_alive=30m + 120s timeout fix
# deployed, we want them re-judged.
#
# What this script does (in a single transaction):
#   1. SELECT proposals whose latest machine review is a timeout-abstain
#      AND whose current machine_status is 'abstain'. Report the count.
#   2. DELETE those timeout review rows from kg_proposal_reviews.
#   3. UPDATE kg_proposals to clear machine_status / machine_judged_at /
#      machine_confidence / machine_model on those proposals.
#
# Safe re-run: once cleaned, subsequent runs match nothing.
#
# Usage:
#   bash bin/db/one-shots/reset_judge_timeout_abstains.sh           # dry-run
#   bash bin/db/one-shots/reset_judge_timeout_abstains.sh --apply   # commit
#
# Environment (defaults match bin/db/migrate.sh):
#   PGHOST       localhost
#   PGPORT       5432
#   PGUSER       jspwiki
#   PGPASSWORD   (required)
#   DB_NAME      jspwiki
set -euo pipefail

PGHOST="${PGHOST:-localhost}"
PGPORT="${PGPORT:-5432}"
PGUSER="${PGUSER:-jspwiki}"
DB_NAME="${DB_NAME:-jspwiki}"

if [[ -z "${PGPASSWORD:-}" ]]; then
    echo "PGPASSWORD must be set." >&2
    exit 1
fi

APPLY=0
if [[ "${1:-}" == "--apply" ]]; then
    APPLY=1
fi

echo "Connecting to ${PGUSER}@${PGHOST}:${PGPORT}/${DB_NAME}"
echo "Mode: $([[ $APPLY -eq 1 ]] && echo 'APPLY (transactional)' || echo 'DRY-RUN (no writes)')"
echo

SQL_PREVIEW='
WITH latest_review AS (
  SELECT proposal_id, rationale,
         ROW_NUMBER() OVER (PARTITION BY proposal_id ORDER BY created DESC) AS rn
  FROM kg_proposal_reviews
  WHERE reviewer_kind = '"'"'machine'"'"'
)
SELECT COUNT(*) AS proposals_to_reset
FROM latest_review lr
JOIN kg_proposals p ON p.id = lr.proposal_id
WHERE lr.rn = 1
  AND lr.rationale LIKE '"'"'judge_unavailable%'"'"'
  AND p.machine_status = '"'"'abstain'"'"';
'

psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -c "$SQL_PREVIEW"

if [[ $APPLY -eq 0 ]]; then
    echo
    echo "Dry-run only. Re-run with --apply to commit."
    exit 0
fi

# Transaction: delete the timeout review rows and clear machine_status on
# affected proposals. Targeted by the same predicate as the preview.
psql -h "$PGHOST" -p "$PGPORT" -U "$PGUSER" -d "$DB_NAME" -v ON_ERROR_STOP=1 <<'SQL'
BEGIN;

CREATE TEMP TABLE _affected ON COMMIT DROP AS
SELECT p.id AS proposal_id
FROM kg_proposals p
JOIN LATERAL (
    SELECT rationale
    FROM kg_proposal_reviews
    WHERE proposal_id = p.id AND reviewer_kind = 'machine'
    ORDER BY created DESC LIMIT 1
) latest ON TRUE
WHERE p.machine_status = 'abstain'
  AND latest.rationale LIKE 'judge_unavailable%';

SELECT COUNT(*) AS proposals_affected FROM _affected;

DELETE FROM kg_proposal_reviews r
USING _affected a
WHERE r.proposal_id = a.proposal_id
  AND r.reviewer_kind = 'machine'
  AND r.verdict = 'abstain'
  AND r.rationale LIKE 'judge_unavailable%';

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
echo "Done. The next judge cron pass will re-evaluate the cleared proposals."
