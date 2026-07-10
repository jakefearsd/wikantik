-- Scheduled bundle-eval run results (RetrievalEvaluationObservability Phase 1, 2026-07-10).
-- One row per scheduled harness run; recall@12 overall + per BundleCategory
-- (SIMILARITY/RELATIONAL/BOUNDARY); regression=true when any recall fell below its
-- threshold floor (eval/bundle-corpus/thresholds.properties).
CREATE TABLE IF NOT EXISTS bundle_eval_run (
    id                BIGSERIAL   PRIMARY KEY,
    run_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    config_id         TEXT        NOT NULL,
    overall_recall    DOUBLE PRECISION NOT NULL,
    overall_precision DOUBLE PRECISION NOT NULL,
    recall_similarity DOUBLE PRECISION NOT NULL,
    recall_relational DOUBLE PRECISION NOT NULL,
    recall_boundary   DOUBLE PRECISION NOT NULL,
    questions_scored  INTEGER     NOT NULL,
    regression        BOOLEAN     NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_bundle_eval_run_run_at ON bundle_eval_run (run_at);
GRANT SELECT, INSERT ON bundle_eval_run TO :app_user;
GRANT USAGE, SELECT ON SEQUENCE bundle_eval_run_id_seq TO :app_user;
