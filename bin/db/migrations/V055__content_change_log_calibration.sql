-- V055: the columns self-calibration needs (design section 7.4.4).
--
-- Calibration compares what a rule PREDICTED against what a change actually DELIVERED, then
-- moves that rule's weight toward the observed ratio. Both halves of that comparison have to
-- survive on the change row:
--
--   predicted_priority  -- the priority the engine assigned when the change was proposed. Without
--                          it the prediction is unrecoverable once thresholds or weights move,
--                          and the ratio can never be computed retrospectively.
--   effect_click_delta  -- the realised, site-adjusted click delta. effect_ctr_delta already
--                          exists but is a rate; calibration needs the same unit the priorities
--                          are expressed in (expected incremental clicks) or the ratio is
--                          dimensionally meaningless.
--
-- effect_method records which comparison the evaluator could actually run ('query_intersection'
-- or 'page_rollup'). Today it is always page_rollup, because no query in the store is attributed
-- to a page; the two are different strengths of claim and calibration must be able to tell them
-- apart rather than averaging them together.
--
-- Idempotent / DDL-only.

ALTER TABLE content_change_log ADD COLUMN IF NOT EXISTS predicted_priority NUMERIC(10,4);
ALTER TABLE content_change_log ADD COLUMN IF NOT EXISTS effect_click_delta NUMERIC(10,2);
ALTER TABLE content_change_log ADD COLUMN IF NOT EXISTS effect_method      TEXT;

-- Calibration reads only evaluated rows, grouped by the rule that motivated them.
CREATE INDEX IF NOT EXISTS idx_ccl_calibration
    ON content_change_log (opportunity_type)
    WHERE evaluated_at IS NOT NULL AND opportunity_type IS NOT NULL;
