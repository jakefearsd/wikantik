-- V057: jakemon's real EXPECTED_CTR curve (content-intelligence design §7.3 rule 2), replacing the
-- placeholder step table ExpectedCtrCurve.defaultCurve() -- numbers invented locally because
-- nothing measured shipped one. ENGINE_DIVERGENCE's priority is literally
-- weak_impressions * (ctr(strong_pos) - ctr(weak_pos)); the design's stated rule is that
-- jakemon's EXPECTED_CTR stays the single definition of the CTR model, so once jakemon ships it,
-- Wikantik must consume it rather than keep guessing.
--
-- One row per (as_of, position) -- jakemon ships the curve as a top-level `expected_ctr` object
-- keyed by stringified integer position ("1".."10") on every ingest payload
-- (POST /admin/insights/ingest), alongside the visibility rows (V050) and imported opportunities
-- (V056) already carried in the same body. Storing it dated, rather than overwriting a single
-- mutable row, lets a future recalibration (jakemon's own source comment: "the ONE place to
-- recalibrate") be observed historically instead of silently erasing what an earlier evaluation
-- actually ran against -- and it matches the re-ship-converges idempotency contract every other
-- imported table in this schema already follows (V050, V056).
--
-- No site_host column: unlike search_visibility_snapshot and imported_opportunity, jakemon ships
-- exactly one CTR-by-position curve, not one per site -- EXPECTED_CTR is a single module-level
-- dict in opportunities.py, calibrated from aggregate data, not scoped to any one property.
--
-- IMPORTANT -- only the table points (positions 1-10 today) are stored here. jakemon's tail rule
-- -- a fixed 0.008 floor for positions 11-20, decaying as round(0.008 * (20.0 / p), 4) beyond
-- that -- is deliberately NOT part of the shipped payload (ship_visibility.py sends only
-- `{str(k): v for k, v in EXPECTED_CTR.items()}`, never _DEEP_CTR or the decay formula), so it
-- cannot be imported the same way. It is mirrored instead, as configuration
-- (wikantik.insights.ctr.deep / wikantik.insights.ctr.deep_max_position, consumed by
-- ExpectedCtrCurve.fromTable). That mirroring is a deliberate, narrow coupling to jakemon's
-- source -- two independently-maintained copies of the same two numbers -- accepted only because
-- the alternative (continuing to guess a whole curve locally) is precisely the problem this
-- migration removes for the part that IS shipped. If jakemon ever starts shipping the tail too,
-- replace the mirrored config with an import the same way the top-10 table was, and drop this
-- table's implicit "positions without a row fall through to config" contract.
--
-- Idempotent / DDL-only.

CREATE TABLE IF NOT EXISTS expected_ctr_curve (
    as_of    DATE     NOT NULL,
    position SMALLINT NOT NULL,
    ctr      NUMERIC(6,5) NOT NULL,
    PRIMARY KEY (as_of, position)
);

GRANT SELECT, INSERT, UPDATE, DELETE ON expected_ctr_curve TO :app_user;
