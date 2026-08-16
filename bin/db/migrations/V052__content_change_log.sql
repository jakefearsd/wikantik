-- V052: content_change_log — the effect-measurement ledger for content-intelligence Phase 2.
--
-- One row per applied content change (title/summary/tags/body/new_page/internal_links edit).
-- The 28-day pre-change baseline is captured from search_visibility_snapshot AT WRITE TIME,
-- not reconstructed later -- reconstructing it later works only until retention or a GSC
-- restatement moves the ground under it (see content-intelligence design §6.3, §7.4.1).
--
-- A nightly job later fills evaluated_at/effect/effect_ctr_delta/effect_position_delta once
-- applied_at <= today - 28 and evaluated_at IS NULL (§7.4.2); that job is not part of this
-- migration. Idempotent / DDL-only.

CREATE TABLE IF NOT EXISTS content_change_log (
    id                    BIGSERIAL   PRIMARY KEY,
    page_path             TEXT        NOT NULL,
    change_type           TEXT        NOT NULL,  -- title|summary|tags|body|new_page|internal_links
    opportunity_type      TEXT,                  -- rule that motivated it, NULL if manual
    applied_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    applied_by            TEXT        NOT NULL,  -- login or agent identifier
    note                  TEXT,

    baseline_start        DATE        NOT NULL,
    baseline_end          DATE        NOT NULL,
    baseline_impressions  INTEGER     NOT NULL,
    baseline_clicks       INTEGER     NOT NULL,
    baseline_ctr          NUMERIC(8,5),
    baseline_position     NUMERIC(6,2),

    evaluated_at          TIMESTAMPTZ,
    effect                TEXT,                  -- improved|no_effect|regressed|insufficient_data
    effect_ctr_delta      NUMERIC(8,5),          -- site-adjusted, see 7.4.3
    effect_position_delta NUMERIC(6,2),
    effect_detail         JSONB
);

CREATE INDEX IF NOT EXISTS idx_ccl_page      ON content_change_log (page_path, applied_at DESC);
CREATE INDEX IF NOT EXISTS idx_ccl_pending   ON content_change_log (applied_at)
    WHERE evaluated_at IS NULL;

GRANT SELECT, INSERT, UPDATE ON content_change_log TO :app_user;
GRANT USAGE, SELECT ON SEQUENCE content_change_log_id_seq TO :app_user;
